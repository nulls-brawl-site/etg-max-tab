(function () {
  "use strict";

  const MAX_HOST_RE = /(^|\.)max\.ru$/i;
  const FETCHABLE_EXT_RE = /\.(css|js|mjs|png|jpg|jpeg|webp|gif|svg|ico|woff2?|ttf|otf|map)(\?|$)/i;
  const RISKY_QUERY_RE = /(^|[?&])(token|auth|session|cookie|secret|password|phone|email|jwt|bearer|key|signature|sig)=/i;
  const MAX_ASSET_BYTES = 12 * 1024 * 1024;
  const MAX_TOTAL_BYTES = 70 * 1024 * 1024;
  const MAX_RESOURCE_COUNT = 450;

  chrome.runtime.onConnect.addListener((port) => {
    if (!port || port.name !== "max-export-popup") {
      return;
    }
    port.onMessage.addListener((message) => {
      if (!message || message.type !== "startExport") {
        return;
      }
      runExport(port).catch((error) => {
        safePost(port, { type: "error", error: String(error && error.message ? error.message : error) });
      });
    });
  });

  async function runExport(port) {
    const tab = await getActiveTab();
    if (!tab || !tab.id) {
      throw new Error("No active tab.");
    }
    if (!isMaxUrl(tab.url || "")) {
      throw new Error("Open web.max.ru or max.ru before exporting.");
    }

    progress(port, 5, "collect", "Reading sanitized page data...");
    const payload = await collectFromTab(tab.id);
    if (!payload || !payload.ok) {
      throw new Error(payload && payload.error ? payload.error : "Content script failed.");
    }

    const files = [];
    const skipped = [];
    const resourceReport = [];
    const collected = payload.payload;

    addText(files, "README.txt", makeReadme(collected));
    addJson(files, "page/document-meta.json", collected.meta);
    addText(files, "page/dom-redacted.html", collected.html || "");
    addText(files, "page/cssom.css", collected.cssom || "");
    addJson(files, "page/resources.json", collected.resources || []);
    addJson(files, "page/cookies-redacted.json", collected.cookies || []);
    addJson(files, "page/storage-redacted.json", collected.storage || {});
    addJson(files, "page/indexeddb-redacted.json", collected.indexedDB || {});
    addJson(files, "page/caches-redacted.json", collected.caches || {});
    addJson(files, "theme/theme-snapshot.json", collected.theme || {});

    progress(port, 18, "assets", "Fetching public CSS/JS/images/fonts without credentials...");
    await fetchResources(collected.resources || [], files, skipped, resourceReport, (done, total) => {
      const pct = 18 + Math.floor(Math.min(1, total ? done / total : 0) * 58);
      progress(port, pct, "assets", `Fetched ${done}/${total} resources`);
    });

    addJson(files, "reports/fetched-resources.json", resourceReport);
    addJson(files, "reports/skipped-resources.json", skipped);

    progress(port, 82, "zip", "Building max.zip...");
    const zip = ZipStore.create(files);
    const url = URL.createObjectURL(zip);
    progress(port, 92, "download", "Sending max.zip to Downloads...");
    const downloadId = await downloadZip(url);
    setTimeout(() => URL.revokeObjectURL(url), 30000);
    progress(port, 100, "done", `Download started: id=${downloadId}`);
    safePost(port, { type: "done", filename: "max.zip", bytes: zip.size, downloadId });
  }

  function collectFromTab(tabId) {
    return new Promise((resolve) => {
      chrome.tabs.sendMessage(tabId, { type: "collectMaxPage" }, (response) => {
        if (chrome.runtime.lastError || !response) {
          chrome.tabs.executeScript(tabId, { file: "content.js" }, () => {
            chrome.tabs.sendMessage(tabId, { type: "collectMaxPage" }, (retryResponse) => {
              resolve(retryResponse || { ok: false, error: chrome.runtime.lastError && chrome.runtime.lastError.message });
            });
          });
          return;
        }
        resolve(response);
      });
    });
  }

  function getActiveTab() {
    return new Promise((resolve) => {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => resolve(tabs && tabs[0]));
    });
  }

  async function fetchResources(initialResources, files, skipped, report, onProgress) {
    const queue = [];
    const queued = new Set();
    const fetched = new Set();
    let totalBytes = 0;
    let done = 0;

    function enqueue(url, source) {
      if (!url || queued.has(url) || fetched.has(url) || queue.length + fetched.size >= MAX_RESOURCE_COUNT) {
        return;
      }
      queued.add(url);
      queue.push({ url, source });
    }

    for (const resource of initialResources) {
      if (!resource || !resource.url) {
        continue;
      }
      const reason = skipReason(resource.url);
      if (reason) {
        skipped.push({ url: resource.displayUrl || redactUrl(resource.url), reason });
        continue;
      }
      enqueue(resource.url, resource.type || "resource");
    }

    while (queue.length) {
      const item = queue.shift();
      done++;
      onProgress(done, done + queue.length);
      fetched.add(item.url);
      try {
        const response = await fetch(item.url, {
          credentials: "omit",
          cache: "reload",
          redirect: "follow"
        });
        if (!response.ok) {
          skipped.push({ url: redactUrl(item.url), reason: `http-${response.status}` });
          continue;
        }
        const contentLength = Number(response.headers.get("content-length") || "0");
        if (contentLength && contentLength > MAX_ASSET_BYTES) {
          skipped.push({ url: redactUrl(item.url), reason: "too-large" });
          continue;
        }
        const buffer = await response.arrayBuffer();
        if (buffer.byteLength > MAX_ASSET_BYTES) {
          skipped.push({ url: redactUrl(item.url), reason: "too-large" });
          continue;
        }
        if (totalBytes + buffer.byteLength > MAX_TOTAL_BYTES) {
          skipped.push({ url: redactUrl(item.url), reason: "total-size-limit" });
          continue;
        }
        totalBytes += buffer.byteLength;
        const contentType = response.headers.get("content-type") || "";
        const path = assetPathForUrl(item.url, contentType);
        files.push({ name: path, data: new Uint8Array(buffer) });
        report.push({
          url: redactUrl(item.url),
          path,
          bytes: buffer.byteLength,
          contentType,
          source: item.source
        });

        if (/text\/css|\.css(\?|$)/i.test(contentType) || /\.css(\?|$)/i.test(item.url)) {
          const cssText = new TextDecoder("utf-8").decode(buffer);
          for (const child of cssUrls(cssText, item.url)) {
            const reason = skipReason(child);
            if (reason) {
              skipped.push({ url: redactUrl(child), reason });
            } else {
              enqueue(child, "css-url");
            }
          }
        }
        if (/javascript|ecmascript/i.test(contentType) || /\.(js|mjs)(\?|$)/i.test(item.url)) {
          const jsText = new TextDecoder("utf-8").decode(buffer.slice(Math.max(0, buffer.byteLength - 4096)));
          for (const child of sourceMapUrls(jsText, item.url)) {
            const reason = skipReason(child);
            if (reason) {
              skipped.push({ url: redactUrl(child), reason });
            } else {
              enqueue(child, "source-map");
            }
          }
        }
      } catch (error) {
        skipped.push({ url: redactUrl(item.url), reason: String(error && error.message ? error.message : error) });
      }
    }
  }

  function skipReason(rawUrl) {
    let url;
    try {
      url = new URL(rawUrl);
    } catch (ignored) {
      return "bad-url";
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return "bad-scheme";
    }
    if (!MAX_HOST_RE.test(url.hostname)) {
      return "external-host";
    }
    if (RISKY_QUERY_RE.test(url.search)) {
      return "risky-query";
    }
    if (!FETCHABLE_EXT_RE.test(url.pathname + url.search)) {
      return "not-static-asset";
    }
    return "";
  }

  function cssUrls(cssText, baseUrl) {
    const out = [];
    const re = /url\(\s*(['"]?)(.*?)\1\s*\)/gi;
    let match;
    while ((match = re.exec(cssText))) {
      const raw = match[2];
      if (!raw || raw.startsWith("data:")) {
        continue;
      }
      try {
        const url = new URL(raw, baseUrl);
        url.hash = "";
        out.push(url.href);
      } catch (ignored) {
      }
    }
    return out;
  }

  function sourceMapUrls(jsText, baseUrl) {
    const out = [];
    const re = /\/\/# sourceMappingURL=(.+)$/gm;
    let match;
    while ((match = re.exec(jsText))) {
      const raw = match[1].trim();
      if (!raw || raw.startsWith("data:")) {
        continue;
      }
      try {
        const url = new URL(raw, baseUrl);
        url.hash = "";
        out.push(url.href);
      } catch (ignored) {
      }
    }
    return out;
  }

  function isMaxUrl(rawUrl) {
    try {
      const url = new URL(rawUrl);
      return MAX_HOST_RE.test(url.hostname);
    } catch (ignored) {
      return false;
    }
  }

  function assetPathForUrl(rawUrl, contentType) {
    const url = new URL(rawUrl);
    let pathname = decodeURIComponent(url.pathname || "/");
    if (!pathname || pathname.endsWith("/")) {
      pathname += "index";
    }
    const parts = pathname.split("/")
      .filter(Boolean)
      .map((part) => part.replace(/[^\w.\-()+@~]/g, "_").slice(0, 120) || "_");
    if (!parts.length) {
      parts.push("index");
    }
    let last = parts[parts.length - 1];
    if (!/\.[a-z0-9]{1,8}$/i.test(last)) {
      last += extensionForContentType(contentType);
    }
    if (url.search) {
      last = `${last}__q_${shortHash(url.search)}`;
    }
    parts[parts.length - 1] = last;
    return `assets/${url.hostname}/${parts.join("/")}`;
  }

  function extensionForContentType(contentType) {
    const value = (contentType || "").toLowerCase();
    if (value.includes("text/css")) return ".css";
    if (value.includes("javascript") || value.includes("ecmascript")) return ".js";
    if (value.includes("image/png")) return ".png";
    if (value.includes("image/jpeg")) return ".jpg";
    if (value.includes("image/webp")) return ".webp";
    if (value.includes("image/svg")) return ".svg";
    if (value.includes("font/woff2")) return ".woff2";
    if (value.includes("font/woff")) return ".woff";
    if (value.includes("application/json")) return ".json";
    return ".bin";
  }

  function redactUrl(rawUrl) {
    try {
      const url = new URL(rawUrl);
      for (const key of Array.from(url.searchParams.keys())) {
        if (/token|auth|session|cookie|secret|password|phone|email|jwt|bearer|key|signature|sig/i.test(key)) {
          url.searchParams.set(key, "[redacted]");
        }
      }
      url.hash = "";
      return url.href;
    } catch (ignored) {
      return "[bad-url]";
    }
  }

  function addText(files, name, text) {
    files.push({ name, data: String(text) });
  }

  function addJson(files, name, data) {
    addText(files, name, `${JSON.stringify(data, null, 2)}\n`);
  }

  function makeReadme(collected) {
    return [
      "MAX Safe Exporter archive",
      "",
      "This archive intentionally excludes personal values:",
      "- cookie values are redacted",
      "- localStorage/sessionStorage values are redacted",
      "- IndexedDB/cache records are not exported",
      "- DOM text, form values, titles, alt text, aria labels, and risky data attributes are redacted",
      "- fetched resources use credentials: omit, so session cookies are not sent",
      "",
      `Collected at: ${collected.collectedAt}`,
      `Page: ${collected.meta && collected.meta.origin ? collected.meta.origin : "unknown"}`,
      "",
      "Useful files:",
      "- page/dom-redacted.html",
      "- page/cssom.css",
      "- theme/theme-snapshot.json",
      "- reports/fetched-resources.json",
      "- reports/skipped-resources.json",
      "- assets/"
    ].join("\n") + "\n";
  }

  function downloadZip(url) {
    return new Promise((resolve, reject) => {
      chrome.downloads.download({
        url,
        filename: "max.zip",
        conflictAction: "overwrite",
        saveAs: false
      }, (downloadId) => {
        if (chrome.runtime.lastError) {
          reject(new Error(chrome.runtime.lastError.message));
          return;
        }
        resolve(downloadId);
      });
    });
  }

  function progress(port, percent, state, text) {
    safePost(port, { type: "progress", percent, state, text });
  }

  function safePost(port, message) {
    try {
      port.postMessage(message);
    } catch (ignored) {
    }
  }

  function shortHash(value) {
    let hash = 2166136261;
    for (let i = 0; i < value.length; i++) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16).padStart(8, "0");
  }
}());

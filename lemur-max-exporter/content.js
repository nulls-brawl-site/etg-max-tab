(function () {
  "use strict";

  const VERSION = "1.0.0";
  const MAX_HOST_RE = /(^|\.)max\.ru$/i;
  const RISKY_KEY_RE = /(token|auth|session|cookie|secret|password|phone|email|mail|user|uid|peer|chat|message|contact|profile|avatar|photo|name|login|jwt|bearer)/i;
  const REDACTED = "[redacted]";

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (!message || message.type !== "collectMaxPage") {
      return false;
    }
    collectMaxPage()
      .then((payload) => sendResponse({ ok: true, payload }))
      .catch((error) => sendResponse({ ok: false, error: String(error && error.message ? error.message : error) }));
    return true;
  });

  async function collectMaxPage() {
    if (!MAX_HOST_RE.test(location.hostname)) {
      throw new Error("Open a max.ru or web.max.ru page first.");
    }

    const resources = new Map();
    const cssom = collectStyleSheets(resources);
    collectDomResources(resources);
    collectPerformanceResources(resources);

    return {
      version: VERSION,
      collectedAt: new Date().toISOString(),
      meta: collectMeta(),
      html: sanitizeHtml(),
      cssom,
      resources: Array.from(resources.values()),
      cookies: collectCookieNames(),
      storage: collectStorageMeta(),
      indexedDB: await collectIndexedDbMeta(),
      caches: await collectCacheMeta(),
      theme: collectThemeSnapshot()
    };
  }

  function collectMeta() {
    return {
      url: location.href,
      origin: location.origin,
      pathname: location.pathname,
      title: REDACTED,
      referrer: document.referrer ? "[redacted-referrer]" : "",
      contentType: document.contentType,
      charset: document.characterSet,
      direction: document.dir || document.documentElement.getAttribute("dir") || "",
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
        devicePixelRatio: window.devicePixelRatio
      },
      media: {
        prefersDark: matchMedia("(prefers-color-scheme: dark)").matches,
        prefersReducedMotion: matchMedia("(prefers-reduced-motion: reduce)").matches
      }
    };
  }

  function sanitizeHtml() {
    const clone = document.documentElement.cloneNode(true);
    sanitizeElementTree(clone);
    redactTextNodes(clone);
    return `${serializeDoctype()}\n${clone.outerHTML}\n`;
  }

  function serializeDoctype() {
    const dt = document.doctype;
    if (!dt) {
      return "<!doctype html>";
    }
    let out = `<!doctype ${dt.name}`;
    if (dt.publicId) {
      out += ` PUBLIC "${dt.publicId}"`;
    }
    if (dt.systemId) {
      out += dt.publicId ? ` "${dt.systemId}"` : ` SYSTEM "${dt.systemId}"`;
    }
    return `${out}>`;
  }

  function sanitizeElementTree(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    let node = root;
    while (node) {
      sanitizeElement(node);
      node = walker.nextNode();
    }
  }

  function sanitizeElement(el) {
    const tag = el.tagName ? el.tagName.toLowerCase() : "";
    if (tag === "script") {
      if (!el.getAttribute("src")) {
        el.textContent = "";
        el.setAttribute("data-max-export", "inline-script-redacted");
      }
    }
    if (tag === "input" || tag === "textarea" || tag === "select" || tag === "option") {
      el.textContent = "";
      el.setAttribute("data-max-export", "form-state-redacted");
    }

    for (const attr of Array.from(el.attributes || [])) {
      const name = attr.name;
      const lower = name.toLowerCase();
      const value = attr.value || "";
      if (lower.startsWith("on")) {
        el.removeAttribute(name);
        continue;
      }
      if (lower.startsWith("data-") && !/^data-(test|testid|qa|role)$/i.test(lower)) {
        el.removeAttribute(name);
        continue;
      }
      if (lower === "value" || lower === "placeholder" || lower === "title" || lower === "alt"
          || lower === "aria-label" || lower === "aria-description" || lower === "aria-valuetext") {
        el.setAttribute(name, REDACTED);
        continue;
      }
      if (RISKY_KEY_RE.test(lower)) {
        el.setAttribute(name, REDACTED);
        continue;
      }
      if ((lower === "href" || lower === "src" || lower === "action") && /^\s*javascript:/i.test(value)) {
        el.removeAttribute(name);
      }
    }
  }

  function redactTextNodes(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    let node = walker.nextNode();
    while (node) {
      nodes.push(node);
      node = walker.nextNode();
    }
    for (const text of nodes) {
      const parentTag = text.parentElement && text.parentElement.tagName
        ? text.parentElement.tagName.toLowerCase()
        : "";
      if (parentTag === "style") {
        continue;
      }
      if (parentTag === "script") {
        text.nodeValue = "";
        continue;
      }
      if (text.nodeValue && text.nodeValue.trim()) {
        text.nodeValue = preserveOuterWhitespace(text.nodeValue, REDACTED);
      }
    }
  }

  function preserveOuterWhitespace(value, replacement) {
    const before = (value.match(/^\s*/) || [""])[0];
    const after = (value.match(/\s*$/) || [""])[0];
    return `${before}${replacement}${after}`;
  }

  function collectDomResources(resources) {
    const selectors = [
      ["link[href]", "href", "link"],
      ["script[src]", "src", "script"],
      ["img[src]", "src", "image"],
      ["img[srcset]", "srcset", "srcset"],
      ["source[src]", "src", "media"],
      ["source[srcset]", "srcset", "srcset"],
      ["video[src]", "src", "video"],
      ["audio[src]", "src", "audio"],
      ["track[src]", "src", "track"],
      ["embed[src]", "src", "embed"],
      ["object[data]", "data", "object"],
      ["use[href]", "href", "svg-use"],
      ["use[xlink\\:href]", "xlink:href", "svg-use"]
    ];
    for (const [selector, attr, type] of selectors) {
      for (const el of document.querySelectorAll(selector)) {
        const raw = el.getAttribute(attr);
        if (!raw) {
          continue;
        }
        if (attr === "srcset") {
          for (const candidate of parseSrcset(raw)) {
            addResource(resources, candidate, type, "dom");
          }
        } else {
          addResource(resources, raw, type, "dom");
        }
      }
    }
    for (const el of document.querySelectorAll("[style]")) {
      collectCssUrls(el.getAttribute("style") || "", resources, "inline-style");
    }
  }

  function collectStyleSheets(resources) {
    const chunks = [];
    for (const sheet of Array.from(document.styleSheets || [])) {
      const href = sheet.href || "";
      if (href) {
        addResource(resources, href, "stylesheet", "cssom");
      }
      try {
        const rules = Array.from(sheet.cssRules || []);
        if (!rules.length) {
          continue;
        }
        chunks.push(`/* stylesheet: ${href || "inline"} */`);
        for (const rule of rules) {
          const text = rule.cssText || "";
          chunks.push(text);
          collectCssUrls(text, resources, href || "cssom");
        }
      } catch (error) {
        chunks.push(`/* inaccessible stylesheet: ${href || "inline"} */`);
      }
    }
    return chunks.join("\n");
  }

  function collectPerformanceResources(resources) {
    try {
      const safeTypes = new Set(["script", "css", "link", "img", "image", "font", "video", "audio"]);
      for (const entry of performance.getEntriesByType("resource") || []) {
        if (!entry || !entry.name || !safeTypes.has(entry.initiatorType)) {
          continue;
        }
        addResource(resources, entry.name, entry.initiatorType || "resource", "performance");
      }
    } catch (ignored) {
    }
  }

  function collectCssUrls(cssText, resources, source) {
    const re = /url\(\s*(['"]?)(.*?)\1\s*\)/gi;
    let match;
    while ((match = re.exec(cssText))) {
      const raw = match[2];
      if (raw && !raw.startsWith("data:")) {
        addResource(resources, raw, "css-url", source);
      }
    }
  }

  function addResource(resources, raw, type, source) {
    const url = normalizeUrl(raw);
    if (!url) {
      return;
    }
    const key = stripHash(url);
    const existing = resources.get(key);
    if (existing) {
      existing.sources.push(source);
      return;
    }
    const parsed = new URL(key);
    resources.set(key, {
      url: key,
      displayUrl: redactUrl(key),
      host: parsed.hostname,
      path: parsed.pathname,
      type,
      sources: [source],
      sameSite: MAX_HOST_RE.test(parsed.hostname)
    });
  }

  function normalizeUrl(raw) {
    if (!raw || typeof raw !== "string") {
      return null;
    }
    const value = raw.trim();
    if (!value || value.startsWith("#") || value.startsWith("data:") || value.startsWith("blob:") || value.startsWith("javascript:")) {
      return null;
    }
    try {
      const url = new URL(value, document.baseURI);
      if (url.protocol !== "http:" && url.protocol !== "https:") {
        return null;
      }
      return url.href;
    } catch (ignored) {
      return null;
    }
  }

  function stripHash(url) {
    const parsed = new URL(url);
    parsed.hash = "";
    return parsed.href;
  }

  function redactUrl(url) {
    try {
      const parsed = new URL(url);
      for (const key of Array.from(parsed.searchParams.keys())) {
        if (RISKY_KEY_RE.test(key)) {
          parsed.searchParams.set(key, REDACTED);
        }
      }
      parsed.hash = "";
      return parsed.href;
    } catch (ignored) {
      return REDACTED;
    }
  }

  function parseSrcset(value) {
    return value.split(",")
      .map((part) => part.trim().split(/\s+/)[0])
      .filter(Boolean);
  }

  function collectCookieNames() {
    try {
      return document.cookie.split(";")
        .map((part) => part.trim())
        .filter(Boolean)
        .map((part, index) => {
          const sep = part.indexOf("=");
          const rawName = sep >= 0 ? part.slice(0, sep) : part;
          return {
            name: safeName(rawName, `cookie_${index}`),
            value: REDACTED
          };
        });
    } catch (ignored) {
      return [];
    }
  }

  function collectStorageMeta() {
    return {
      localStorage: readStorageMeta(window.localStorage),
      sessionStorage: readStorageMeta(window.sessionStorage)
    };
  }

  function readStorageMeta(storage) {
    const rows = [];
    try {
      for (let i = 0; i < storage.length; i++) {
        const rawKey = storage.key(i);
        const value = storage.getItem(rawKey);
        rows.push({
          key: safeName(rawKey, `key_${i}`),
          value: REDACTED,
          valueLength: typeof value === "string" ? value.length : 0,
          looksJson: typeof value === "string" && /^[\[{]/.test(value.trim())
        });
      }
    } catch (ignored) {
    }
    return rows;
  }

  async function collectIndexedDbMeta() {
    try {
      if (!indexedDB.databases) {
        return { available: false, databases: [] };
      }
      const dbs = await indexedDB.databases();
      return {
        available: true,
        databases: (dbs || []).map((db, index) => ({
          name: safeName(db && db.name ? db.name : "", `indexeddb_${index}`),
          version: db && db.version ? db.version : null,
          records: REDACTED
        }))
      };
    } catch (ignored) {
      return { available: false, databases: [] };
    }
  }

  async function collectCacheMeta() {
    try {
      if (!window.caches || !caches.keys) {
        return { available: false, caches: [] };
      }
      const names = await caches.keys();
      return {
        available: true,
        caches: names.map((name, index) => ({
          name: safeName(name, `cache_${index}`),
          requests: REDACTED
        }))
      };
    } catch (ignored) {
      return { available: false, caches: [] };
    }
  }

  function safeName(value, fallback) {
    const text = String(value || "");
    return text && !RISKY_KEY_RE.test(text) ? text : fallback;
  }

  function collectThemeSnapshot() {
    const root = getComputedStyle(document.documentElement);
    const body = document.body ? getComputedStyle(document.body) : root;
    const vars = {};
    for (const style of [root, body]) {
      for (let i = 0; i < style.length; i++) {
        const name = style[i];
        if (name && name.startsWith("--") && !RISKY_KEY_RE.test(name)) {
          vars[name] = style.getPropertyValue(name).trim();
        }
      }
    }
    return {
      prefersDark: matchMedia("(prefers-color-scheme: dark)").matches,
      documentColorScheme: root.colorScheme || "",
      root: readThemeStyle(root),
      body: readThemeStyle(body),
      cssVariables: vars,
      backgroundImages: collectBackgroundImages()
    };
  }

  function readThemeStyle(style) {
    return {
      color: style.color,
      backgroundColor: style.backgroundColor,
      fontFamily: style.fontFamily,
      accentColor: style.accentColor || "",
      colorScheme: style.colorScheme || ""
    };
  }

  function collectBackgroundImages() {
    const out = [];
    const seen = new Set();
    for (const el of [document.documentElement, document.body, ...Array.from(document.querySelectorAll("*")).slice(0, 500)]) {
      if (!el) {
        continue;
      }
      const style = getComputedStyle(el);
      const bg = style.backgroundImage;
      if (!bg || bg === "none" || seen.has(bg)) {
        continue;
      }
      seen.add(bg);
      out.push({
        selector: describeElement(el),
        backgroundImage: bg.replace(/url\((['"]?).*?\1\)/g, "url([redacted-url])")
      });
      if (out.length >= 80) {
        break;
      }
    }
    return out;
  }

  function describeElement(el) {
    const tag = el.tagName ? el.tagName.toLowerCase() : "node";
    const id = el.id && !RISKY_KEY_RE.test(el.id) ? `#${el.id}` : "";
    const cls = Array.from(el.classList || [])
      .filter((name) => !RISKY_KEY_RE.test(name))
      .slice(0, 4)
      .map((name) => `.${name}`)
      .join("");
    return `${tag}${id}${cls}`;
  }
}());

(function () {
  "use strict";

  const button = document.getElementById("exportButton");
  const log = document.getElementById("log");
  const state = document.getElementById("state");
  const progress = document.getElementById("progress");

  let port = null;

  function line(text) {
    log.textContent += `${text}\n`;
    log.scrollTop = log.scrollHeight;
  }

  function setState(text, value) {
    state.textContent = text;
    if (typeof value === "number") {
      progress.value = Math.max(0, Math.min(100, value));
    }
  }

  button.addEventListener("click", () => {
    button.disabled = true;
    log.textContent = "";
    setState("running", 2);
    line("Collecting current MAX tab...");

    port = chrome.runtime.connect({ name: "max-export-popup" });
    port.onMessage.addListener((message) => {
      if (!message || typeof message !== "object") {
        return;
      }
      if (message.type === "progress") {
        setState(message.state || "running", message.percent);
        if (message.text) {
          line(message.text);
        }
        return;
      }
      if (message.type === "done") {
        setState("done", 100);
        line(`Saved: ${message.filename || "max.zip"}`);
        if (message.bytes) {
          line(`ZIP size: ${message.bytes} bytes`);
        }
        button.disabled = false;
        return;
      }
      if (message.type === "error") {
        setState("error", 0);
        line(message.error || "Export failed");
        button.disabled = false;
      }
    });
    port.onDisconnect.addListener(() => {
      port = null;
      button.disabled = false;
    });
    port.postMessage({ type: "startExport" });
  });
}());

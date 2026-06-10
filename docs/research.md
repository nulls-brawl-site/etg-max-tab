# Research Notes

No official `web.max.ru` frontend source repository was found on GitHub.

Sources used for resilient selectors and behavior:

- `https://web.max.ru/`: public SvelteKit shell and immutable CSS/JS assets. The current CSS exposes `data-bubbles-variant` and many `--bubbles-*` custom properties, so the bridge overrides those variables instead of depending only on hashed Svelte classes.
- `https://github.com/me0wkie/maxplus`: Svelte/Tauri MAX client. The bridge supports its chat UI class names such as `.chat-item`, `.message-row`, `.message-bubble`, `.preview`, `.badge`, and `.tabs-container`.
- `https://github.com/ilcommm/Max2iMessage`: WebView-based MAX monitor. The bridge only borrows the idea that WebSocket events are a useful local signal to re-run DOM marking after sync/message packets. It does not forward packet data to native code or any remote endpoint.
- `https://github.com/PronikFire/Max-API-Guide`: community MAX API notes. Used only to confirm that the web client is driven by WebSocket sync/message events.

Implementation assumptions:

- Login, session storage, phone auth, QR auth, and the actual chat transport stay fully inside the official `https://web.max.ru/` page.
- The dex bridge creates only the Telegram-side tab, the full-screen overlay, WebView settings, and a CSS/JS restyle layer.
- DOM selectors intentionally prefer roles, `data-bubbles-variant`, and semantic class fragments over exact generated Svelte classes.

# MAX Safe Exporter for Lemur Browser

Local Chrome-compatible extension for Lemur Browser. It exports the current
`max.ru` / `web.max.ru` page into `max.zip` through the browser downloads API.
On Android/Lemur this normally lands in `/sdcard/Download/max.zip`.

The exporter is intentionally safe:

- no `cookies` permission;
- cookie values are not written;
- `localStorage` and `sessionStorage` values are not written;
- IndexedDB and Cache API records are not dumped;
- DOM text, input values, titles, alt text, ARIA labels, and risky data
  attributes are redacted;
- static assets are fetched with `credentials: "omit"`;
- only `max.ru` / `*.max.ru` static assets are fetched.

The archive contains:

- `page/dom-redacted.html`
- `page/cssom.css`
- `page/resources.json`
- `page/cookies-redacted.json`
- `page/storage-redacted.json`
- `theme/theme-snapshot.json`
- `reports/fetched-resources.json`
- `reports/skipped-resources.json`
- `assets/...`

## Install

Import `dist/lemur-max-exporter.zip` as a local extension in Lemur Browser, or
load the unpacked `lemur-max-exporter` folder if your build exposes that option.

Open `https://web.max.ru/`, tap the extension button, then tap `Export max.zip`.

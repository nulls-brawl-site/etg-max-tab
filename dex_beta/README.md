# MAX Tab Dex Beta

This folder is for experimental bridge updates. The published stable bridge in
`dex/src` is not changed here.

Current beta changes:

- injects ExteraGram/Telegram theme colors into `web.max.ru`;
- maps Telegram chat wallpaper colors to MAX chat background CSS variables;
- renders the current Telegram themed wallpaper drawable into a local data URL
  and applies it inside the MAX WebView;
- keeps the existing stable tab/overlay behavior as the base.

Build beta dex with:

```bash
ANDROID_JAR=/path/to/android.jar ./scripts/build_beta_dex.sh
```

Output:

```text
dex_beta/build/etg-max-bridge-beta.dex
```

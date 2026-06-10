# ETG MAX Tab

ExteraGram plugin + dex bridge that adds a rightmost `MAX` tab to the chat-folder strip and opens `https://web.max.ru/` in an embedded native Android `WebView`.

The Python plugin is only the loader/hook layer. UI creation, WebView configuration, and DOM restyling live in `dex/src/com/etgmax/bridge/MaxBridge.java`.

## Build

Install Android SDK platform 34 or 35, then:

```bash
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar" ./scripts/build_dex.sh
./scripts/update_loader_sha.py
```

Artifacts:

- `plugin/etg_max.py`
- `build/etg-max-bridge.dex`

## Publish

Create a GitHub release and upload `build/etg-max-bridge.dex`. The loader defaults to:

```text
https://github.com/nulls-brawl-site/etg-max-tab/releases/latest/download/etg-max-bridge.dex
```

After publishing, install `plugin/etg_max.py` into:

```text
/data/user/0/com.exteragram.messenger/files/plugins/etg_max.py
```

## Notes

`web.max.ru` is a Svelte SPA. Public HTML only exposes the app shell; chat DOM is created after login and can change. The bridge therefore injects a resilient CSS/JS layer with broad selectors and a `MutationObserver` instead of depending on one fixed class map.

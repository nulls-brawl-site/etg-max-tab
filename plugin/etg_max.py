import hashlib
import os
import urllib.request
from typing import Any, List

from base_plugin import BasePlugin, MethodHook
from client_utils import PLUGINS_QUEUE, run_on_queue
from file_utils import ensure_dir_exists
from hook_utils import find_class
from java import jclass
from java.lang import ClassLoader
from ui.settings import Header, Input, Switch, Text

__id__ = "etg_max"
__name__ = "MAX Tab"
__description__ = "Adds a rightmost MAX tab to ExteraGram chat folders and opens web.max.ru in a native WebView."
__author__ = "@nulls-brawl-site"
__version__ = "1.0.0"
__icon__ = "msg_plugins"
__app_version__ = ">=12.5.1"
__sdk_version__ = ">=1.4.3.6"

ENTRY_CLASS = "com.etgmax.bridge.MaxBridge"
DEFAULT_DEX_URL = "https://github.com/nulls-brawl-site/etg-max-tab/releases/latest/download/etg-max-bridge.dex"
DEFAULT_DEX_SHA256 = "6436d0ade8aaa3df803339d4079995a04dead3204b9ff51310f24d361ffca40f"


class _AfterCreateView(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        self.plugin.install_tab(param.thisObject)


class _AfterUpdateTabs(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        self.plugin.install_tab(param.thisObject)


class _BeforeDestroy(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def before_hooked_method(self, param):
        self.plugin.hide_tab(param.thisObject)


class MaxTabPlugin(BasePlugin):
    def on_plugin_load(self):
        self._bridge = None
        self._bridge_ready = False
        self._pending_fragments = []
        self._hooks = []
        run_on_queue(self._load_bridge, PLUGINS_QUEUE)
        self._install_hooks()

    def on_plugin_unload(self):
        self._pending_fragments = []

    def create_settings(self) -> List[Any]:
        return [
            Header(text="MAX"),
            Switch(
                key="auto_download",
                text="Auto-download dex",
                default=True,
                subtext="Loads the native bridge from the configured URL.",
                icon="msg_plugins",
            ),
            Input(
                key="dex_url",
                text="Dex URL",
                default=DEFAULT_DEX_URL,
                subtext="GitHub release asset URL.",
                icon="msg_link",
            ),
            Input(
                key="dex_sha256",
                text="Dex SHA-256",
                default=DEFAULT_DEX_SHA256,
                subtext="Leave empty only while testing your own build.",
                icon="msg_info",
            ),
            Text(text="Reload plugin after changing URL or checksum.", icon="msg_info"),
        ]

    def _install_hooks(self):
        DialogsActivity = find_class("org.telegram.ui.DialogsActivity")
        Context = find_class("android.content.Context")
        Boolean = jclass("java.lang.Boolean")
        if DialogsActivity is None or Context is None:
            self.log("MAX Tab: DialogsActivity/Context not found")
            return
        try:
            create_view = DialogsActivity.getDeclaredMethod("createView", Context)
            create_view.setAccessible(True)
            self.hook_method(create_view, _AfterCreateView(self))

            update_tabs = DialogsActivity.getDeclaredMethod("updateFilterTabs", Boolean.TYPE, Boolean.TYPE)
            update_tabs.setAccessible(True)
            self.hook_method(update_tabs, _AfterUpdateTabs(self))

            destroy = DialogsActivity.getDeclaredMethod("onFragmentDestroy")
            destroy.setAccessible(True)
            self.hook_method(destroy, _BeforeDestroy(self))
            self.log("MAX Tab: hooks installed")
        except Exception as e:
            self.log(f"MAX Tab: hook install failed: {e}")

    def _load_bridge(self):
        try:
            dex_path = self._ensure_dex()
            if not dex_path:
                return
            ctx = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
            opt_dir = os.path.join(str(ctx.getCodeCacheDir()), "etg_max_dex_opt")
            ensure_dir_exists(opt_dir)
            DexClassLoader = jclass("dalvik.system.DexClassLoader")
            loader = DexClassLoader(dex_path, opt_dir, None, ctx.getClassLoader() or ClassLoader.getSystemClassLoader())
            self._bridge = loader.loadClass(ENTRY_CLASS)
            self._bridge_ready = True
            self.log("MAX Tab: dex bridge loaded")
            for fragment in list(self._pending_fragments):
                self.install_tab(fragment)
            self._pending_fragments = []
        except Exception as e:
            self.log(f"MAX Tab: dex load failed: {e}")

    def _ensure_dex(self):
        ctx = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
        dex_dir = os.path.join(str(ctx.getFilesDir()), "etg_max")
        ensure_dir_exists(dex_dir)
        dex_path = os.path.join(dex_dir, "etg-max-bridge.dex")
        url = self.get_setting("dex_url", DEFAULT_DEX_URL)
        expected_sha = (self.get_setting("dex_sha256", DEFAULT_DEX_SHA256) or "").strip().lower()
        if os.path.exists(dex_path) and os.path.getsize(dex_path) > 1024:
            if not expected_sha or self._sha256(dex_path) == expected_sha:
                return dex_path
        if not self.get_setting("auto_download", True):
            self.log("MAX Tab: dex missing and auto-download is off")
            return None
        tmp_path = dex_path + ".tmp"
        try:
            with urllib.request.urlopen(url, timeout=25) as response:
                data = response.read()
            if len(data) < 1024:
                self.log("MAX Tab: downloaded dex is too small")
                return None
            got_sha = hashlib.sha256(data).hexdigest()
            if expected_sha and got_sha != expected_sha:
                self.log(f"MAX Tab: dex sha mismatch: {got_sha}")
                return None
            with open(tmp_path, "wb") as f:
                f.write(data)
            os.replace(tmp_path, dex_path)
            self.log(f"MAX Tab: dex downloaded sha256={got_sha}")
            return dex_path
        except Exception as e:
            self.log(f"MAX Tab: dex download failed: {e}")
            return None
        finally:
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except Exception:
                return None

    def install_tab(self, fragment):
        if fragment is None:
            return
        if not self._bridge_ready:
            if fragment not in self._pending_fragments:
                self._pending_fragments.append(fragment)
            return
        try:
            self._bridge.install(fragment)
        except Exception as e:
            self.log(f"MAX Tab: install failed: {e}")

    def hide_tab(self, fragment):
        if self._bridge_ready and fragment is not None:
            try:
                self._bridge.hide(fragment)
            except Exception:
                return

    def _sha256(self, path):
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1024 * 128), b""):
                h.update(chunk)
        return h.hexdigest()

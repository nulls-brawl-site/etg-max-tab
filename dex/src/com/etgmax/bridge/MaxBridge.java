package com.etgmax.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

public final class MaxBridge {
    private static final int MAX_TAB_ID = 0x4d415858;
    private static final String TAB_TAG = "etg_max_tab";
    private static final String BUTTON_TAG = "etg_max_button";
    private static final String OVERLAY_TAG = "etg_max_overlay";
    private static final String URL = "https://web.max.ru/";
    private static View activeFilterTabs;
    private static Object activeDialogsActivity;
    private static Object activeOriginalDelegate;
    private static Class<?> activeDelegateType;
    private static int restoreTabId = Integer.MIN_VALUE;
    private static int restorePosition = -1;
    private static boolean floatingStateSaved;
    private static boolean previousFloatingHidden;
    private static boolean previousFloatingForceVisible;

    private MaxBridge() {
    }

    public static void install(Object dialogsActivity) {
        installWithStatus(dialogsActivity);
    }

    public static String installWithStatus(Object dialogsActivity) {
        if (dialogsActivity == null) {
            return "install: fragment=null";
        }
        Object target = resolveDialogsActivity(dialogsActivity);
        if (target == null) {
            return "install: dialogsActivity not resolved from " + dialogsActivity.getClass().getName();
        }
        Activity activity = getActivity(target);
        View root = getFragmentView(target);
        View filterTabs = getFieldView(target, "filterTabsView");
        if (activity == null || root == null || filterTabs == null) {
            return "install: missing activity=" + (activity != null)
                    + " root=" + (root != null)
                    + " filterTabsView=" + (filterTabs != null);
        }
        cleanupOldStandaloneButton(root, filterTabs);
        return installFilterTab(target, activity, root, filterTabs);
    }

    private static Object resolveDialogsActivity(Object fragment) {
        if (fragment == null) {
            return null;
        }
        if ("org.telegram.ui.DialogsActivity".equals(fragment.getClass().getName())) {
            return fragment;
        }
        try {
            Method method = fragment.getClass().getMethod("getDialogsActivity");
            Object value = method.invoke(fragment);
            if (value != null && "org.telegram.ui.DialogsActivity".equals(value.getClass().getName())) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field field = findField(fragment.getClass(), "dialogsActivity");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(fragment);
                if (value != null && "org.telegram.ui.DialogsActivity".equals(value.getClass().getName())) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String installFilterTab(Object dialogsActivity, Activity activity, View root, View filterTabs) {
        boolean unwrapped = unwrapFilterTabsDelegate(filterTabs);
        if (TAB_TAG.equals(filterTabs.getTag())) {
            filterTabs.setTag(null);
        }
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return "cleanup: no tabs field class=" + filterTabs.getClass().getName() + " unwrapped=" + unwrapped;
            }
            tabsField.setAccessible(true);
            Object tabsValue = tabsField.get(filterTabs);
            if (!(tabsValue instanceof ArrayList)) {
                return "tab: tabs not ArrayList class=" + filterTabs.getClass().getName() + " unwrapped=" + unwrapped;
            }
            ArrayList tabs = (ArrayList) tabsValue;
            int removedIndex = -1;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                Object tab = tabs.get(i);
                if (getIntField(tab, "id", Integer.MIN_VALUE) == MAX_TAB_ID) {
                    tabs.remove(i);
                    removedIndex = i;
                }
            }
            if (removedIndex >= 0) {
                repairMappingsAfterMaxRemoval(filterTabs, tabs, removedIndex);
            }
            repairMaxSelection(filterTabs, tabs);

            Method addTab = filterTabs.getClass().getMethod(
                    "addTab",
                    int.class,
                    int.class,
                    String.class,
                    String.class,
                    ArrayList.class,
                    boolean.class,
                    boolean.class,
                    boolean.class
            );
            addTab.setAccessible(true);
            addTab.invoke(filterTabs, MAX_TAB_ID, MAX_TAB_ID, "MAX", null, null, false, false, false);
            rebuildTabMappings(filterTabs, tabs);
            boolean wrapped = wrapFilterTabsDelegate(dialogsActivity, activity, root, filterTabs);
            boolean overlayActive = root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null;
            if (overlayActive) {
                int maxIndex = findMaxTabIndex(tabs);
                if (maxIndex >= 0) {
                    setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
                    setIntField(filterTabs, "currentPosition", maxIndex);
                    setIntField(filterTabs, "oldAnimatedTab", maxIndex);
                    activeFilterTabs = filterTabs;
                }
            }
            notifyTabsChanged(filterTabs);
            return "tab: installed real locked=false removedOld=" + (removedIndex >= 0)
                    + " tabs=" + tabs.size()
                    + " wrapped=" + wrapped
                    + " unwrapped=" + unwrapped
                    + " class=" + filterTabs.getClass().getName();
        } catch (Throwable e) {
            return "tab: error=" + e.getClass().getSimpleName()
                    + ":" + e.getMessage()
                    + " class=" + filterTabs.getClass().getName()
                    + " unwrapped=" + unwrapped;
        }
    }

    private static void cleanupOldStandaloneButton(View root, View filterTabs) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        try {
            ViewGroup rootGroup = (ViewGroup) root;
            View old = rootGroup.findViewWithTag(TAB_TAG);
            if (old != null && old != filterTabs && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }
            View button = rootGroup.findViewWithTag(BUTTON_TAG);
            if (button != null && button.getParent() instanceof ViewGroup) {
                ((ViewGroup) button.getParent()).removeView(button);
            }
            if (filterTabs instanceof ViewGroup) {
                View nestedButton = ((ViewGroup) filterTabs).findViewWithTag(BUTTON_TAG);
                if (nestedButton != null && nestedButton.getParent() instanceof ViewGroup) {
                    ((ViewGroup) nestedButton.getParent()).removeView(nestedButton);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean unwrapFilterTabsDelegate(View filterTabs) {
        try {
            Field delegateField = findField(filterTabs.getClass(), "delegate");
            if (delegateField == null) {
                return false;
            }
            delegateField.setAccessible(true);
            Object current = delegateField.get(filterTabs);
            if (current == null || !Proxy.isProxyClass(current.getClass())) {
                return false;
            }
            InvocationHandler handler = Proxy.getInvocationHandler(current);
            if (handler == null || handler.getClass().getName().indexOf("MaxBridge$MaxTabDelegateHandler") < 0) {
                return false;
            }
            Field originalField = findField(handler.getClass(), "original");
            if (originalField == null) {
                return false;
            }
            originalField.setAccessible(true);
            Object original = originalField.get(handler);
            if (original != null) {
                delegateField.set(filterTabs, original);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean wrapFilterTabsDelegate(Object dialogsActivity, Activity activity, View root, View filterTabs) {
        try {
            Field delegateField = findField(filterTabs.getClass(), "delegate");
            if (delegateField == null) {
                return false;
            }
            delegateField.setAccessible(true);
            Object original = delegateField.get(filterTabs);
            if (original == null) {
                return false;
            }
            Class<?> delegateType = delegateField.getType();
            Object proxy = Proxy.newProxyInstance(
                    delegateType.getClassLoader(),
                    new Class[]{delegateType},
                    new MaxTabDelegateHandler(original, delegateType, dialogsActivity, activity, root, filterTabs)
            );
            delegateField.set(filterTabs, proxy);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void notifyTabsChanged(View filterTabs) {
        try {
            Field adapterField = findField(filterTabs.getClass(), "adapter");
            if (adapterField != null) {
                adapterField.setAccessible(true);
                Object adapter = adapterField.get(filterTabs);
                if (adapter != null) {
                    Method notify = adapter.getClass().getMethod("notifyDataSetChanged");
                    notify.setAccessible(true);
                    notify.invoke(adapter);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            filterTabs.requestLayout();
            filterTabs.invalidate();
        } catch (Throwable ignored) {
        }
    }

    private static final class MaxTabDelegateHandler implements InvocationHandler {
        private final Object original;
        private final Class<?> delegateType;
        private final Object dialogsActivity;
        private final Activity activity;
        private final View root;
        private final View filterTabs;

        MaxTabDelegateHandler(Object original, Class<?> delegateType, Object dialogsActivity, Activity activity, View root, View filterTabs) {
            this.original = original;
            this.delegateType = delegateType;
            this.dialogsActivity = dialogsActivity;
            this.activity = activity;
            this.root = root;
            this.filterTabs = filterTabs;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("didSelectTab".equals(name) && args != null && args.length > 0) {
                if (isMaxTabView(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showOverlay(activity, root, filterTabs, dialogsActivity);
                    return true;
                }
                hideOverlay(root);
            }
            if (("onTabSelected".equals(name) || "onPageSelected".equals(name)) && args != null && args.length > 0) {
                if (isMaxTab(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showOverlay(activity, root, filterTabs, dialogsActivity);
                    return defaultValue(method.getReturnType());
                }
                hideOverlay(root);
            }
            return method.invoke(original, args);
        }
    }

    private static boolean isMaxTabView(Object tabView) {
        try {
            Field field = findField(tabView.getClass(), "currentTab");
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            return isMaxTab(field.get(tabView));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMaxTab(Object tab) {
        return getIntField(tab, "id", Integer.MIN_VALUE) == MAX_TAB_ID;
    }

    private static int findMaxTabIndex(ArrayList<?> tabs) {
        try {
            for (int i = 0; i < tabs.size(); i++) {
                if (getIntField(tabs.get(i), "id", Integer.MIN_VALUE) == MAX_TAB_ID) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void rememberRestorePoint(View filterTabs, Object originalDelegate, Class<?> delegateType, Object dialogsActivity) {
        try {
            activeFilterTabs = filterTabs;
            activeOriginalDelegate = originalDelegate;
            activeDelegateType = delegateType;
            activeDialogsActivity = dialogsActivity;
            int previousId = getIntField(filterTabs, "previousId", Integer.MIN_VALUE);
            int previousPos = getIntField(filterTabs, "previousPosition", -1);
            if (previousId == Integer.MIN_VALUE || previousId == MAX_TAB_ID || previousPos < 0) {
                Field tabsField = findField(filterTabs.getClass(), "tabs");
                if (tabsField != null) {
                    tabsField.setAccessible(true);
                    Object value = tabsField.get(filterTabs);
                    if (value instanceof ArrayList) {
                        ArrayList tabs = (ArrayList) value;
                        for (int i = 0; i < tabs.size(); i++) {
                            int id = getIntField(tabs.get(i), "id", Integer.MIN_VALUE);
                            if (id != Integer.MIN_VALUE && id != MAX_TAB_ID) {
                                previousId = id;
                                previousPos = i;
                                break;
                            }
                        }
                    }
                }
            }
            restoreTabId = previousId;
            restorePosition = previousPos;
        } catch (Throwable ignored) {
        }
    }

    private static void restoreSelectionIfCurrentMax() {
        View filterTabs = activeFilterTabs;
        Object originalDelegate = activeOriginalDelegate;
        Class<?> delegateType = activeDelegateType;
        activeFilterTabs = null;
        activeOriginalDelegate = null;
        activeDelegateType = null;
        try {
            if (filterTabs == null || getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != MAX_TAB_ID) {
                restoreTabId = Integer.MIN_VALUE;
                restorePosition = -1;
                return;
            }
            int pos = restorePosition;
            int id = restoreTabId;
            if (id == Integer.MIN_VALUE || id == MAX_TAB_ID || pos < 0) {
                Field tabsField = findField(filterTabs.getClass(), "tabs");
                if (tabsField != null) {
                    tabsField.setAccessible(true);
                    Object value = tabsField.get(filterTabs);
                    if (value instanceof ArrayList) {
                        ArrayList tabs = (ArrayList) value;
                        for (int i = 0; i < tabs.size(); i++) {
                            int tabId = getIntField(tabs.get(i), "id", Integer.MIN_VALUE);
                            if (tabId != Integer.MIN_VALUE && tabId != MAX_TAB_ID) {
                                id = tabId;
                                pos = i;
                                break;
                            }
                        }
                    }
                }
            }
            if (id != Integer.MIN_VALUE && id != MAX_TAB_ID && pos >= 0) {
                setIntField(filterTabs, "selectedTabId", id);
                setIntField(filterTabs, "currentPosition", pos);
                setIntField(filterTabs, "oldAnimatedTab", pos);
                setIntField(filterTabs, "previousId", id);
                setIntField(filterTabs, "previousPosition", pos);
                setBooleanField(filterTabs, "animatingIndicator", false);
                filterTabs.setEnabled(true);
                notifyTabsChanged(filterTabs);
                Object tab = getTabAt(filterTabs, pos);
                dispatchRestoreSelection(originalDelegate, delegateType, tab);
            }
        } catch (Throwable ignored) {
        } finally {
            restoreTabId = Integer.MIN_VALUE;
            restorePosition = -1;
        }
    }

    private static Object getTabAt(View filterTabs, int position) {
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return null;
            }
            tabsField.setAccessible(true);
            Object value = tabsField.get(filterTabs);
            if (!(value instanceof ArrayList)) {
                return null;
            }
            ArrayList tabs = (ArrayList) value;
            return position >= 0 && position < tabs.size() ? tabs.get(position) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dispatchRestoreSelection(Object originalDelegate, Class<?> delegateType, Object tab) {
        if (originalDelegate == null || delegateType == null || tab == null) {
            return;
        }
        try {
            Method onPageSelected = delegateType.getMethod("onPageSelected", tab.getClass(), boolean.class);
            onPageSelected.setAccessible(true);
            onPageSelected.invoke(originalDelegate, tab, false);
        } catch (Throwable ignored) {
        }
        try {
            Method onTabSelected = delegateType.getMethod("onTabSelected", tab.getClass(), boolean.class, boolean.class);
            onTabSelected.setAccessible(true);
            onTabSelected.invoke(originalDelegate, tab, false, true);
        } catch (Throwable ignored) {
        }
    }

    private static void hideFloatingButtons(Object dialogsActivity) {
        if (dialogsActivity == null) {
            return;
        }
        try {
            activeDialogsActivity = dialogsActivity;
            if (!floatingStateSaved) {
                previousFloatingHidden = getBooleanField(dialogsActivity, "floatingButtonHidden", false);
                previousFloatingForceVisible = getBooleanField(dialogsActivity, "floatingForceVisible", false);
                floatingStateSaved = true;
            }
            setBooleanField(dialogsActivity, "floatingForceVisible", false);
            setBooleanField(dialogsActivity, "floatingButtonHidden", true);
            invokeNoArgOrBoolean(dialogsActivity, "updateFloatingButtonVisibility", false);
            setFieldViewVisibility(dialogsActivity, "floatingButton3", View.GONE);
            setFieldViewVisibility(dialogsActivity, "floatingButtonStories", View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreFloatingButtons() {
        Object dialogsActivity = activeDialogsActivity;
        activeDialogsActivity = null;
        if (dialogsActivity == null || !floatingStateSaved) {
            floatingStateSaved = false;
            return;
        }
        try {
            setBooleanField(dialogsActivity, "floatingButtonHidden", previousFloatingHidden);
            setBooleanField(dialogsActivity, "floatingForceVisible", previousFloatingForceVisible);
            invokeNoArgOrBoolean(dialogsActivity, "updateFloatingButtonVisibility", false);
            if (!previousFloatingHidden) {
                setFieldViewVisibility(dialogsActivity, "floatingButton3", View.VISIBLE);
                setFieldViewVisibility(dialogsActivity, "floatingButtonStories", View.VISIBLE);
            }
        } catch (Throwable ignored) {
        } finally {
            floatingStateSaved = false;
            previousFloatingHidden = false;
            previousFloatingForceVisible = false;
        }
    }

    private static void invokeNoArgOrBoolean(Object target, String name, boolean value) {
        try {
            Method method = findMethod(target.getClass(), name, boolean.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, value);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method method = findMethod(target.getClass(), name);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setFieldViewVisibility(Object target, String fieldName, int visibility) {
        try {
            View view = getFieldView(target, fieldName);
            if (view != null) {
                view.setVisibility(visibility);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Byte.TYPE) {
            return (byte) 0;
        }
        if (type == Short.TYPE) {
            return (short) 0;
        }
        if (type == Character.TYPE) {
            return (char) 0;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Float.TYPE) {
            return 0f;
        }
        if (type == Double.TYPE) {
            return 0d;
        }
        return null;
    }

    public static void hide(Object dialogsActivity) {
        Object target = resolveDialogsActivity(dialogsActivity);
        View root = getFragmentView(target);
        hideOverlay(root);
    }

    private static void hideOverlay(View root) {
        if (root instanceof ViewGroup) {
            View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
            if (overlay != null) {
                destroyWebViews(overlay);
                ((ViewGroup) root).removeView(overlay);
            }
            restoreSelectionIfCurrentMax();
            restoreFloatingButtons();
        }
    }

    private static void destroyWebViews(View view) {
        try {
            if (view instanceof WebView) {
                WebView webView = (WebView) view;
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    destroyWebViews(group.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showOverlay(Activity activity, View root, View filterTabs, Object dialogsActivity) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) root;
        View old = parent.findViewWithTag(OVERLAY_TAG);
        if (old != null) {
            destroyWebViews(old);
            parent.removeView(old);
        }

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(resolveColor("org.telegram.ui.ActionBar.Theme", "key_windowBackgroundWhite", Color.WHITE));
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                hideOverlay(root);
                return true;
            }
            return false;
        });

        WebView webView = new WebView(activity);
        configureWebView(webView);
        overlay.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        overlay.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 2),
                Gravity.TOP
        ));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectTelegramSkin(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectTelegramSkin(view);
            }
        });

        int top = estimateTopMargin(parent, filterTabs);
        parent.addView(overlay, makeOverlayLayoutParams(parent, top));
        hideFloatingButtons(dialogsActivity);
        overlay.requestFocus();
        webView.loadUrl(URL);
    }

    private static ViewGroup.LayoutParams makeOverlayLayoutParams(ViewGroup parent, int topMargin) {
        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            lp.topMargin = topMargin;
            return lp;
        }
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            lp.topMargin = topMargin;
            return lp;
        }
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        lp.topMargin = topMargin;
        return lp;
    }

    private static void configureWebView(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private static void injectTelegramSkin(WebView webView) {
        String js = "(function(){"
                + "if(window.__etgMaxSkinBooting)return;window.__etgMaxSkinBooting=1;"
                + "var CSS='"
                + ":root{--etg-tg-bg:#fff;--etg-tg-surface:#fff;--etg-tg-panel:#f5f7fa;--etg-tg-text:#111;--etg-tg-muted:#707579;--etg-tg-line:#e7e7e7;--etg-tg-accent:#3390ec;--etg-tg-out:#effdde;--etg-tg-in:#fff;--etg-tg-chat-bg:#d8e6d1;--bubbles-background-bubble:#fff;--bubbles-background-bubble-gradient-step-1:#fff;--bubbles-background-bubble-gradient-step-2:#fff;--bubbles-background-bubble-gradient-step-3:#fff;--bubbles-text-body:#111;--bubbles-text-body-secondary:#707579;--bubbles-text-time:#707579;--bubbles-text-link:#2481cc;--bubbles-icon-read-status:#3390ec;}"
                + "[data-bubbles-variant=outgoing]{--bubbles-background-bubble:#effdde!important;--bubbles-background-bubble-gradient-step-1:#effdde!important;--bubbles-background-bubble-gradient-step-2:#effdde!important;--bubbles-background-bubble-gradient-step-3:#effdde!important;--bubbles-text-body:#111!important;--bubbles-text-time:#4fae4e!important;--bubbles-icon-read-status:#4fae4e!important;}"
                + "[data-bubbles-variant=incoming]{--bubbles-background-bubble:#fff!important;--bubbles-background-bubble-gradient-step-1:#fff!important;--bubbles-background-bubble-gradient-step-2:#fff!important;--bubbles-background-bubble-gradient-step-3:#fff!important;--bubbles-text-body:#111!important;--bubbles-text-time:#707579!important;}"
                + "html,body,#app{height:100%!important;width:100%!important;max-width:none!important;margin:0!important;background:var(--etg-tg-bg)!important;font-family:Roboto,Arial,sans-serif!important;color:var(--etg-tg-text)!important;letter-spacing:0!important;box-sizing:border-box!important;}"
                + "*,*:before,*:after{box-sizing:border-box!important;}"
                + "body{overflow:hidden!important;min-width:0!important;}"
                + "button,input,textarea,select{font-family:Roboto,Arial,sans-serif!important;letter-spacing:0!important;}"
                + "#app,main,[data-etg-max-root],.container{width:100%!important;max-width:none!important;min-width:0!important;background:var(--etg-tg-bg)!important;}"
                + "[data-etg-max-list],[role=list],nav,aside,[class*=chatList],[class*=ChatList],[class*=conversationList],[class*=DialogList],.tabs-container{background:var(--etg-tg-surface)!important;border-right:1px solid var(--etg-tg-line)!important;box-shadow:none!important;}"
                + "[data-etg-max-chat-row],[role=listitem],.chat-item,[class*=chatItem],[class*=ChatItem],[class*=dialog-item],[class*=conversation]{min-height:64px!important;border-radius:0!important;border-bottom:1px solid var(--etg-tg-line)!important;background:var(--etg-tg-surface)!important;color:var(--etg-tg-text)!important;transition:background .12s ease!important;}"
                + "[data-etg-max-chat-row]:active,.chat-item:active,[data-etg-max-chat-row][aria-selected=true],.chat-item.selected{background:#eef6ff!important;}"
                + ".chat-item{gap:12px!important;padding:8px 10px!important;}"
                + ".chat-item .name,[data-etg-max-title]{font-size:16px!important;font-weight:500!important;color:var(--etg-tg-text)!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;}"
                + ".chat-item .preview,[data-etg-max-preview],.preview{font-size:14px!important;color:var(--etg-tg-muted)!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;}"
                + ".chat-item .time,.time,[data-etg-max-time]{font-size:12px!important;color:var(--etg-tg-muted)!important;}"
                + ".badge,[data-etg-max-badge]{background:var(--etg-tg-accent)!important;color:#fff!important;border-radius:999px!important;min-width:20px!important;height:20px!important;padding:0 6px!important;font-size:12px!important;line-height:20px!important;text-align:center!important;}"
                + "[data-etg-max-messages],[class*=messages],[class*=Messages],[class*=chat-background],[class*=ChatWindow]{background:var(--etg-tg-chat-bg)!important;width:100%!important;max-width:none!important;min-width:0!important;}"
                + "[data-etg-max-bubble],.message-bubble,[data-bubbles-variant]{max-width:min(78%,480px)!important;padding:7px 10px!important;margin:2px 8px!important;box-shadow:0 1px 1px rgba(0,0,0,.12)!important;color:var(--bubbles-text-body,var(--etg-tg-text))!important;}"
                + "[data-etg-max-out=1],.message-row.is-me .message-bubble,[data-bubbles-variant=outgoing]{background:var(--etg-tg-out)!important;border-radius:12px 12px 4px 12px!important;margin-left:auto!important;}"
                + "[data-etg-max-out=0],.message-row:not(.is-me):not(.is-system) .message-bubble,[data-bubbles-variant=incoming]{background:var(--etg-tg-in)!important;border-radius:12px 12px 12px 4px!important;margin-right:auto!important;}"
                + ".message-row,[data-etg-max-message-row]{display:flex!important;align-items:flex-start!important;width:100%!important;max-width:100%!important;margin:2px 0!important;padding:0 8px!important;}"
                + ".message-row.is-me,[data-etg-max-message-row][data-etg-max-out-row=\"1\"]{justify-content:flex-end!important;align-items:flex-end!important;}"
                + ".message-row.is-system .message-bubble{background:rgba(255,255,255,.55)!important;border-radius:999px!important;box-shadow:none!important;color:var(--etg-tg-muted)!important;}"
                + "[data-etg-max-composer],form:has(textarea),form:has(input),[class*=composer],[class*=writebar],[class*=WriteBar]{background:var(--etg-tg-surface)!important;border-top:1px solid var(--etg-tg-line)!important;box-shadow:none!important;width:100%!important;max-width:none!important;min-width:0!important;}"
                + "[data-etg-max-composer] textarea,[data-etg-max-composer] input,[data-etg-max-composer] [contenteditable=true]{width:100%!important;max-width:100%!important;min-width:0!important;}"
                + ".tab,.tab-wrapper,[role=tab]{border-radius:999px!important;color:var(--etg-tg-muted)!important;}"
                + "[aria-selected=true].tab,[role=tab][aria-selected=true]{background:#e7f1ff!important;color:var(--etg-tg-accent)!important;}"
                + "';"
                + "function addStyle(){var s=document.getElementById('etg-max-telegram-skin');if(!s){s=document.createElement('style');s.id='etg-max-telegram-skin';document.head.appendChild(s);}if(s.textContent!==CSS)s.textContent=CSS;}"
                + "function hasAny(cls,arr){cls=(cls||'').toLowerCase();for(var i=0;i<arr.length;i++){if(cls.indexOf(arr[i])>-1)return true;}return false;}"
                + "function mark(){try{addStyle();document.documentElement.dataset.etgMaxSkin='telegram';if(document.body)document.body.dataset.etgMax='1';"
                + "var roots=document.querySelectorAll('#app,main,[class*=container]');for(var r=0;r<roots.length;r++){roots[r].setAttribute('data-etg-max-root','1');}"
                + "var bubbles=document.querySelectorAll('[data-bubbles-variant],.message-bubble,[class*=bubble],[class*=Bubble]');for(var i=0;i<bubbles.length;i++){var b=bubbles[i];var v=(b.getAttribute('data-bubbles-variant')||'').toLowerCase();var c=b.className||'';b.setAttribute('data-etg-max-bubble','1');var out=(v.indexOf('out')>-1||hasAny(c,['outgoing','is-me','my-message']));if(out)b.setAttribute('data-etg-max-out','1');else if(v.indexOf('in')>-1||hasAny(c,['incoming']))b.setAttribute('data-etg-max-out','0');var row=b.closest('[class*=message],[class*=Message],[role=listitem]')||b.parentElement;if(row){row.setAttribute('data-etg-max-message-row','1');if(out)row.setAttribute('data-etg-max-out-row','1');}}"
                + "var rows=document.querySelectorAll('[role=listitem],.chat-item,[class*=chatItem],[class*=ChatItem],[class*=dialog],[class*=Dialog],[class*=conversation],[class*=Conversation]');for(var j=0;j<rows.length;j++){var e=rows[j];var t=(e.textContent||'').trim();if(t.length>0&&t.length<600){e.setAttribute('data-etg-max-chat-row','1');}}"
                + "var lists=document.querySelectorAll('[role=list],aside,nav,[class*=list],[class*=List]');for(var k=0;k<lists.length;k++){lists[k].setAttribute('data-etg-max-list','1');}"
                + "var composers=document.querySelectorAll('form,footer,[class*=composer],[class*=writebar],[class*=WriteBar]');for(var m=0;m<composers.length;m++){var q=composers[m];if(q.querySelector('textarea,input,[contenteditable=true]'))q.setAttribute('data-etg-max-composer','1');}"
                + "var links=document.querySelectorAll('a,button,[role=button]');for(var n=0;n<links.length;n++){links[n].style.webkitTapHighlightColor='transparent';}"
                + "}catch(e){window.__etgMaxSkinError=String(e);}}"
                + "function installWsProbe(){if(window.__etgMaxWsProbe||!window.WebSocket)return;window.__etgMaxWsProbe=1;var NativeWS=window.WebSocket;function WrappedWS(url,protocols){var ws=protocols!==undefined?new NativeWS(url,protocols):new NativeWS(url);try{ws.addEventListener('message',function(ev){window.__etgMaxLastPacketAt=Date.now();setTimeout(mark,0);});}catch(e){}return ws;}WrappedWS.prototype=NativeWS.prototype;WrappedWS.CONNECTING=NativeWS.CONNECTING;WrappedWS.OPEN=NativeWS.OPEN;WrappedWS.CLOSING=NativeWS.CLOSING;WrappedWS.CLOSED=NativeWS.CLOSED;window.WebSocket=WrappedWS;}"
                + "mark();installWsProbe();if(!window.__etgMaxObserver){window.__etgMaxObserver=new MutationObserver(function(){clearTimeout(window.__etgMaxMarkTimer);window.__etgMaxMarkTimer=setTimeout(mark,60);});window.__etgMaxObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','data-bubbles-variant','aria-selected']});}"
                + "window.__etgMaxSkinBooting=0;"
                + "})();";
        if (Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    private static int estimateTopMargin(ViewGroup root, View filterTabs) {
        try {
            int[] rootPos = new int[2];
            int[] tabsPos = new int[2];
            root.getLocationOnScreen(rootPos);
            filterTabs.getLocationOnScreen(tabsPos);
            return Math.max((tabsPos[1] - rootPos[1]) + filterTabs.getHeight(), dp(root.getContext(), 48));
        } catch (Throwable ignored) {
            return dp(root.getContext(), 48);
        }
    }

    private static Activity getActivity(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("getParentActivity");
            Object value = m.invoke(fragment);
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View getFragmentView(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("getFragmentView");
            Object value = m.invoke(fragment);
            if (value instanceof View) {
                return (View) value;
            }
        } catch (Throwable ignored) {
        }
        return getFieldView(fragment, "fragmentView");
    }

    private static View getFieldView(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ViewGroup findTabsContainer(View filterTabs) {
        try {
            Field field = findField(filterTabs.getClass(), "tabsContainer");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(filterTabs);
                if (value instanceof ViewGroup) {
                    return (ViewGroup) value;
                }
            }
        } catch (Throwable ignored) {
        }
        if (filterTabs instanceof ViewGroup) {
            return findLinearLayout((ViewGroup) filterTabs);
        }
        return null;
    }

    private static ViewGroup findLinearLayout(ViewGroup root) {
        if (root instanceof LinearLayout) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup found = findLinearLayout((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... parameterTypes) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static int getIntField(Object target, String name, int fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return fallback;
            }
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean getBooleanField(Object target, String name, boolean fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return fallback;
            }
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) {
        if (target == null) {
            return;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                field.setBoolean(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void repairMaxSelection(View filterTabs, ArrayList<?> tabs) {
        try {
            Field selectedField = findField(filterTabs.getClass(), "selectedTabId");
            Field currentField = findField(filterTabs.getClass(), "currentPosition");
            if (selectedField == null || currentField == null) {
                return;
            }
            selectedField.setAccessible(true);
            currentField.setAccessible(true);
            if (selectedField.getInt(filterTabs) != MAX_TAB_ID) {
                return;
            }
            for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                int id = getIntField(tab, "id", Integer.MIN_VALUE);
                if (id != MAX_TAB_ID && id != Integer.MIN_VALUE) {
                    selectedField.setInt(filterTabs, id);
                    currentField.setInt(filterTabs, i);
                    return;
                }
            }
            selectedField.setInt(filterTabs, -1);
            currentField.setInt(filterTabs, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void rebuildTabMappings(View filterTabs, ArrayList<?> tabs) {
        try {
            SparseIntArray positionToId = getSparseField(filterTabs, "positionToId");
            SparseIntArray positionToStableId = getSparseField(filterTabs, "positionToStableId");
            SparseIntArray idToPosition = getSparseField(filterTabs, "idToPosition");
            if (positionToId != null) {
                positionToId.clear();
            }
            if (positionToStableId != null) {
                positionToStableId.clear();
            }
            if (idToPosition != null) {
                idToPosition.clear();
            }
            clearSparseField(filterTabs, "positionToWidth");
            clearSparseField(filterTabs, "prevPositionToWidth");

            int allWidth = 0;
            int padding = getFolderTabPadding();
            for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                int id = getIntField(tab, "id", Integer.MIN_VALUE);
                if (id == Integer.MIN_VALUE) {
                    continue;
                }
                if (positionToId != null) {
                    positionToId.put(i, id);
                }
                if (positionToStableId != null) {
                    positionToStableId.put(i, id);
                }
                if (idToPosition != null) {
                    idToPosition.put(id, i);
                }
                allWidth += getTabWidth(tab) + padding;
            }
            setIntField(filterTabs, "allTabsWidth", Math.max(allWidth, 0));
            int current = getIntField(filterTabs, "currentPosition", 0);
            if (current < 0 || current >= tabs.size()) {
                setIntField(filterTabs, "currentPosition", Math.max(tabs.size() - 1, 0));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int getTabWidth(Object tab) {
        try {
            Method method = tab.getClass().getMethod("getWidth", boolean.class);
            method.setAccessible(true);
            Object value = method.invoke(tab, true);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int getFolderTabPadding() {
        try {
            Class<?> icons = Class.forName("com.exteragram.messenger.utils.ui.FolderIcons");
            Method method = icons.getMethod("getPaddingTab");
            Object value = method.invoke(null);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void repairMappingsAfterMaxRemoval(View filterTabs, ArrayList<?> tabs, int removedIndex) {
        try {
            rebuildTabMappings(filterTabs, tabs);
            int current = getIntField(filterTabs, "currentPosition", 0);
            if (current >= removedIndex && current > 0) {
                setIntField(filterTabs, "currentPosition", Math.min(current - 1, Math.max(tabs.size() - 1, 0)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static SparseIntArray getSparseField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof SparseIntArray ? (SparseIntArray) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearSparseField(Object target, String name) {
        SparseIntArray value = getSparseField(target, name);
        if (value != null) {
            value.clear();
        }
    }

    private static void setIntField(Object target, String name, int value) {
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                field.setInt(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int estimateViewTop(ViewGroup root, View child) {
        try {
            int[] rootPos = new int[2];
            int[] childPos = new int[2];
            root.getLocationOnScreen(rootPos);
            child.getLocationOnScreen(childPos);
            return Math.max(childPos[1] - rootPos[1], 0);
        } catch (Throwable ignored) {
            return dp(root.getContext(), 48);
        }
    }

    private static int resolveColor(String className, String fieldName, int fallback) {
        try {
            Class<?> theme = Class.forName(className);
            Field key = theme.getField(fieldName);
            Method getColor = theme.getMethod("getColor", int.class);
            Object value = getColor.invoke(null, key.getInt(null));
            return value instanceof Integer ? (Integer) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dp(Context context, float value) {
        return (int) Math.ceil(value * context.getResources().getDisplayMetrics().density);
    }
}

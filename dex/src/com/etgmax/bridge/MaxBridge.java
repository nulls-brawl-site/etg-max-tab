package com.etgmax.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

public final class MaxBridge {
    private static final int MAX_TAB_ID = 0x4d415858;
    private static final String TAB_TAG = "etg_max_tab";
    private static final String OVERLAY_TAG = "etg_max_overlay";
    private static final String URL = "https://web.max.ru/";

    private MaxBridge() {
    }

    public static void install(Object dialogsActivity) {
        if (dialogsActivity == null) {
            return;
        }
        Activity activity = getActivity(dialogsActivity);
        View root = getFragmentView(dialogsActivity);
        View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
        if (activity == null || root == null || filterTabs == null) {
            return;
        }
        if (installFilterTabsView(dialogsActivity, activity, root, filterTabs)) {
            return;
        }
        ViewGroup tabsContainer = findTabsContainer(filterTabs);
        if (tabsContainer == null || tabsContainer.findViewWithTag(TAB_TAG) != null) {
            return;
        }
        TextView tab = createTab(activity);
        tab.setTag(TAB_TAG);
        tab.setOnClickListener(v -> showOverlay(activity, root, filterTabs));
        tabsContainer.addView(tab, new LinearLayout.LayoutParams(dp(activity, 64), ViewGroup.LayoutParams.MATCH_PARENT));
        tabsContainer.post(() -> {
            try {
                tabsContainer.requestLayout();
                if (tabsContainer.getParent() instanceof View) {
                    ((View) tabsContainer.getParent()).requestLayout();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean installFilterTabsView(Object dialogsActivity, Activity activity, View root, View filterTabs) {
        if (!filterTabs.getClass().getName().equals("org.telegram.ui.Components.FilterTabsView")) {
            return false;
        }
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return false;
            }
            tabsField.setAccessible(true);
            Object tabsValue = tabsField.get(filterTabs);
            if (!(tabsValue instanceof ArrayList)) {
                return false;
            }
            ArrayList<?> tabs = (ArrayList<?>) tabsValue;
            for (Object tab : tabs) {
                if (getIntField(tab, "id", Integer.MIN_VALUE) == MAX_TAB_ID) {
                    wrapFilterTabsDelegate(activity, root, filterTabs);
                    return true;
                }
            }

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
            addTab.invoke(filterTabs, MAX_TAB_ID, MAX_TAB_ID, "MAX", null, null, false, false, false);
            filterTabs.setTag(TAB_TAG);
            wrapFilterTabsDelegate(activity, root, filterTabs);
            notifyTabsChanged(filterTabs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void wrapFilterTabsDelegate(Activity activity, View root, View filterTabs) {
        try {
            Field delegateField = findField(filterTabs.getClass(), "delegate");
            if (delegateField == null) {
                return;
            }
            delegateField.setAccessible(true);
            Object original = delegateField.get(filterTabs);
            if (original == null || isOurProxy(original)) {
                return;
            }
            Class<?> delegateInterface = Class.forName("org.telegram.ui.Components.FilterTabsView$FilterTabsViewDelegate");
            InvocationHandler handler = new MaxTabDelegateHandler(original, activity, root, filterTabs);
            Object proxy = Proxy.newProxyInstance(delegateInterface.getClassLoader(), new Class[]{delegateInterface}, handler);
            delegateField.set(filterTabs, proxy);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isOurProxy(Object value) {
        try {
            return Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof MaxTabDelegateHandler;
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
        private final Activity activity;
        private final View root;
        private final View filterTabs;

        MaxTabDelegateHandler(Object original, Activity activity, View root, View filterTabs) {
            this.original = original;
            this.activity = activity;
            this.root = root;
            this.filterTabs = filterTabs;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("didSelectTab".equals(name) && args != null && args.length > 0 && isMaxTabView(args[0])) {
                showOverlay(activity, root, filterTabs);
                return true;
            }
            if (("onTabSelected".equals(name) || "onPageSelected".equals(name)) && args != null && args.length > 0 && isMaxTab(args[0])) {
                showOverlay(activity, root, filterTabs);
                return defaultValue(method.getReturnType());
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
        View root = getFragmentView(dialogsActivity);
        if (root instanceof ViewGroup) {
            View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
            if (overlay != null) {
                ((ViewGroup) root).removeView(overlay);
            }
        }
    }

    private static TextView createTab(Context context) {
        TextView tab = new TextView(context);
        tab.setText("MAX");
        tab.setGravity(Gravity.CENTER);
        tab.setSingleLine(true);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setTextSize(14);
        tab.setAllCaps(false);
        tab.setIncludeFontPadding(false);
        tab.setTextColor(resolveColor("org.telegram.ui.ActionBar.Theme", "key_windowBackgroundWhiteBlackText", 0xff222222));
        int hPad = dp(context, 12);
        tab.setPadding(hPad, 0, hPad, 0);
        tab.setMinWidth(dp(context, 56));
        if (Build.VERSION.SDK_INT >= 21) {
            TypedValue outValue = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                tab.setBackgroundResource(outValue.resourceId);
            }
            tab.setStateListAnimator(null);
        }
        return tab;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showOverlay(Activity activity, View root, View filterTabs) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) root;
        View old = parent.findViewWithTag(OVERLAY_TAG);
        if (old != null) {
            parent.removeView(old);
        }

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(resolveColor("org.telegram.ui.ActionBar.Theme", "key_windowBackgroundWhite", Color.WHITE));

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
                + "html,body,#app{height:100%!important;max-width:none!important;margin:0!important;background:var(--etg-tg-bg)!important;font-family:Roboto,Arial,sans-serif!important;color:var(--etg-tg-text)!important;letter-spacing:0!important;}"
                + "body{overflow:hidden!important;place-items:stretch!important;min-width:0!important;}"
                + "button,input,textarea,select{font-family:Roboto,Arial,sans-serif!important;letter-spacing:0!important;}"
                + "[data-etg-max-root],.container{background:var(--etg-tg-bg)!important;}"
                + "[data-etg-max-list],[role=list],nav,aside,[class*=chatList],[class*=ChatList],[class*=conversationList],[class*=DialogList],.tabs-container{background:var(--etg-tg-surface)!important;border-right:1px solid var(--etg-tg-line)!important;box-shadow:none!important;}"
                + "[data-etg-max-chat-row],[role=listitem],.chat-item,[class*=chatItem],[class*=ChatItem],[class*=dialog-item],[class*=conversation]{min-height:64px!important;border-radius:0!important;border-bottom:1px solid var(--etg-tg-line)!important;background:var(--etg-tg-surface)!important;color:var(--etg-tg-text)!important;transition:background .12s ease!important;}"
                + "[data-etg-max-chat-row]:active,.chat-item:active,[data-etg-max-chat-row][aria-selected=true],.chat-item.selected{background:#eef6ff!important;}"
                + ".chat-item{gap:12px!important;padding:8px 10px!important;}"
                + ".chat-item .name,[data-etg-max-title]{font-size:16px!important;font-weight:500!important;color:var(--etg-tg-text)!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;}"
                + ".chat-item .preview,[data-etg-max-preview],.preview{font-size:14px!important;color:var(--etg-tg-muted)!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;}"
                + ".chat-item .time,.time,[data-etg-max-time]{font-size:12px!important;color:var(--etg-tg-muted)!important;}"
                + ".badge,[data-etg-max-badge]{background:var(--etg-tg-accent)!important;color:#fff!important;border-radius:999px!important;min-width:20px!important;height:20px!important;padding:0 6px!important;font-size:12px!important;line-height:20px!important;text-align:center!important;}"
                + "[data-etg-max-messages],[class*=messages],[class*=Messages],[class*=chat-background],[class*=ChatWindow]{background:var(--etg-tg-chat-bg)!important;}"
                + "[data-etg-max-bubble],.message-bubble,[data-bubbles-variant]{max-width:min(78%,480px)!important;padding:7px 10px!important;margin:2px 8px!important;box-shadow:0 1px 1px rgba(0,0,0,.12)!important;color:var(--bubbles-text-body,var(--etg-tg-text))!important;}"
                + "[data-etg-max-out=1],.message-row.is-me .message-bubble,[data-bubbles-variant=outgoing]{background:var(--etg-tg-out)!important;border-radius:12px 12px 4px 12px!important;margin-left:auto!important;}"
                + "[data-etg-max-out=0],.message-row:not(.is-me):not(.is-system) .message-bubble,[data-bubbles-variant=incoming]{background:var(--etg-tg-in)!important;border-radius:12px 12px 12px 4px!important;margin-right:auto!important;}"
                + ".message-row{display:flex!important;align-items:flex-start!important;margin:2px 0!important;}"
                + ".message-row.is-me{align-items:flex-end!important;}"
                + ".message-row.is-system .message-bubble{background:rgba(255,255,255,.55)!important;border-radius:999px!important;box-shadow:none!important;color:var(--etg-tg-muted)!important;}"
                + "[data-etg-max-composer],form:has(textarea),form:has(input),[class*=composer],[class*=writebar],[class*=WriteBar]{background:var(--etg-tg-surface)!important;border-top:1px solid var(--etg-tg-line)!important;box-shadow:none!important;}"
                + ".tab,.tab-wrapper,[role=tab]{border-radius:999px!important;color:var(--etg-tg-muted)!important;}"
                + "[aria-selected=true].tab,[role=tab][aria-selected=true]{background:#e7f1ff!important;color:var(--etg-tg-accent)!important;}"
                + "';"
                + "function addStyle(){var s=document.getElementById('etg-max-telegram-skin');if(!s){s=document.createElement('style');s.id='etg-max-telegram-skin';document.head.appendChild(s);}if(s.textContent!==CSS)s.textContent=CSS;}"
                + "function hasAny(cls,arr){cls=(cls||'').toLowerCase();for(var i=0;i<arr.length;i++){if(cls.indexOf(arr[i])>-1)return true;}return false;}"
                + "function mark(){try{addStyle();document.documentElement.dataset.etgMaxSkin='telegram';if(document.body)document.body.dataset.etgMax='1';"
                + "var roots=document.querySelectorAll('#app,main,[class*=container]');for(var r=0;r<roots.length;r++){roots[r].setAttribute('data-etg-max-root','1');}"
                + "var bubbles=document.querySelectorAll('[data-bubbles-variant],.message-bubble,[class*=bubble],[class*=Bubble]');for(var i=0;i<bubbles.length;i++){var b=bubbles[i];var v=(b.getAttribute('data-bubbles-variant')||'').toLowerCase();var c=b.className||'';b.setAttribute('data-etg-max-bubble','1');if(v.indexOf('out')>-1||hasAny(c,['outgoing','is-me','my-message']))b.setAttribute('data-etg-max-out','1');else if(v.indexOf('in')>-1||hasAny(c,['incoming']))b.setAttribute('data-etg-max-out','0');}"
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

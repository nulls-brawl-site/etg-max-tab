package com.etgmax.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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
import java.lang.reflect.Method;

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
        ViewGroup tabsContainer = findTabsContainer(filterTabs);
        if (tabsContainer == null || tabsContainer.findViewWithTag(TAB_TAG) != null) {
            return;
        }
        TextView tab = createTab(activity, filterTabs);
        tab.setTag(TAB_TAG);
        tab.setOnClickListener(v -> showOverlay(dialogsActivity, activity, root, filterTabs));
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

    public static void hide(Object dialogsActivity) {
        View root = getFragmentView(dialogsActivity);
        if (root instanceof ViewGroup) {
            View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
            if (overlay != null) {
                ((ViewGroup) root).removeView(overlay);
            }
        }
    }

    private static TextView createTab(Context context, View filterTabs) {
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
            tab.setBackground(filterTabs.getBackground());
            tab.setStateListAnimator(null);
        }
        return tab;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void showOverlay(Object dialogsActivity, Activity activity, View root, View filterTabs) {
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

        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        overlay.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 2),
                Gravity.TOP
        ));

        WebView webView = new WebView(activity);
        configureWebView(webView);
        overlay.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
            public void onPageFinished(WebView view, String url) {
                injectTelegramSkin(view);
            }
        });

        int top = estimateTopMargin(parent, filterTabs);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        lp.topMargin = top;
        parent.addView(overlay, lp);
        webView.loadUrl(URL);
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
                + "const id='etg-max-telegram-skin';"
                + "if(!document.getElementById(id)){"
                + "const s=document.createElement('style');s.id=id;s.textContent=`"
                + ":root{--tg-bg:#fff;--tg-text:#111;--tg-muted:#707579;--tg-line:#e7e7e7;--tg-accent:#3390ec;--tg-out:#effdde;--tg-in:#fff;}"
                + "html,body,#app{height:100%!important;max-width:none!important;margin:0!important;background:var(--tg-bg)!important;font-family:Roboto,Arial,sans-serif!important;}"
                + "body{overflow:hidden!important;color:var(--tg-text)!important;}"
                + "button,input,textarea{font-family:Roboto,Arial,sans-serif!important;}"
                + "[role=list],nav,aside,[class*=chatList],[class*=conversationList],[class*=dialogs],[class*=DialogList]{background:var(--tg-bg)!important;border-right:1px solid var(--tg-line)!important;}"
                + "[role=listitem],[class*=chatItem],[class*=dialog],[class*=conversation]{min-height:64px!important;border-radius:0!important;border-bottom:1px solid var(--tg-line)!important;background:var(--tg-bg)!important;}"
                + "[class*=message][class*=out], [data-out=true]{background:var(--tg-out)!important;border-radius:12px 12px 4px 12px!important;}"
                + "[class*=message][class*=in], [data-out=false]{background:var(--tg-in)!important;border-radius:12px 12px 12px 4px!important;box-shadow:0 1px 1px rgba(0,0,0,.08)!important;}"
                + "[class*=composer],form:has(textarea),form:has(input){background:var(--tg-bg)!important;border-top:1px solid var(--tg-line)!important;}"
                + "`;document.head.appendChild(s);}"
                + "function mark(){document.body.dataset.etgMax='1';"
                + "document.querySelectorAll('a,button,[role=button]').forEach(e=>{if(!e.dataset.etgFast){e.dataset.etgFast='1';e.style.webkitTapHighlightColor='transparent';}});}"
                + "mark();new MutationObserver(mark).observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
        if (Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }

    private static int estimateTopMargin(ViewGroup root, View filterTabs) {
        try {
            int rootTop = root.getTop();
            int bottom = filterTabs.getBottom();
            if (filterTabs.getParent() instanceof View) {
                View parent = (View) filterTabs.getParent();
                bottom += parent.getTop();
            }
            return Math.max(bottom - rootTop, dp(root.getContext(), 48));
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

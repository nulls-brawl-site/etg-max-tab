package com.etgmax.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class MaxBridge {
    private static final int MAX_TAB_ID = 0x4d415858;
    private static final String TAB_TAG = "etg_max_tab";
    private static final String BUTTON_TAG = "etg_max_button";
    private static final String OVERLAY_TAG = "etg_max_overlay";
    private static final String URL = "https://web.max.ru/";
    private static FrameLayout cachedOverlay;
    private static WebView cachedWebView;
    private static Activity cachedOverlayActivity;
    private static int overlayGuardGeneration;
    private static View activeFilterTabs;
    private static Object activeOriginalDelegate;
    private static Class<?> activeDelegateType;
    private static Object activeDialogsActivity;
    private static int restoreTabId = Integer.MIN_VALUE;
    private static int restorePosition = -1;
    private static View systemBarsDecor;
    private static android.view.Window systemBarsWindow;
    private static int previousSystemUiVisibility;
    private static int previousStatusBarColor;
    private static int previousNavigationBarColor;
    private static boolean previousBarColorsSaved;
    private static boolean systemBarsHidden;
    private static final ArrayList<HiddenViewState> hiddenTelegramChromeViews = new ArrayList<>();
    private static Object hiddenChromeDialogsActivity;
    private static boolean floatingButtonHiddenCaptured;
    private static boolean previousFloatingButtonHidden;
    private static Object hiddenMainTabsController;
    private static boolean mainTabsControllerHidden;
    private static Object hiddenStoriesDialogsActivity;
    private static boolean storiesStateCaptured;
    private static boolean previousHasStories;
    private static boolean previousDialogStoriesCellVisible;
    private static boolean previousHasOnlySelfStories;
    private static boolean previousAnimateToHasStories;
    private static float previousProgressToDialogStoriesCell;
    private static float previousProgressToShowStories;
    private static int previousDialogStoriesCurrentState;
    private static boolean previousDialogStoriesCollapsed;
    private static boolean previousDialogStoriesStateCaptured;
    private static boolean previousDialogStoriesAllowGlobalUpdates;
    private static boolean previousDialogStoriesAllowGlobalUpdatesCaptured;
    private static int tabTransitionGeneration;
    private static final int IMMERSIVE_SYSTEM_UI_FLAGS = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LOW_PROFILE;

    private MaxBridge() {
    }

    public static void install(Object dialogsActivity) {
        installWithStatus(dialogsActivity);
    }

    public static void beforeUpdateFilterTabs(Object dialogsActivity) {
        Object target = resolveDialogsActivity(dialogsActivity);
        View filterTabs = getFieldView(target, "filterTabsView");
        if (filterTabs == null) {
            return;
        }
        if (getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) == MAX_TAB_ID) {
            int restoreId = chooseRestoreTabId(filterTabs, target);
            setFilterTabsSelectionFields(filterTabs, restoreId);
            normalizeDialogsSelection(target, filterTabs, restoreId);
        }
    }

    public static String openMaxUrl(Object source, String rawUrl) {
        String url = normalizeMaxUrl(rawUrl);
        if (url == null) {
            return "link: ignored url=" + rawUrl;
        }
        Object target = findExistingDialogsActivity(source);
        if (target == null) {
            return "link: dialogsActivity not found url=" + url;
        }
        Object current = getLaunchFragment(false);
        boolean closed = closeFragmentsAbove(getParentLayout(current), target);
        if (!closed) {
            closed = closeFragmentsAbove(getParentLayout(target), target);
        }
        if (!closed) {
            Object launch = getLaunchActivity();
            closed = closeFragmentsAbove(invokeNoArg(launch, "getActionBarLayout"), target);
            if (!closed) {
                closed = closeFragmentsAbove(getFieldObject(launch, "actionBarLayout"), target);
            }
            if (!closed) {
                closeFragmentsAbove(getFieldObject(launch, "rightActionBarLayout"), target);
            }
        }
        String result = openOnDialogsActivity(target, url);
        final boolean openedNow = result != null && result.startsWith("link: opened");
        View root = getFragmentView(target);
        if (root != null) {
            root.postDelayed(() -> {
                if (openedNow) {
                    refreshOpenOnDialogsActivity(target, url);
                } else {
                    openOnDialogsActivity(target, url);
                }
            }, 120);
            root.postDelayed(() -> refreshOpenOnDialogsActivity(target, url), 420);
            root.postDelayed(() -> refreshOpenOnDialogsActivity(target, url), 900);
        }
        return result;
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

    private static Object findExistingDialogsActivity(Object source) {
        Object target = resolveDialogsActivity(source);
        if (target != null) {
            return target;
        }
        Object current = getLaunchFragment(true);
        target = resolveDialogsActivity(current);
        if (target != null) {
            return target;
        }
        Object layout = getParentLayout(current);
        target = findDialogsInLayout(layout);
        if (target != null) {
            return target;
        }
        Object launch = getLaunchActivity();
        target = findDialogsInLayout(invokeNoArg(launch, "getActionBarLayout"));
        if (target != null) {
            return target;
        }
        target = findDialogsInLayout(getFieldObject(launch, "actionBarLayout"));
        if (target != null) {
            return target;
        }
        target = findDialogsInLayout(getFieldObject(launch, "rightActionBarLayout"));
        if (target != null) {
            return target;
        }
        target = findDialogsInLayout(getFieldObject(launch, "layersActionBarLayout"));
        if (target != null) {
            return target;
        }
        return findDialogsInStaticMainStack();
    }

    private static Object findDialogsInLayout(Object layout) {
        if (layout == null) {
            return null;
        }
        try {
            Method method = layout.getClass().getMethod("getFragmentStack");
            method.setAccessible(true);
            Object value = method.invoke(layout);
            if (!(value instanceof List)) {
                return null;
            }
            List stack = (List) value;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object target = resolveDialogsActivity(stack.get(i));
                if (target != null) {
                    return target;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findDialogsInStaticMainStack() {
        try {
            Class<?> launchClass = Class.forName("org.telegram.ui.LaunchActivity");
            Field field = findField(launchClass, "mainFragmentsStack");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof List)) {
                return null;
            }
            List stack = (List) value;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object target = resolveDialogsActivity(stack.get(i));
                if (target != null) {
                    return target;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean closeFragmentsAbove(Object layout, Object target) {
        try {
            Method stackMethod = layout.getClass().getMethod("getFragmentStack");
            stackMethod.setAccessible(true);
            Object value = stackMethod.invoke(layout);
            if (!(value instanceof List)) {
                return false;
            }
            List stack = (List) value;
            int targetIndex = -1;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object candidate = resolveDialogsActivity(stack.get(i));
                if (candidate == target) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex < 0) {
                return false;
            }
            Method close = layout.getClass().getMethod("closeLastFragment", boolean.class);
            close.setAccessible(true);
            for (int i = stack.size() - 1; i > targetIndex && stack.size() > targetIndex + 1; i--) {
                close.invoke(layout, false);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String openOnDialogsActivity(Object dialogsActivity, String url) {
        return openOnDialogsActivity(dialogsActivity, url, true);
    }

    private static String refreshOpenOnDialogsActivity(Object dialogsActivity, String url) {
        View root = getFragmentView(dialogsActivity);
        View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
        if (!(root instanceof ViewGroup) || filterTabs == null) {
            return "link: stale";
        }
        if (((ViewGroup) root).findViewWithTag(OVERLAY_TAG) == null) {
            return "link: stale";
        }
        if (getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != MAX_TAB_ID) {
            return "link: stale";
        }
        return openOnDialogsActivity(dialogsActivity, url, false);
    }

    private static String openOnDialogsActivity(Object dialogsActivity, String url, boolean captureRestore) {
        if (dialogsActivity == null) {
            return "link: dialogsActivity=null";
        }
        Activity activity = getActivity(dialogsActivity);
        View root = getFragmentView(dialogsActivity);
        View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
        if (activity == null || root == null || filterTabs == null) {
            return "link: missing activity=" + (activity != null)
                    + " root=" + (root != null)
                    + " filterTabsView=" + (filterTabs != null);
        }
        cleanupOldStandaloneButton(root, filterTabs);
        boolean overlayActive = root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null;
        installFilterTab(dialogsActivity, activity, root, filterTabs);
        if (captureRestore || !overlayActive) {
            captureCurrentRestorePoint(filterTabs, dialogsActivity);
            normalizeDialogsSelection(dialogsActivity, filterTabs, restoreTabId);
        }
        int maxIndex = findTabPositionById(filterTabs, MAX_TAB_ID);
        if (maxIndex < 0) {
            return "link: max tab missing";
        }
        if (!(captureRestore
                && getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != MAX_TAB_ID
                && selectFilterTab(filterTabs, MAX_TAB_ID))) {
            setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
            setIntField(filterTabs, "currentPosition", maxIndex);
            setIntField(filterTabs, "oldAnimatedTab", maxIndex);
            setBooleanField(filterTabs, "animatingIndicator", false);
            hardenFilterTabsDrawState(filterTabs, activity);
            View listView = getFieldView(filterTabs, "listView");
            if (listView != null) {
                listView.invalidate();
            }
            filterTabs.invalidate();
        }
        hardenMaxTabVisualState(root, filterTabs, activity);
        showOverlay(activity, root, filterTabs, dialogsActivity, url);
        return "link: opened " + url;
    }

    private static void captureCurrentRestorePoint(View filterTabs, Object dialogsActivity) {
        try {
            activeFilterTabs = filterTabs;
            activeDialogsActivity = dialogsActivity;
            activeOriginalDelegate = getOriginalDelegate(filterTabs);
            Field delegateField = findField(filterTabs.getClass(), "delegate");
            activeDelegateType = delegateField != null ? delegateField.getType() : null;
            int selectedId = getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE);
            int selectedPosition = getIntField(filterTabs, "currentPosition", -1);
            if (selectedId == Integer.MIN_VALUE || selectedId == MAX_TAB_ID || selectedPosition < 0) {
                selectedId = getDialogsSelectedType(dialogsActivity, 0);
                selectedPosition = findTabPositionById(filterTabs, selectedId);
            }
            if (selectedId == Integer.MIN_VALUE || selectedId == MAX_TAB_ID || selectedPosition < 0) {
                selectedPosition = firstRealTabPosition(filterTabs);
                Object tab = getTabAt(filterTabs, selectedPosition);
                selectedId = getIntField(tab, "id", Integer.MIN_VALUE);
            }
            restoreTabId = selectedId;
            restorePosition = selectedPosition;
        } catch (Throwable ignored) {
        }
    }

    private static int firstRealTabPosition(View filterTabs) {
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return -1;
            }
            tabsField.setAccessible(true);
            Object value = tabsField.get(filterTabs);
            if (!(value instanceof ArrayList)) {
                return -1;
            }
            ArrayList tabs = (ArrayList) value;
            for (int i = 0; i < tabs.size(); i++) {
                int id = getIntField(tabs.get(i), "id", Integer.MIN_VALUE);
                if (id != Integer.MIN_VALUE && id != MAX_TAB_ID) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static Object getOriginalDelegate(View filterTabs) {
        try {
            Field delegateField = findField(filterTabs.getClass(), "delegate");
            if (delegateField == null) {
                return null;
            }
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(filterTabs);
            if (delegate == null) {
                return null;
            }
            if (Proxy.isProxyClass(delegate.getClass())) {
                InvocationHandler handler = Proxy.getInvocationHandler(delegate);
                Field originalField = findField(handler.getClass(), "original");
                if (originalField != null) {
                    originalField.setAccessible(true);
                    Object original = originalField.get(handler);
                    if (original != null) {
                        return original;
                    }
                }
            }
            return delegate;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getLaunchFragment(boolean includeMainTabs) {
        try {
            Class<?> launchClass = Class.forName("org.telegram.ui.LaunchActivity");
            Method method = launchClass.getMethod(includeMainTabs ? "getLastFragmentIncludeMainTabs" : "getLastFragment");
            method.setAccessible(true);
            return method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getLaunchActivity() {
        try {
            Class<?> launchClass = Class.forName("org.telegram.ui.LaunchActivity");
            Field field = findField(launchClass, "instance");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getParentLayout(Object fragment) {
        try {
            if (fragment == null) {
                return null;
            }
            Method method = fragment.getClass().getMethod("getParentLayout");
            method.setAccessible(true);
            return method.invoke(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            if (target == null) {
                return null;
            }
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeMaxUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String value = rawUrl.trim();
        if (value.length() == 0) {
            return null;
        }
        String lower = value.toLowerCase();
        if (lower.startsWith("max://") || lower.startsWith("oneme://")) {
            int scheme = value.indexOf("://");
            String rest = scheme >= 0 ? value.substring(scheme + 3) : "";
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return URL + rest;
        }
        if (lower.startsWith("max.ru/") || "max.ru".equals(lower)) {
            return "https://web." + value;
        }
        if (lower.startsWith("web.max.ru/") || "web.max.ru".equals(lower)) {
            return "https://" + value;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String hostLower = host.toLowerCase();
            if ("web.max.ru".equals(hostLower) || hostLower.endsWith(".web.max.ru")) {
                return value;
            }
            if ("max.ru".equals(hostLower) || hostLower.endsWith(".max.ru")) {
                Uri.Builder builder = uri.buildUpon();
                builder.scheme("https");
                builder.authority("web.max.ru");
                return builder.build().toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isMaxUrl(String rawUrl) {
        return normalizeMaxUrl(rawUrl) != null;
    }

    private static String installFilterTab(Object dialogsActivity, Activity activity, View root, View filterTabs) {
        if (isMaxTabInstalledAtEnd(filterTabs) && isWrappedMaxDelegate(filterTabs)) {
            hardenMaxTabVisualState(root, filterTabs, activity);
            boolean overlayActive = root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null;
            if (overlayActive) {
                int maxIndex = findTabPositionById(filterTabs, MAX_TAB_ID);
                if (maxIndex >= 0) {
                    setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
                    setIntField(filterTabs, "currentPosition", maxIndex);
                    setIntField(filterTabs, "oldAnimatedTab", maxIndex);
                    setBooleanField(filterTabs, "animatingIndicator", false);
                }
                activeFilterTabs = filterTabs;
                activeDialogsActivity = dialogsActivity;
            }
            return "tab: already installed tabs=" + getTabsCount(filterTabs)
                    + " class=" + filterTabs.getClass().getName();
        }
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
            hardenMaxTabVisualState(root, filterTabs, activity);
            boolean wrapped = wrapFilterTabsDelegate(dialogsActivity, activity, root, filterTabs);
            boolean overlayActive = root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null;
            if (overlayActive) {
                int maxIndex = findTabPositionById(filterTabs, MAX_TAB_ID);
                if (maxIndex >= 0) {
                    setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
                    setIntField(filterTabs, "currentPosition", maxIndex);
                    setIntField(filterTabs, "oldAnimatedTab", maxIndex);
                }
                activeFilterTabs = filterTabs;
                activeDialogsActivity = dialogsActivity;
            }
            notifyTabsChanged(filterTabs);
            hardenMaxTabVisualState(root, filterTabs, activity);
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

    private static boolean isWrappedMaxDelegate(View filterTabs) {
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
            return handler != null && handler.getClass().getName().indexOf("MaxBridge$MaxTabDelegateHandler") >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMaxTabInstalledAtEnd(View filterTabs) {
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return false;
            }
            tabsField.setAccessible(true);
            Object value = tabsField.get(filterTabs);
            if (!(value instanceof ArrayList)) {
                return false;
            }
            ArrayList tabs = (ArrayList) value;
            if (tabs.isEmpty()) {
                return false;
            }
            int maxCount = 0;
            for (int i = 0; i < tabs.size(); i++) {
                if (getIntField(tabs.get(i), "id", Integer.MIN_VALUE) == MAX_TAB_ID) {
                    maxCount++;
                    if (i != tabs.size() - 1) {
                        return false;
                    }
                }
            }
            return maxCount == 1
                    && findTabPositionById(filterTabs, MAX_TAB_ID) == tabs.size() - 1
                    && getIntField(tabs.get(tabs.size() - 1), "id", Integer.MIN_VALUE) == MAX_TAB_ID;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int getTabsCount(View filterTabs) {
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return -1;
            }
            tabsField.setAccessible(true);
            Object value = tabsField.get(filterTabs);
            return value instanceof ArrayList ? ((ArrayList) value).size() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
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

    private static void hardenMaxTabVisualState(View root, View filterTabs, Activity activity) {
        hardenFilterTabsDrawState(filterTabs, activity);
        if (root != null) {
            root.post(() -> hardenFilterTabsDrawState(filterTabs, activity));
            root.postDelayed(() -> hardenFilterTabsDrawState(filterTabs, activity), 80);
            root.postDelayed(() -> hardenFilterTabsDrawState(filterTabs, activity), 240);
        }
    }

    private static void hardenFilterTabsDrawState(View filterTabs, Activity activity) {
        ensureFilterTabsLockDrawable(filterTabs, activity);
        sanitizeMaxTabState(filterTabs);
    }

    private static void ensureFilterTabsLockDrawable(View filterTabs, Activity activity) {
        if (filterTabs == null) {
            return;
        }
        try {
            Object current = getFieldObject(filterTabs, "lockDrawable");
            if (current instanceof Drawable) {
                return;
            }
            Drawable drawable = null;
            Context context = activity != null ? activity : filterTabs.getContext();
            if (context != null) {
                try {
                    Class<?> drawables = Class.forName("org.telegram.messenger.R$drawable");
                    Field field = findField(drawables, "other_lockedfolders");
                    if (field != null) {
                        field.setAccessible(true);
                        int resId = field.getInt(null);
                        if (resId != 0) {
                            drawable = Build.VERSION.SDK_INT >= 21
                                    ? context.getDrawable(resId)
                                    : context.getResources().getDrawable(resId);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (drawable == null) {
                drawable = new ColorDrawable(Color.TRANSPARENT);
            }
            setObjectField(filterTabs, "lockDrawable", drawable);
            setIntField(filterTabs, "lockDrawableColor", Integer.MIN_VALUE);
        } catch (Throwable ignored) {
        }
    }

    private static void sanitizeMaxTabState(View filterTabs) {
        if (filterTabs == null) {
            return;
        }
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField != null) {
                tabsField.setAccessible(true);
                Object value = tabsField.get(filterTabs);
                if (value instanceof ArrayList) {
                    ArrayList tabs = (ArrayList) value;
                    for (int i = 0; i < tabs.size(); i++) {
                        Object tab = tabs.get(i);
                        if (isMaxTab(tab)) {
                            setBooleanField(tab, "isLocked", false);
                        }
                    }
                }
            }
            View listView = getFieldView(filterTabs, "listView");
            if (listView instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) listView;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    ensureDrawableField(child, "icon");
                    ensureDrawableField(child, "iconAnimateInDrawable");
                    ensureDrawableField(child, "iconAnimateOutDrawable");
                    Object tab = getFieldObject(child, "currentTab");
                    if (isMaxTab(tab)) {
                        setBooleanField(tab, "isLocked", false);
                        setFloatField(child, "progressToLocked", 0f);
                        setFloatField(child, "locIconXOffset", 0f);
                    }
                    child.invalidate();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureDrawableField(Object target, String name) {
        try {
            Object value = getFieldObject(target, name);
            if (!(value instanceof Drawable)) {
                setObjectField(target, name, new ColorDrawable(Color.TRANSPARENT));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showMaxOverlayFromTab(Activity activity, View root, View filterTabs, Object dialogsActivity) {
        cancelRegularSelectionRepairs();
        setMaxTabSelectionFields(filterTabs);
        hardenMaxTabVisualState(root, filterTabs, activity);
        showOverlay(activity, root, filterTabs, dialogsActivity);
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
                    showMaxOverlayFromTab(activity, root, filterTabs, dialogsActivity);
                    return true;
                }
                return method.invoke(original, args);
            }
            if ("onPageSelected".equals(name) && args != null && args.length > 0) {
                if (isMaxTab(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showMaxOverlayFromTab(activity, root, filterTabs, dialogsActivity);
                    return defaultValue(method.getReturnType());
                }
                int transitionGeneration = beginTabTransition();
                int targetId = getRegularTargetTabId(filterTabs, args);
                boolean leavingMax = isLeavingMax(root, filterTabs);
                if (leavingMax) {
                    prepareRegularSelectionFromMax(dialogsActivity, filterTabs, targetId);
                }
                Object result = method.invoke(original, args);
                closeOverlayAfterRegularSelection(root);
                if (leavingMax) {
                    scheduleRegularSelectionRepair(dialogsActivity, root, targetId, transitionGeneration);
                }
                return result;
            }
            if ("onTabSelected".equals(name) && args != null && args.length > 0) {
                if (isMaxTab(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showMaxOverlayFromTab(activity, root, filterTabs, dialogsActivity);
                    return defaultValue(method.getReturnType());
                }
                return method.invoke(original, args);
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

    private static Object extractTab(Object arg) {
        if (arg == null) {
            return null;
        }
        try {
            Field field = findField(arg.getClass(), "currentTab");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(arg);
                if (value != null) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return arg;
    }

    private static int getRegularTargetTabId(View filterTabs, Object[] args) {
        try {
            if (args == null || args.length == 0) {
                return Integer.MIN_VALUE;
            }
            Object tab = extractTab(args[0]);
            int targetId = getIntField(tab, "id", Integer.MIN_VALUE);
            if (targetId == Integer.MIN_VALUE || targetId == MAX_TAB_ID) {
                return Integer.MIN_VALUE;
            }
            return findTabPositionById(filterTabs, targetId) >= 0 ? targetId : Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static void rememberRestorePoint(View filterTabs, Object originalDelegate, Class<?> delegateType, Object dialogsActivity) {
        try {
            activeFilterTabs = filterTabs;
            activeOriginalDelegate = originalDelegate;
            activeDelegateType = delegateType;
            activeDialogsActivity = dialogsActivity;
            int selectedId = getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE);
            int selectedPos = getIntField(filterTabs, "currentPosition", -1);
            if (isRegularTabId(filterTabs, selectedId) && selectedPos >= 0) {
                restoreTabId = selectedId;
                restorePosition = selectedPos;
                return;
            }
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

    private static boolean isLeavingMax(View root, View filterTabs) {
        try {
            if (root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null) {
                return true;
            }
            if (activeFilterTabs == filterTabs) {
                return true;
            }
            return getIntField(filterTabs, "previousId", Integer.MIN_VALUE) == MAX_TAB_ID;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void prepareRegularSelectionFromMax(Object dialogsActivity, View filterTabs, int targetId) {
        if (!isRegularTabId(filterTabs, targetId)) {
            return;
        }
        int previousId = restoreTabId;
        if (!isRegularTabId(filterTabs, previousId)) {
            previousId = getDialogsSelectedType(dialogsActivity, 0);
        }
        if (!isRegularTabId(filterTabs, previousId)) {
            previousId = getDialogsSelectedType(dialogsActivity, 1);
        }
        if (!isRegularTabId(filterTabs, previousId)) {
            previousId = targetId;
        }
        int previousPos = findTabPositionById(filterTabs, previousId);
        if (previousPos >= 0) {
            setIntField(filterTabs, "previousId", previousId);
            setIntField(filterTabs, "previousPosition", previousPos);
        }
    }

    private static void restoreSelectionIfCurrentMax() {
        restoreSelectionState(true, true);
    }

    private static int restoreSelectionFieldsOnly() {
        return restoreSelectionState(false, false);
    }

    private static int restoreSelectionState(boolean dispatchDelegate, boolean reloadDialogs) {
        View filterTabs = activeFilterTabs;
        Object dialogsActivity = activeDialogsActivity;
        activeFilterTabs = null;
        activeOriginalDelegate = null;
        activeDelegateType = null;
        activeDialogsActivity = null;
        try {
            if (filterTabs == null || getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != MAX_TAB_ID) {
                restoreTabId = Integer.MIN_VALUE;
                restorePosition = -1;
                return Integer.MIN_VALUE;
            }
            int id = chooseRestoreTabId(filterTabs, dialogsActivity);
            if (id != Integer.MIN_VALUE && id != MAX_TAB_ID) {
                if (dispatchDelegate) {
                    if (!selectFilterTab(filterTabs, id)) {
                        setFilterTabsSelectionFields(filterTabs, id);
                    }
                } else {
                    setFilterTabsSelectionFields(filterTabs, id);
                }
                return id;
            }
        } catch (Throwable ignored) {
        } finally {
            restoreTabId = Integer.MIN_VALUE;
            restorePosition = -1;
        }
        return Integer.MIN_VALUE;
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

    private static int chooseRestoreTabId(View filterTabs, Object dialogsActivity) {
        int id = restoreTabId;
        if (isRegularTabId(filterTabs, id)) {
            return id;
        }
        id = getIntField(filterTabs, "previousId", Integer.MIN_VALUE);
        if (isRegularTabId(filterTabs, id)) {
            return id;
        }
        id = getDialogsSelectedType(dialogsActivity, 0);
        if (isRegularTabId(filterTabs, id)) {
            return id;
        }
        id = getDialogsSelectedType(dialogsActivity, 1);
        if (isRegularTabId(filterTabs, id)) {
            return id;
        }
        int position = firstRealTabPosition(filterTabs);
        Object tab = getTabAt(filterTabs, position);
        id = getIntField(tab, "id", Integer.MIN_VALUE);
        return isRegularTabId(filterTabs, id) ? id : Integer.MIN_VALUE;
    }

    private static boolean isRegularTabId(View filterTabs, int id) {
        return id != Integer.MIN_VALUE && id != MAX_TAB_ID && findTabPositionById(filterTabs, id) >= 0;
    }

    private static boolean setMaxTabSelectionFields(View filterTabs) {
        try {
            int pos = findTabPositionById(filterTabs, MAX_TAB_ID);
            if (pos < 0) {
                return false;
            }
            setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
            setIntField(filterTabs, "currentPosition", pos);
            setIntField(filterTabs, "oldAnimatedTab", pos);
            hardenFilterTabsDrawState(filterTabs, null);
            View listView = getFieldView(filterTabs, "listView");
            if (listView != null) {
                listView.invalidate();
            }
            filterTabs.invalidate();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean setFilterTabsSelectionFields(View filterTabs, int id) {
        try {
            int pos = findTabPositionById(filterTabs, id);
            if (id == Integer.MIN_VALUE || id == MAX_TAB_ID || pos < 0) {
                return false;
            }
            setIntField(filterTabs, "selectedTabId", id);
            setIntField(filterTabs, "currentPosition", pos);
            setIntField(filterTabs, "oldAnimatedTab", pos);
            setIntField(filterTabs, "previousId", id);
            setIntField(filterTabs, "previousPosition", pos);
            setBooleanField(filterTabs, "animatingIndicator", false);
            filterTabs.setEnabled(true);
            notifyTabsChanged(filterTabs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void normalizeDialogsSelection(Object dialogsActivity, View filterTabs, int fallbackId) {
        try {
            if (dialogsActivity == null || filterTabs == null) {
                return;
            }
            int safeId = fallbackId;
            if (!isRegularTabId(filterTabs, safeId)) {
                safeId = chooseRestoreTabId(filterTabs, dialogsActivity);
            }
            if (!isRegularTabId(filterTabs, safeId)) {
                Object tab = getTabAt(filterTabs, firstRealTabPosition(filterTabs));
                safeId = getIntField(tab, "id", Integer.MIN_VALUE);
            }
            if (!isRegularTabId(filterTabs, safeId)) {
                return;
            }
            Field viewPagesField = findField(dialogsActivity.getClass(), "viewPages");
            if (viewPagesField == null) {
                return;
            }
            viewPagesField.setAccessible(true);
            Object value = viewPagesField.get(dialogsActivity);
            if (!(value instanceof Object[])) {
                return;
            }
            Object[] pages = (Object[]) value;
            for (Object page : pages) {
                int selectedType = getIntField(page, "selectedType", Integer.MIN_VALUE);
                if (!isRegularTabId(filterTabs, selectedType)) {
                    setIntField(page, "selectedType", safeId);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int beginTabTransition() {
        return ++tabTransitionGeneration;
    }

    private static void cancelRegularSelectionRepairs() {
        tabTransitionGeneration++;
    }

    private static void scheduleRegularSelectionRepair(Object dialogsActivity, View root, int targetId) {
        scheduleRegularSelectionRepair(dialogsActivity, root, targetId, tabTransitionGeneration);
    }

    private static void scheduleRegularSelectionRepair(Object dialogsActivity, View root, int targetId, int transitionGeneration) {
        if (dialogsActivity == null || root == null || targetId == Integer.MIN_VALUE || targetId == MAX_TAB_ID) {
            return;
        }
        Runnable repair = () -> repairRegularSelection(dialogsActivity, targetId, transitionGeneration);
        try {
            root.postDelayed(repair, 260);
            root.postDelayed(repair, 700);
            root.postDelayed(repair, 1200);
        } catch (Throwable ignored) {
        }
    }

    private static void repairRegularSelection(Object dialogsActivity, int targetId, int transitionGeneration) {
        try {
            if (transitionGeneration != tabTransitionGeneration) {
                return;
            }
            View root = getFragmentView(dialogsActivity);
            View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
            if (root == null || filterTabs == null || !isRegularTabId(filterTabs, targetId)) {
                return;
            }
            if (getBooleanField(filterTabs, "animatingIndicator", false) || !filterTabs.isEnabled()) {
                return;
            }
            if (root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null) {
                return;
            }
            if (getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != targetId) {
                return;
            }
            if (getDialogsSelectedType(dialogsActivity, 0) == targetId) {
                refreshDialogsPageAdapters(dialogsActivity);
                return;
            }
            setFilterTabsSelectionFields(filterTabs, targetId);
            forceDialogsPagesToSelectedType(dialogsActivity, targetId);
            resetDialogsPagesForMainList(dialogsActivity);
            forceDialogsSwitchNow(dialogsActivity, Boolean.FALSE);
            refreshDialogsPageAdapters(dialogsActivity);
        } catch (Throwable ignored) {
        }
    }

    private static boolean selectFilterTab(View filterTabs, int id) {
        try {
            int pos = findTabPositionById(filterTabs, id);
            Object tab = getTabAt(filterTabs, pos);
            if (tab == null) {
                return false;
            }
            Method method = filterTabs.getClass().getMethod("scrollToTab", tab.getClass(), int.class);
            method.setAccessible(true);
            method.invoke(filterTabs, tab, pos);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Boolean prepareRegularSelectionAfterMax(Object dialogsActivity, View filterTabs, Object[] args) {
        try {
            Object tab = null;
            if (args != null && args.length > 0) {
                if (isMaxTabView(args[0])) {
                    return null;
                }
                tab = extractTab(args[0]);
                if (isMaxTab(tab)) {
                    tab = null;
                }
            }
            int targetId = getIntField(tab, "id", Integer.MIN_VALUE);
            int targetPosition = findTabPositionById(filterTabs, targetId);
            if (targetId == Integer.MIN_VALUE || targetId == MAX_TAB_ID || targetPosition < 0) {
                return null;
            }
            if (getIntField(filterTabs, "previousId", Integer.MIN_VALUE) == MAX_TAB_ID || activeFilterTabs == filterTabs) {
                int realPreviousId = restoreTabId;
                int realPreviousPosition = restorePosition;
                if (realPreviousId == Integer.MIN_VALUE || realPreviousId == MAX_TAB_ID || realPreviousPosition < 0) {
                    realPreviousId = targetId;
                    realPreviousPosition = targetPosition;
                }
                setIntField(filterTabs, "previousId", realPreviousId);
                setIntField(filterTabs, "previousPosition", realPreviousPosition);
                if (args != null && args.length > 1 && args[1] instanceof Boolean) {
                    args[1] = Boolean.FALSE;
                }
                int currentType = getDialogsSelectedType(dialogsActivity, 0);
                return currentType == targetId ? Boolean.FALSE : Boolean.TRUE;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int getDialogsSelectedType(Object dialogsActivity, int pageIndex) {
        try {
            Object[] pages = getDialogsViewPages(dialogsActivity);
            if (pages == null) {
                return Integer.MIN_VALUE;
            }
            if (pageIndex < 0 || pageIndex >= pages.length || pages[pageIndex] == null) {
                return Integer.MIN_VALUE;
            }
            return getIntField(pages[pageIndex], "selectedType", Integer.MIN_VALUE);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static void setDialogsSelectedType(Object dialogsActivity, int pageIndex, int selectedType) {
        try {
            Object[] pages = getDialogsViewPages(dialogsActivity);
            if (pages == null) {
                return;
            }
            if (pageIndex < 0 || pageIndex >= pages.length || pages[pageIndex] == null) {
                return;
            }
            setIntField(pages[pageIndex], "selectedType", selectedType);
        } catch (Throwable ignored) {
        }
    }

    private static Object[] getDialogsViewPages(Object dialogsActivity) {
        try {
            if (dialogsActivity == null) {
                return null;
            }
            Field viewPagesField = findField(dialogsActivity.getClass(), "viewPages");
            if (viewPagesField == null) {
                return null;
            }
            viewPagesField.setAccessible(true);
            Object value = viewPagesField.get(dialogsActivity);
            return value instanceof Object[] ? (Object[]) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void forceDialogsPagesToSelectedType(Object dialogsActivity, int selectedType) {
        try {
            if (selectedType == Integer.MIN_VALUE || selectedType == MAX_TAB_ID) {
                return;
            }
            Object[] pages = getDialogsViewPages(dialogsActivity);
            if (pages == null) {
                return;
            }
            for (Object page : pages) {
                if (page != null) {
                    setIntField(page, "selectedType", selectedType);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resetDialogsPagesForMainList(Object dialogsActivity) {
        try {
            Object[] pages = getDialogsViewPages(dialogsActivity);
            if (pages == null) {
                return;
            }
            for (int i = 0; i < pages.length; i++) {
                Object page = pages[i];
                if (page instanceof View) {
                    View view = (View) page;
                    view.setTranslationX(0f);
                    view.setTranslationY(0f);
                    view.setAlpha(1f);
                    view.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
                    view.requestLayout();
                    view.invalidate();
                }
                View listView = getFieldView(page, "listView");
                if (listView != null) {
                    listView.setVisibility(View.VISIBLE);
                    listView.setTranslationX(0f);
                    listView.setTranslationY(0f);
                    listView.setAlpha(1f);
                    listView.requestLayout();
                    listView.invalidate();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void refreshDialogsPageAdapters(Object dialogsActivity) {
        try {
            Object[] pages = getDialogsViewPages(dialogsActivity);
            if (pages == null) {
                return;
            }
            for (int i = 0; i < pages.length; i++) {
                Object page = pages[i];
                if (page == null) {
                    continue;
                }
                Object adapter = getFieldObject(page, "dialogsAdapter");
                invokeNoArg(adapter, i == 0 ? "resume" : "pause");
                invokeNoArg(adapter, "notifyDataSetChanged");
                View listView = getFieldView(page, "listView");
                if (listView != null) {
                    invokeNoArg(listView, "stopScroll");
                    invokeNoArg(listView, "invalidateViews");
                    listView.requestLayout();
                    listView.invalidate();
                }
            }
            invokeIntArgMethod(dialogsActivity, "updateVisibleRows", 0);
        } catch (Throwable ignored) {
        }
    }

    private static void forceDialogsSwitch(Object dialogsActivity, Boolean page) {
        if (dialogsActivity == null || page == null) {
            return;
        }
        forceDialogsSwitchNow(dialogsActivity, page);
        View root = getFragmentView(dialogsActivity);
        if (root != null) {
            final boolean targetPage = page.booleanValue();
            root.postDelayed(() -> forceDialogsSwitchNow(dialogsActivity, Boolean.valueOf(targetPage)), 80);
            root.postDelayed(() -> forceDialogsSwitchNow(dialogsActivity, Boolean.valueOf(targetPage)), 240);
        }
    }

    private static void forceDialogsSwitchToTarget(Object dialogsActivity, int selectedType, Boolean page) {
        if (dialogsActivity == null) {
            return;
        }
        if (selectedType == Integer.MIN_VALUE || selectedType == MAX_TAB_ID) {
            forceDialogsSwitch(dialogsActivity, page);
            return;
        }
        reloadDialogsToSelectedType(dialogsActivity, selectedType, page);
        View root = getFragmentView(dialogsActivity);
        if (root != null) {
            final boolean targetPage = page != null && page.booleanValue();
            root.postDelayed(() -> reloadDialogsToSelectedType(dialogsActivity, selectedType, Boolean.valueOf(targetPage)), 80);
            root.postDelayed(() -> reloadDialogsToSelectedType(dialogsActivity, selectedType, Boolean.FALSE), 240);
            root.postDelayed(() -> reloadDialogsToSelectedType(dialogsActivity, selectedType, Boolean.FALSE), 700);
        }
    }

    private static void reloadDialogsToSelectedType(Object dialogsActivity, int selectedType, Boolean page) {
        if (dialogsActivity == null || selectedType == Integer.MIN_VALUE || selectedType == MAX_TAB_ID) {
            return;
        }
        setDialogsSelectedType(dialogsActivity, 0, selectedType);
        setDialogsSelectedType(dialogsActivity, 1, selectedType);
        forceDialogsSwitchNow(dialogsActivity, page != null ? page : Boolean.FALSE);
    }

    private static void forceDialogsSwitchNow(Object dialogsActivity, Boolean page) {
        if (dialogsActivity == null || page == null) {
            return;
        }
        try {
            Method method = dialogsActivity.getClass().getMethod("switchToCurrentSelectedMode", boolean.class);
            method.setAccessible(true);
            method.invoke(dialogsActivity, page.booleanValue());
        } catch (Throwable ignored) {
        }
    }

    private static int findTabPositionById(View filterTabs, int id) {
        if (id == Integer.MIN_VALUE) {
            return -1;
        }
        try {
            Field tabsField = findField(filterTabs.getClass(), "tabs");
            if (tabsField == null) {
                return -1;
            }
            tabsField.setAccessible(true);
            Object value = tabsField.get(filterTabs);
            if (!(value instanceof ArrayList)) {
                return -1;
            }
            ArrayList tabs = (ArrayList) value;
            for (int i = 0; i < tabs.size(); i++) {
                if (getIntField(tabs.get(i), "id", Integer.MIN_VALUE) == id) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
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
        hideOverlay(root, true);
    }

    private static void hideOverlay(View root) {
        hideOverlay(root, false);
    }

    private static void hideOverlay(View root, boolean destroyReusable) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    if (destroyReusable) {
                        ((ViewGroup) root).removeView(overlay);
                        destroyWebViewsLater(root, overlay);
                        clearCachedOverlayIfMatches(overlay);
                    } else {
                        detachOverlayForReuse(root, overlay);
                    }
                }
                restoreSelectionIfCurrentMax();
            }
            if (destroyReusable) {
                destroyCachedOverlay();
            }
        } finally {
            restoreTelegramChrome();
            restoreSystemBars();
        }
    }

    private static void closeOverlayAfterRegularSelection(View root) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    detachOverlayForReuse(root, overlay);
                }
            }
            activeFilterTabs = null;
            activeOriginalDelegate = null;
            activeDelegateType = null;
            activeDialogsActivity = null;
            restoreTabId = Integer.MIN_VALUE;
            restorePosition = -1;
        } finally {
            restoreTelegramChrome();
            restoreSystemBars();
        }
    }

    private static void closeOverlayForSearch(View root, Object dialogsActivity) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    detachOverlayForReuse(root, overlay);
                }
            }
            int restoredId = restoreSelectionFieldsOnly();
            scheduleSearchSafeReload(dialogsActivity, restoredId);
        } finally {
            restoreTelegramChrome();
            restoreSystemBars();
        }
    }

    private static void scheduleSearchSafeReload(Object dialogsActivity, int restoredId) {
        if (dialogsActivity == null || restoredId == Integer.MIN_VALUE || restoredId == MAX_TAB_ID) {
            return;
        }
        View root = getFragmentView(dialogsActivity);
        if (root == null) {
            return;
        }
        final Runnable[] reload = new Runnable[1];
        final int[] attempts = new int[]{0};
        reload[0] = () -> {
            try {
                if (dialogsActivity == null) {
                    return;
                }
                if (!isSearchVisible(dialogsActivity)) {
                    View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
                    if (filterTabs != null) {
                        selectFilterTab(filterTabs, restoredId);
                    }
                    return;
                }
                attempts[0]++;
                if (attempts[0] < 80) {
                    View currentRoot = getFragmentView(dialogsActivity);
                    if (currentRoot != null) {
                        currentRoot.postDelayed(reload[0], 500);
                    }
                }
            } catch (Throwable ignored) {
            }
        };
        root.postDelayed(reload[0], 350);
    }

    private static void detachOverlayForReuse(View host, View overlay) {
        try {
            overlayGuardGeneration++;
            if (overlay == null) {
                return;
            }
            if (overlay instanceof FrameLayout) {
                cacheOverlay((FrameLayout) overlay);
                pauseOverlayWebView((FrameLayout) overlay);
                overlay.setVisibility(View.GONE);
                Object parent = overlay.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(overlay);
                }
            } else {
                Object parent = overlay.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(overlay);
                }
                if (host != null) {
                    destroyWebViewsLater(host, overlay);
                } else {
                    destroyWebViews(overlay);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cacheOverlay(FrameLayout overlay) {
        if (overlay == null) {
            return;
        }
        try {
            if (cachedOverlay != null && cachedOverlay != overlay) {
                destroyCachedOverlay();
            }
            cachedOverlay = overlay;
            cachedWebView = findWebView(overlay);
            Context context = overlay.getContext();
            cachedOverlayActivity = context instanceof Activity ? (Activity) context : null;
        } catch (Throwable ignored) {
        }
    }

    private static void clearCachedOverlayIfMatches(View overlay) {
        if (overlay == null || overlay != cachedOverlay) {
            return;
        }
        cachedOverlay = null;
        cachedWebView = null;
        cachedOverlayActivity = null;
    }

    private static void destroyCachedOverlay() {
        overlayGuardGeneration++;
        FrameLayout overlay = cachedOverlay;
        cachedOverlay = null;
        cachedWebView = null;
        cachedOverlayActivity = null;
        if (overlay == null) {
            return;
        }
        try {
            Object parent = overlay.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(overlay);
            }
            destroyWebViews(overlay);
        } catch (Throwable ignored) {
        }
    }

    private static void pauseOverlayWebView(FrameLayout overlay) {
        try {
            WebView webView = overlay != null ? findWebView(overlay) : null;
            if (webView != null) {
                webView.onPause();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resumeOverlayWebView(FrameLayout overlay) {
        try {
            WebView webView = overlay != null ? findWebView(overlay) : null;
            if (webView != null) {
                webView.onResume();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void destroyWebViewsLater(View host, View view) {
        try {
            host.post(() -> destroyWebViews(view));
        } catch (Throwable ignored) {
            destroyWebViews(view);
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

    private static void prepareOverlayForRoot(FrameLayout overlay, View root) {
        if (overlay == null) {
            return;
        }
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(resolveColor("org.telegram.ui.ActionBar.Theme", "key_windowBackgroundWhite", Color.WHITE));
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setVisibility(View.VISIBLE);
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                hideOverlay(root);
                return true;
            }
            return false;
        });
    }

    private static FrameLayout takeReusableOverlay(Activity activity) {
        try {
            if (cachedOverlay == null) {
                return null;
            }
            if (cachedOverlayActivity != null && cachedOverlayActivity != activity) {
                destroyCachedOverlay();
                return null;
            }
            return cachedOverlay;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void installWebViewClient(WebView webView, View root) {
        if (webView == null) {
            return;
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebViewNavigation(view, url);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request != null && handleWebViewNavigation(view, String.valueOf(request.getUrl()));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                scheduleTelegramThemeInjection(view, root);
            }
        });
    }

    private static boolean shouldLoadMaxUrl(WebView webView, String loadUrl) {
        if (webView == null || loadUrl == null) {
            return false;
        }
        try {
            String current = webView.getUrl();
            if (current == null || current.length() == 0 || current.startsWith("about:blank")) {
                return true;
            }
            if (URL.equals(loadUrl)) {
                return false;
            }
            String currentMax = normalizeMaxUrl(current);
            String targetMax = normalizeMaxUrl(loadUrl);
            return targetMax != null && !targetMax.equals(currentMax);
        } catch (Throwable ignored) {
            return !URL.equals(loadUrl);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showOverlay(Activity activity, View root, View filterTabs, Object dialogsActivity) {
        showOverlay(activity, root, filterTabs, dialogsActivity, URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showOverlay(Activity activity, View root, View filterTabs, Object dialogsActivity, String initialUrl) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        String loadUrl = normalizeMaxUrl(initialUrl);
        if (loadUrl == null) {
            loadUrl = URL;
        }
        ViewGroup parent = (ViewGroup) root;
        View old = parent.findViewWithTag(OVERLAY_TAG);
        if (old instanceof FrameLayout) {
            FrameLayout existing = (FrameLayout) old;
            cacheOverlay(existing);
            prepareOverlayForRoot(existing, root);
            updateOverlayBounds(parent, filterTabs, existing);
            existing.bringToFront();
            if (Build.VERSION.SDK_INT >= 21) {
                existing.setElevation(dp(activity, 256));
                existing.setTranslationZ(dp(activity, 256));
            }
            existing.requestFocus();
            resumeOverlayWebView(existing);
            hideTelegramChrome(activity, parent, existing, dialogsActivity);
            hideSystemBars(activity);
            scheduleOverlayGuard(activity, parent, filterTabs, existing, dialogsActivity);
            scheduleTelegramChromeSuppressBurst(activity, parent, existing, dialogsActivity);
            WebView webView = findWebView(existing);
            if (webView != null) {
                installWebViewClient(webView, root);
                scheduleTelegramThemeInjection(webView, root);
                if (shouldLoadMaxUrl(webView, loadUrl)) {
                    webView.loadUrl(loadUrl);
                }
            }
            return;
        } else if (old != null) {
            parent.removeView(old);
            destroyWebViewsLater(parent, old);
            clearCachedOverlayIfMatches(old);
        }

        FrameLayout overlay = takeReusableOverlay(activity);
        if (overlay != null) {
            Object overlayParent = overlay.getParent();
            if (overlayParent instanceof ViewGroup) {
                ((ViewGroup) overlayParent).removeView(overlay);
            }
            prepareOverlayForRoot(overlay, root);
            WebView reusedWebView = findWebView(overlay);
            if (reusedWebView != null) {
                installWebViewClient(reusedWebView, root);
                int top = estimateTopMargin(parent, filterTabs);
                parent.addView(overlay, makeOverlayLayoutParams(parent, top));
                cacheOverlay(overlay);
                overlay.bringToFront();
                if (Build.VERSION.SDK_INT >= 21) {
                    overlay.setElevation(dp(activity, 256));
                    overlay.setTranslationZ(dp(activity, 256));
                }
                overlay.requestFocus();
                resumeOverlayWebView(overlay);
                hideTelegramChrome(activity, parent, overlay, dialogsActivity);
                hideSystemBars(activity);
                scheduleOverlayGuard(activity, parent, filterTabs, overlay, dialogsActivity);
                scheduleTelegramChromeSuppressBurst(activity, parent, overlay, dialogsActivity);
                if (shouldLoadMaxUrl(reusedWebView, loadUrl)) {
                    reusedWebView.loadUrl(loadUrl);
                }
                scheduleTelegramThemeInjection(reusedWebView, root);
                return;
            }
            destroyCachedOverlay();
        }

        overlay = new FrameLayout(activity);
        prepareOverlayForRoot(overlay, root);

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

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (request != null) {
                    request.deny();
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (callback != null) {
                    callback.invoke(origin, false, false);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                return true;
            }
        });
        installWebViewClient(webView, root);

        int top = estimateTopMargin(parent, filterTabs);
        parent.addView(overlay, makeOverlayLayoutParams(parent, top));
        cacheOverlay(overlay);
        overlay.bringToFront();
        if (Build.VERSION.SDK_INT >= 21) {
            overlay.setElevation(dp(activity, 256));
            overlay.setTranslationZ(dp(activity, 256));
        }
        overlay.requestFocus();
        hideTelegramChrome(activity, parent, overlay, dialogsActivity);
        hideSystemBars(activity);
        scheduleOverlayGuard(activity, parent, filterTabs, overlay, dialogsActivity);
        scheduleTelegramChromeSuppressBurst(activity, parent, overlay, dialogsActivity);
        if (shouldLoadMaxUrl(webView, loadUrl)) {
            webView.loadUrl(loadUrl);
        }
        scheduleTelegramThemeInjection(webView, root);
    }

    private static void scheduleOverlayGuard(Activity activity, ViewGroup root, View filterTabs, FrameLayout overlay, Object dialogsActivity) {
        final Runnable[] guard = new Runnable[1];
        final int[] invalidFrames = new int[]{0};
        final int[] nonMaxFrames = new int[]{0};
        final int guardGeneration = ++overlayGuardGeneration;
        guard[0] = () -> {
            try {
                if (guardGeneration != overlayGuardGeneration) {
                    return;
                }
                if (overlay.getParent() == null) {
                    restoreSelectionIfCurrentMax();
                    restoreTelegramChrome();
                    restoreSystemBars();
                    return;
                }
                if (isSearchVisible(dialogsActivity)) {
                    closeOverlayForSearch(root, dialogsActivity);
                    return;
                }
                View currentFilterTabs = activeFilterTabs != null ? activeFilterTabs : filterTabs;
                if (!root.isShown() || currentFilterTabs == null || !currentFilterTabs.isShown()) {
                    invalidFrames[0]++;
                    if (invalidFrames[0] >= 4) {
                        hideOverlay(root);
                        return;
                    }
                    overlay.postDelayed(guard[0], 250);
                    return;
                }
                invalidFrames[0] = 0;
                int selectedId = getIntField(currentFilterTabs, "selectedTabId", Integer.MIN_VALUE);
                if (selectedId != MAX_TAB_ID) {
                    if (getBooleanField(currentFilterTabs, "animatingIndicator", false) || !currentFilterTabs.isEnabled()) {
                        overlay.postDelayed(guard[0], 120);
                        return;
                    }
                    if (selectedId != Integer.MIN_VALUE && findTabPositionById(currentFilterTabs, selectedId) >= 0) {
                        int transitionGeneration = beginTabTransition();
                        closeOverlayAfterRegularSelection(root);
                        scheduleRegularSelectionRepair(dialogsActivity, root, selectedId, transitionGeneration);
                        return;
                    } else {
                        nonMaxFrames[0] = 0;
                    }
                } else {
                    nonMaxFrames[0] = 0;
                }
                updateOverlayBounds(root, currentFilterTabs, overlay);
                overlay.bringToFront();
                if (Build.VERSION.SDK_INT >= 21) {
                    overlay.setElevation(dp(activity, 256));
                    overlay.setTranslationZ(dp(activity, 256));
                }
                hideTelegramChrome(activity, root, overlay, dialogsActivity);
                hideSystemBars(activity);
                overlay.postDelayed(guard[0], 250);
            } catch (Throwable ignored) {
                if (guardGeneration == overlayGuardGeneration) {
                    hideOverlay(root);
                }
            }
        };
        overlay.postDelayed(guard[0], 250);
    }

    private static void scheduleTelegramChromeSuppressBurst(Activity activity, ViewGroup root, FrameLayout overlay, Object dialogsActivity) {
        if (overlay == null) {
            return;
        }
        final int generation = overlayGuardGeneration;
        final int[] delays = new int[]{16, 64, 140, 240};
        for (int i = 0; i < delays.length; i++) {
            overlay.postDelayed(() -> {
                try {
                    if (generation != overlayGuardGeneration || overlay.getParent() == null) {
                        return;
                    }
                    hideTelegramChrome(activity, root, overlay, dialogsActivity);
                    hideSystemBars(activity);
                } catch (Throwable ignored) {
                }
            }, delays[i]);
        }
    }

    private static void hideTelegramChrome(Activity activity, ViewGroup root, View overlay, Object dialogsActivity) {
        try {
            if (dialogsActivity != null && hiddenChromeDialogsActivity != null && hiddenChromeDialogsActivity != dialogsActivity) {
                restoreTelegramChrome();
            }
            if (dialogsActivity != null) {
                hiddenChromeDialogsActivity = dialogsActivity;
                if (!floatingButtonHiddenCaptured) {
                    previousFloatingButtonHidden = getBooleanField(dialogsActivity, "floatingButtonHidden", false);
                    floatingButtonHiddenCaptured = true;
                }
                setBooleanField(dialogsActivity, "floatingButtonHidden", true);
                invokeBooleanArgMethod(dialogsActivity, "updateFloatingButtonVisibility", false);
                hideFieldView(dialogsActivity, "floatingButton3");
                hideFieldView(dialogsActivity, "floatingButtonStories");
                hideFieldView(dialogsActivity, "writeButton");
                hideFieldView(dialogsActivity, "storyHint");
                hideFieldView(dialogsActivity, "storyPremiumHint");
                hideFieldView(dialogsActivity, "dialogStoriesCell");
                suppressTelegramStories(dialogsActivity);
                Object controller = getFieldObject(dialogsActivity, "mainTabsActivityController");
                if (controller != null) {
                    hiddenMainTabsController = controller;
                    mainTabsControllerHidden = true;
                    invokeBooleanArgMethod(controller, "setTabsVisible", false);
                }
            }
            Object mainTabs = findMainTabsActivity(dialogsActivity);
            if (mainTabs != null) {
                hideFieldView(mainTabs, "tabsView");
                hideFieldView(mainTabs, "fadeView");
            }
            if (root != null) {
                hideTelegramChromeInTree(root, overlay, root.getHeight());
            }
            if (activity != null && activity.getWindow() != null) {
                View decor = activity.getWindow().getDecorView();
                if (decor != null && decor != root) {
                    hideTelegramChromeInTree(decor, overlay, decor.getHeight());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hideFieldView(Object target, String fieldName) {
        View view = getFieldView(target, fieldName);
        if (view != null) {
            hideTelegramChromeView(view);
        }
    }

    private static void hideTelegramChromeInTree(View view, View overlay, int rootHeight) {
        try {
            if (view == null || view == overlay) {
                return;
            }
            if (isTelegramChromeCandidate(view, rootHeight)) {
                hideTelegramChromeView(view);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    hideTelegramChromeInTree(group.getChildAt(i), overlay, rootHeight);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isTelegramChromeCandidate(View view, int rootHeight) {
        try {
            String name = view.getClass().getName();
            if ("org.telegram.ui.MainTabsLayout".equals(name)
                    || name.contains("FragmentFloatingButton")
                    || name.contains("DialogStoriesCell")
                    || name.endsWith("ChatActivityEnterView$SendButton")
                    || name.contains("BottomSheetTabs")) {
                return true;
            }
            if (rootHeight > 0 && name.contains("MainTabsLayout")) {
                int[] pos = new int[2];
                view.getLocationOnScreen(pos);
                return pos[1] + Math.max(view.getHeight(), 1) > rootHeight * 0.55f;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void hideTelegramChromeView(View view) {
        try {
            if (view == null) {
                return;
            }
            rememberHiddenView(view);
            cancelViewAnimations(view);
            invokeBooleanPairMethod(view, "setButtonVisible", false, false);
            invokeNoArg(view, "hide");
            view.setClickable(false);
            view.setEnabled(false);
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private static void rememberHiddenView(View view) {
        try {
            for (int i = 0; i < hiddenTelegramChromeViews.size(); i++) {
                if (hiddenTelegramChromeViews.get(i).view == view) {
                    return;
                }
            }
            hiddenTelegramChromeViews.add(new HiddenViewState(view));
        } catch (Throwable ignored) {
        }
    }

    private static void cancelViewAnimations(View view) {
        try {
            view.clearAnimation();
            if (Build.VERSION.SDK_INT >= 12) {
                view.animate().cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreTelegramChrome() {
        Object dialogsActivity = hiddenChromeDialogsActivity;
        try {
            restoreTelegramStories(dialogsActivity);
            if (dialogsActivity != null && floatingButtonHiddenCaptured) {
                setBooleanField(dialogsActivity, "floatingButtonHidden", previousFloatingButtonHidden);
            }
            if (mainTabsControllerHidden && hiddenMainTabsController != null) {
                invokeBooleanArgMethod(hiddenMainTabsController, "setTabsVisible", isBottomNavigationVisible());
            }
            for (int i = hiddenTelegramChromeViews.size() - 1; i >= 0; i--) {
                hiddenTelegramChromeViews.get(i).restore();
            }
            if (dialogsActivity != null) {
                invokeBooleanArgMethod(dialogsActivity, "updateFloatingButtonVisibility", false);
            }
        } catch (Throwable ignored) {
        } finally {
            hiddenTelegramChromeViews.clear();
            hiddenChromeDialogsActivity = null;
            floatingButtonHiddenCaptured = false;
            previousFloatingButtonHidden = false;
            hiddenMainTabsController = null;
            mainTabsControllerHidden = false;
        }
    }

    private static void suppressTelegramStories(Object dialogsActivity) {
        if (dialogsActivity == null) {
            return;
        }
        try {
            if (!storiesStateCaptured || hiddenStoriesDialogsActivity != dialogsActivity) {
                hiddenStoriesDialogsActivity = dialogsActivity;
                previousHasStories = getBooleanField(dialogsActivity, "hasStories", false);
                previousDialogStoriesCellVisible = getBooleanField(dialogsActivity, "dialogStoriesCellVisible", false);
                previousHasOnlySelfStories = getBooleanField(dialogsActivity, "hasOnlySlefStories", false);
                previousAnimateToHasStories = getBooleanField(dialogsActivity, "animateToHasStories", false);
                previousProgressToDialogStoriesCell = getFloatField(dialogsActivity, "progressToDialogStoriesCell", 0f);
                previousProgressToShowStories = getFloatField(dialogsActivity, "progressToShowStories", 0f);
                previousDialogStoriesAllowGlobalUpdatesCaptured = false;
                Object stories = getFieldObject(dialogsActivity, "dialogStoriesCell");
                if (stories != null) {
                    previousDialogStoriesAllowGlobalUpdates = getBooleanField(stories, "allowGlobalUpdates", true);
                    previousDialogStoriesAllowGlobalUpdatesCaptured = true;
                    previousDialogStoriesCurrentState = getIntField(stories, "currentState", 2);
                    previousDialogStoriesCollapsed = getBooleanField(stories, "collapsed", true);
                    previousDialogStoriesStateCaptured = true;
                }
                storiesStateCaptured = true;
            }

            cancelAnimator(getFieldObject(dialogsActivity, "storiesVisibilityAnimator"));
            cancelAnimator(getFieldObject(dialogsActivity, "storiesVisibilityAnimator2"));
            setBooleanField(dialogsActivity, "hasStories", false);
            setBooleanField(dialogsActivity, "dialogStoriesCellVisible", false);
            setBooleanField(dialogsActivity, "hasOnlySlefStories", false);
            setBooleanField(dialogsActivity, "animateToHasStories", false);
            setFloatField(dialogsActivity, "progressToDialogStoriesCell", 0f);
            setFloatField(dialogsActivity, "progressToShowStories", 0f);

            Object stories = getFieldObject(dialogsActivity, "dialogStoriesCell");
            if (stories instanceof View) {
                View storiesView = (View) stories;
                rememberHiddenView(storiesView);
                cancelViewAnimations(storiesView);
                cancelAnimator(getFieldObject(stories, "valueAnimator"));
                cancelAnimator(getFieldObject(stories, "valueAnimator2"));
                cancelAnimator(getFieldObject(stories, "storiesAnimatorSet"));
                setBooleanField(stories, "allowGlobalUpdates", false);
                setBooleanField(stories, "collapsed", true);
                setIntField(stories, "currentState", 2);
                invokeFloatBooleanArgMethod(stories, "setProgressToCollapse", 1f, false);
                storiesView.setClickable(false);
                storiesView.setEnabled(false);
                storiesView.setFocusable(false);
                storiesView.setFocusableInTouchMode(false);
                storiesView.setAlpha(0f);
                storiesView.setTranslationY(-Math.max(storiesView.getHeight(), dp(storiesView.getContext(), 96)));
                storiesView.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreTelegramStories(Object dialogsActivity) {
        Object target = hiddenStoriesDialogsActivity != null ? hiddenStoriesDialogsActivity : dialogsActivity;
        try {
            if (target != null && storiesStateCaptured) {
                setBooleanField(target, "hasStories", previousHasStories);
                setBooleanField(target, "dialogStoriesCellVisible", previousDialogStoriesCellVisible);
                setBooleanField(target, "hasOnlySlefStories", previousHasOnlySelfStories);
                setBooleanField(target, "animateToHasStories", previousAnimateToHasStories);
                setFloatField(target, "progressToDialogStoriesCell", previousProgressToDialogStoriesCell);
                setFloatField(target, "progressToShowStories", previousProgressToShowStories);
                Object stories = getFieldObject(target, "dialogStoriesCell");
                if (stories != null && previousDialogStoriesAllowGlobalUpdatesCaptured) {
                    setBooleanField(stories, "allowGlobalUpdates", previousDialogStoriesAllowGlobalUpdates);
                }
                if (stories != null && previousDialogStoriesStateCaptured) {
                    setIntField(stories, "currentState", previousDialogStoriesCurrentState);
                    setBooleanField(stories, "collapsed", previousDialogStoriesCollapsed);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            hiddenStoriesDialogsActivity = null;
            storiesStateCaptured = false;
            previousHasStories = false;
            previousDialogStoriesCellVisible = false;
            previousHasOnlySelfStories = false;
            previousAnimateToHasStories = false;
            previousProgressToDialogStoriesCell = 0f;
            previousProgressToShowStories = 0f;
            previousDialogStoriesCurrentState = 2;
            previousDialogStoriesCollapsed = true;
            previousDialogStoriesStateCaptured = false;
            previousDialogStoriesAllowGlobalUpdates = false;
            previousDialogStoriesAllowGlobalUpdatesCaptured = false;
        }
    }

    private static Object findMainTabsActivity(Object dialogsActivity) {
        Object current = getLaunchFragment(true);
        if (isClassName(current, "org.telegram.ui.MainTabsActivity")) {
            return current;
        }
        Object layout = getParentLayout(dialogsActivity);
        Object found = findFragmentInLayout(layout, "org.telegram.ui.MainTabsActivity");
        if (found != null) {
            return found;
        }
        Object launch = getLaunchActivity();
        found = findFragmentInLayout(invokeNoArg(launch, "getActionBarLayout"), "org.telegram.ui.MainTabsActivity");
        if (found != null) {
            return found;
        }
        found = findFragmentInLayout(getFieldObject(launch, "actionBarLayout"), "org.telegram.ui.MainTabsActivity");
        if (found != null) {
            return found;
        }
        return findFragmentInLayout(getFieldObject(launch, "rightActionBarLayout"), "org.telegram.ui.MainTabsActivity");
    }

    private static Object findFragmentInLayout(Object layout, String className) {
        if (layout == null) {
            return null;
        }
        try {
            Method method = layout.getClass().getMethod("getFragmentStack");
            method.setAccessible(true);
            Object value = method.invoke(layout);
            if (!(value instanceof List)) {
                return null;
            }
            List stack = (List) value;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object item = stack.get(i);
                if (isClassName(item, className)) {
                    return item;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isClassName(Object object, String className) {
        return object != null && className.equals(object.getClass().getName());
    }

    private static boolean isBottomNavigationVisible() {
        try {
            Class<?> cls = Class.forName("com.exteragram.messenger.ExteraConfig$BottomNavigationBar");
            Method method = cls.getMethod("visible");
            Object value = method.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static WebView findWebView(View view) {
        try {
            if (view instanceof WebView) {
                return (WebView) view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    WebView found = findWebView(group.getChildAt(i));
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void updateOverlayBounds(ViewGroup root, View filterTabs, FrameLayout overlay) {
        try {
            int top = estimateTopMargin(root, filterTabs);
            ViewGroup.LayoutParams raw = overlay.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
                boolean changed = lp.topMargin != top
                        || lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.height != ViewGroup.LayoutParams.MATCH_PARENT;
                lp.topMargin = top;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                lp.bottomMargin = 0;
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                if (changed) {
                    overlay.setLayoutParams(lp);
                }
                return;
            }
            overlay.setLayoutParams(makeOverlayLayoutParams(root, top));
        } catch (Throwable ignored) {
        }
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

    private static void scheduleTelegramThemeInjection(WebView webView, View anchor) {
        if (webView == null) {
            return;
        }
        try {
            webView.post(() -> applyTelegramTheme(webView, anchor));
            webView.postDelayed(() -> applyTelegramTheme(webView, anchor), 180);
            webView.postDelayed(() -> applyTelegramTheme(webView, anchor), 700);
            webView.postDelayed(() -> applyTelegramTheme(webView, anchor), 1600);
        } catch (Throwable ignored) {
        }
    }

    private static void applyTelegramTheme(WebView webView, View anchor) {
        try {
            TelegramThemeSnapshot theme = collectTelegramTheme(anchor != null ? anchor : webView);
            String js = buildTelegramThemeJavascript(theme);
            if (Build.VERSION.SDK_INT >= 19) {
                webView.evaluateJavascript(js, null);
            } else {
                webView.loadUrl("javascript:" + js);
            }
        } catch (Throwable ignored) {
        }
    }

    private static TelegramThemeSnapshot collectTelegramTheme(View anchor) {
        Context context = anchor != null ? anchor.getContext() : null;
        int bg = nonZeroColor(resolveThemeColor("key_windowBackgroundWhite", Color.WHITE), Color.WHITE);
        int text = nonZeroColor(resolveThemeColor("key_windowBackgroundWhiteBlackText", Color.BLACK), Color.BLACK);
        boolean dark = isDarkColor(bg);
        int muted = nonZeroColor(resolveThemeColor("key_windowBackgroundWhiteGrayText", mixColors(text, bg, 0.48f)),
                mixColors(text, bg, 0.48f));
        int hint = nonZeroColor(resolveThemeColor("key_windowBackgroundWhiteHintText", muted), muted);
        int accent = nonZeroColor(resolveThemeColor("key_featuredStickers_addButton", 0xff2aabee), 0xff2aabee);
        int actionBar = nonZeroColor(resolveThemeColor("key_actionBarDefault", bg), bg);
        int actionTitle = nonZeroColor(resolveThemeColor("key_actionBarDefaultTitle", text), text);
        int panel = nonZeroColor(resolveThemeColor("key_chat_messagePanelBackground", bg), bg);
        int panelText = nonZeroColor(resolveThemeColor("key_chat_messagePanelText", text), text);
        int panelHint = nonZeroColor(resolveThemeColor("key_chat_messagePanelHint", hint), hint);
        int inBubble = nonZeroColor(resolveThemeColor("key_chat_inBubble", dark ? 0xff1d1f21 : 0xffffffff),
                dark ? 0xff1d1f21 : 0xffffffff);
        int outBubble = nonZeroColor(resolveThemeColor("key_chat_outBubble", accent), accent);
        int inText = nonZeroColor(resolveThemeColor("key_chat_messageTextIn", text), text);
        int outText = nonZeroColor(resolveThemeColor("key_chat_messageTextOut", contrastTextColor(outBubble)),
                contrastTextColor(outBubble));
        int inTimeText = nonZeroColor(resolveThemeColor("key_chat_inTimeText", mixColors(inText, inBubble, 0.38f)),
                mixColors(inText, inBubble, 0.38f));
        int outTimeText = nonZeroColor(resolveThemeColor("key_chat_outTimeText", mixColors(outText, outBubble, 0.34f)),
                mixColors(outText, outBubble, 0.34f));
        int inLink = nonZeroColor(resolveThemeColor("key_chat_messageLinkIn", accent), accent);
        int outLink = nonZeroColor(resolveThemeColor("key_chat_messageLinkOut", outText), outText);
        int outBubble1 = nonZeroColor(resolveThemeColor("key_chat_outBubbleGradient1", outBubble), outBubble);
        int outBubble2 = nonZeroColor(resolveThemeColor("key_chat_outBubbleGradient2", outBubble), outBubble);
        int outBubble3 = nonZeroColor(resolveThemeColor("key_chat_outBubbleGradient3", outBubble2), outBubble2);
        int wallpaper = nonZeroColor(resolveThemeColor("key_chat_wallpaper", mixColors(bg, actionBar, 0.18f)),
                mixColors(bg, actionBar, 0.18f));
        int wallpaperTo1 = resolveThemeColor("key_chat_wallpaper_gradient_to1", 0);
        int wallpaperTo2 = resolveThemeColor("key_chat_wallpaper_gradient_to2", 0);
        int wallpaperTo3 = resolveThemeColor("key_chat_wallpaper_gradient_to3", 0);
        int bgSecondary = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.08f : 0.035f);
        int bgTertiary = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.14f : 0.07f);
        int bgCard = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.05f : 0.025f);
        int bgSurface = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.03f : 0.02f);
        int bgHover = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.12f : 0.055f);
        int bgPressed = mixColors(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.18f : 0.095f);
        int bgSelected = mixColors(accent, bg, dark ? 0.76f : 0.86f);
        int stroke = mixColors(text, bg, dark ? 0.74f : 0.86f);
        int strokeSecondary = mixColors(text, bg, dark ? 0.84f : 0.92f);
        int primaryHover = mixColors(accent, dark ? Color.WHITE : Color.BLACK, dark ? 0.10f : 0.08f);
        int primaryPressed = mixColors(accent, dark ? Color.WHITE : Color.BLACK, dark ? 0.16f : 0.14f);
        int buttonText = contrastTextColor(accent);
        int positive = nonZeroColor(resolveThemeColor("key_text_RedRegular", dark ? 0xffff6262 : 0xffd93025),
                dark ? 0xffff6262 : 0xffd93025);
        int negative = positive;
        int online = nonZeroColor(resolveThemeColor("key_chats_onlineCircle", dark ? 0xff5ecf72 : 0xff2ba84a),
                dark ? 0xff5ecf72 : 0xff2ba84a);

        TelegramThemeSnapshot s = new TelegramThemeSnapshot();
        s.dark = dark;
        s.bg = cssColor(bg);
        s.bgSecondary = cssColor(bgSecondary);
        s.bgTertiary = cssColor(bgTertiary);
        s.bgCard = cssColor(bgCard);
        s.bgSurface = cssColor(bgSurface);
        s.bgHover = cssColor(bgHover);
        s.bgPressed = cssColor(bgPressed);
        s.bgSelected = cssColor(bgSelected);
        s.bgOverlay = rgbaColor(Color.BLACK, dark ? 0.54f : 0.32f);
        s.bgOverlayHard = rgbaColor(Color.BLACK, dark ? 0.76f : 0.58f);
        s.text = cssColor(text);
        s.textSecondary = cssColor(mixColors(text, bg, 0.35f));
        s.textTertiary = cssColor(muted);
        s.textDisabled = rgbaColor(text, dark ? 0.34f : 0.30f);
        s.textInverse = cssColor(contrastTextColor(bg));
        s.buttonText = cssColor(buttonText);
        s.hint = cssColor(panelHint);
        s.accent = cssColor(accent);
        s.accentHover = cssColor(primaryHover);
        s.accentPressed = cssColor(primaryPressed);
        s.accentFade = rgbaColor(accent, dark ? 0.20f : 0.13f);
        s.actionBar = cssColor(actionBar);
        s.actionTitle = cssColor(actionTitle);
        s.panel = cssColor(panel);
        s.panelText = cssColor(panelText);
        s.inBubble = cssColor(inBubble);
        s.outBubble = cssColor(outBubble);
        s.inText = cssColor(inText);
        s.outText = cssColor(outText);
        s.inTimeText = cssColor(inTimeText);
        s.outTimeText = cssColor(outTimeText);
        s.inLink = cssColor(inLink);
        s.outLink = cssColor(outLink);
        s.outBubble1 = cssColor(outBubble1);
        s.outBubble2 = cssColor(outBubble2);
        s.outBubble3 = cssColor(outBubble3);
        s.wallpaper = cssColor(wallpaper);
        s.wallpaperTo1 = cssColor(wallpaperTo1 != 0 ? wallpaperTo1 : mixColors(wallpaper, bg, 0.18f));
        s.wallpaperTo2 = cssColor(wallpaperTo2 != 0 ? wallpaperTo2 : mixColors(wallpaper, actionBar, 0.22f));
        s.wallpaperTo3 = cssColor(wallpaperTo3 != 0 ? wallpaperTo3 : mixColors(wallpaper, dark ? Color.BLACK : Color.WHITE, 0.12f));
        s.divider = rgbaColor(text, dark ? 0.16f : 0.10f);
        s.dividerStrong = rgbaColor(text, dark ? 0.25f : 0.16f);
        s.stroke = cssColor(stroke);
        s.strokeSecondary = cssColor(strokeSecondary);
        s.strokeTransparent = rgbaColor(text, dark ? 0.12f : 0.08f);
        s.positive = cssColor(online);
        s.positiveFade = rgbaColor(online, dark ? 0.20f : 0.13f);
        s.negative = cssColor(negative);
        s.negativeFade = rgbaColor(negative, dark ? 0.20f : 0.13f);
        s.capsule = rgbaColor(Color.BLACK, dark ? 0.34f : 0.18f);
        s.counter = cssColor(accent);
        s.reaction = rgbaColor(bgTertiary, dark ? 0.92f : 0.96f);
        s.sferumCard = cssColor(mixColors(accent, bg, dark ? 0.78f : 0.88f));
        s.shadow = rgbaColor(Color.BLACK, dark ? 0.32f : 0.13f);
        s.wallpaperImage = renderTelegramWallpaperDataUrl(anchor, context, wallpaper);
        return s;
    }

    private static String renderTelegramWallpaperDataUrl(View anchor, Context context, int fallbackColor) {
        try {
            Drawable drawable = getTelegramWallpaperDrawable(anchor);
            if (drawable == null || context == null) {
                return null;
            }
            int width = anchor != null && anchor.getWidth() > 0 ? anchor.getWidth() : dp(context, 360);
            int height = anchor != null && anchor.getHeight() > 0 ? anchor.getHeight() : dp(context, 720);
            width = Math.max(160, Math.min(width, 540));
            height = Math.max(320, Math.min(height, 960));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(fallbackColor);
            Drawable copy = drawable.getConstantState() != null ? drawable.getConstantState().newDrawable() : drawable;
            copy.setBounds(0, 0, width, height);
            copy.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, out);
            bitmap.recycle();
            byte[] bytes = out.toByteArray();
            if (bytes.length < 256) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable getTelegramWallpaperDrawable(View anchor) {
        try {
            Class<?> theme = Class.forName("org.telegram.ui.ActionBar.Theme");
            Drawable cached = invokeStaticDrawableNoArg(theme, "getCachedWallpaperNonBlocking");
            if (isRenderableWallpaperDrawable(cached)) {
                return cached;
            }
            Drawable blockingCached = invokeStaticDrawableNoArg(theme, "getCachedWallpaper");
            if (isRenderableWallpaperDrawable(blockingCached)) {
                return blockingCached;
            }
            Method method = theme.getMethod("getThemedWallpaper", boolean.class, View.class);
            Object value = method.invoke(null, false, anchor);
            Drawable themed = value instanceof Drawable ? (Drawable) value : null;
            return isRenderableWallpaperDrawable(themed) ? themed : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable invokeStaticDrawableNoArg(Class<?> cls, String methodName) {
        if (cls == null) {
            return null;
        }
        try {
            Method method = cls.getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Drawable ? (Drawable) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isRenderableWallpaperDrawable(Drawable drawable) {
        if (drawable == null) {
            return false;
        }
        try {
            String name = drawable.getClass().getName();
            return name == null || !name.contains("ColorDrawable");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String buildTelegramThemeJavascript(TelegramThemeSnapshot theme) {
        String css = buildTelegramThemeCss(theme);
        return "(function(){try{"
                + "var d=document,e=d.documentElement;"
                + "e.setAttribute('data-etg-max-theme'," + jsQuote(theme.dark ? "dark" : "light") + ");"
                + "var s=d.getElementById('etg-max-theme');"
                + "if(!s){s=d.createElement('style');s.id='etg-max-theme';(d.head||e).appendChild(s);}"
                + "s.textContent=" + jsQuote(css) + ";"
                + "function a(){try{document.body&&document.body.setAttribute('data-etg-max-theme'," + jsQuote(theme.dark ? "dark" : "light") + ");}catch(_){}}"
                + "a();"
                + "if(!window.__etgMaxThemeObserver){window.__etgMaxThemeObserver=new MutationObserver(function(){requestAnimationFrame(a);});window.__etgMaxThemeObserver.observe(e,{childList:true,subtree:true});}"
                + "}catch(e){}})();";
    }

    private static String buildTelegramThemeCss(TelegramThemeSnapshot t) {
        String wallpaperLayer = t.wallpaperImage != null
                ? "url(\"" + t.wallpaperImage + "\")"
                : "none";
        StringBuilder css = new StringBuilder(22000);
        css.append(":root,html[data-etg-max-theme],html[data-etg-max-theme] body{");
        css.append("color-scheme:").append(t.dark ? "dark" : "light").append("!important;");
        appendCssVar(css, "background-primary", t.bg);
        appendCssVar(css, "background-secondary", t.bgSecondary);
        appendCssVar(css, "background-tertiary", t.bgTertiary);
        appendCssVar(css, "background-card", t.bgCard);
        appendCssVar(css, "background-surface", t.bgSurface);
        appendCssVar(css, "background-overlay", t.bgOverlay);
        appendCssVar(css, "background-overlay-hard", t.bgOverlayHard);
        appendCssVar(css, "background-overlay-secondary", t.bgOverlay);
        appendCssVar(css, "background-overlay-media-preview", t.bgOverlayHard);
        appendCssVar(css, "background-sticky", t.bg);
        appendCssVar(css, "input-background", t.bgTertiary);
        appendCssVar(css, "fading-background-primary-step-1", rgbaColor(Color.TRANSPARENT, 0f));
        appendCssVar(css, "fading-background-primary-step-2", t.bg);
        appendCssVar(css, "fading-background-surface-step-1", rgbaColor(Color.TRANSPARENT, 0f));
        appendCssVar(css, "fading-background-surface-step-2", t.bgSurface);
        appendCssVar(css, "divider-primary", t.divider);
        appendCssVar(css, "divider-primary-ghost", t.strokeTransparent);
        appendCssVar(css, "divider-secondary", t.divider);
        appendCssVar(css, "divider-contrast", t.dividerStrong);
        appendCssVar(css, "stroke-themed", t.accent);
        appendCssVar(css, "stroke-primary-carver", t.stroke);
        appendCssVar(css, "stroke-secondary", t.strokeSecondary);
        appendCssVar(css, "stroke-tertiary", t.strokeTransparent);
        appendCssVar(css, "stroke-transparent", t.strokeTransparent);
        appendCssVar(css, "stroke-primary-inverse-static", t.buttonText);
        appendCssVar(css, "stroke-secondary-inverse-static", t.buttonText);
        appendCssVar(css, "stroke-card-carver", t.strokeTransparent);
        appendCssVar(css, "float-primary-flat", t.bgCard);
        appendCssVar(css, "float-surface-flat", t.bgSurface);
        appendCssVar(css, "float-popup-flat", t.bgCard);
        appendCssVar(css, "float-modal", t.bgCard);
        appendCssVar(css, "float-popup-blur", rgbaColor(Color.WHITE, t.dark ? 0.18f : 0.42f));
        appendCssVar(css, "float-fab-flat", t.accent);
        appendCssVar(css, "float-scroll-bar", rgbaColor(Color.WHITE, t.dark ? 0.20f : 0.38f));
        appendCssVar(css, "float-stroke", t.strokeTransparent);
        appendCssVar(css, "text-primary", t.text);
        appendCssVar(css, "text-secondary", t.textSecondary);
        appendCssVar(css, "text-tertiary", t.textTertiary);
        appendCssVar(css, "text-mute", t.textTertiary);
        appendCssVar(css, "text-themed", t.accent);
        appendCssVar(css, "text-primary-static", t.text);
        appendCssVar(css, "text-primary-inverse", t.textInverse);
        appendCssVar(css, "text-primary-inverse-static", t.buttonText);
        appendCssVar(css, "text-secondary-inverse-static", t.buttonText);
        appendCssVar(css, "text-mute-inverse-static", t.buttonText);
        appendCssVar(css, "text-positive", t.positive);
        appendCssVar(css, "text-negative", t.negative);
        appendCssVar(css, "text-attention", t.negative);
        appendCssVar(css, "icon-primary", t.text);
        appendCssVar(css, "icon-secondary", t.textSecondary);
        appendCssVar(css, "icon-tertiary", t.textTertiary);
        appendCssVar(css, "icon-mute", t.textTertiary);
        appendCssVar(css, "icon-themed", t.accent);
        appendCssVar(css, "icon-primary-static", t.text);
        appendCssVar(css, "icon-primary-inverse", t.textInverse);
        appendCssVar(css, "icon-primary-inverse-static", t.buttonText);
        appendCssVar(css, "icon-secondary-inverse-static", t.buttonText);
        appendCssVar(css, "icon-mute-inverse-static", t.buttonText);
        appendCssVar(css, "icon-positive", t.positive);
        appendCssVar(css, "icon-negative", t.negative);
        appendCssVar(css, "button-primary", t.accent);
        appendCssVar(css, "button-primary-contrast", t.bgTertiary);
        appendCssVar(css, "button-secondary", t.bgTertiary);
        appendCssVar(css, "button-secondary-contrast", t.bgTertiary);
        appendCssVar(css, "button-overlay", rgbaColor(Color.WHITE, t.dark ? 0.14f : 0.70f));
        appendCssVar(css, "button-positive", t.positive);
        appendCssVar(css, "button-positive-fade", t.positiveFade);
        appendCssVar(css, "button-negative", t.negative);
        appendCssVar(css, "button-negative-fade", t.negativeFade);
        appendCssVar(css, "button-text-color", t.buttonText);
        appendCssVar(css, "button-text-color-disabled", t.textDisabled);
        appendCssVar(css, "button-text-color-hovered", t.buttonText);
        appendCssVar(css, "button-text-color-pressed", t.buttonText);
        appendCssVar(css, "button-background-color", t.bgTertiary);
        appendCssVar(css, "button-background-color-disabled", t.bgSecondary);
        appendCssVar(css, "button-background-color-hovered", t.bgHover);
        appendCssVar(css, "button-background-color-pressed", t.bgPressed);
        appendCssVar(css, "button-background-active", t.accent);
        appendCssVar(css, "button-stroke", t.strokeTransparent);
        appendCssVar(css, "button-stroke-active", t.accent);
        appendCssVar(css, "tool-button-background-color", t.bgTertiary);
        appendCssVar(css, "tool-button-background-color-disabled", t.bgSecondary);
        appendCssVar(css, "tool-button-background-color-hovered", t.bgHover);
        appendCssVar(css, "tool-button-background-color-pressed", t.bgPressed);
        appendCssVar(css, "tool-button-text-color", t.text);
        appendCssVar(css, "tool-button-text-color-disabled", t.textDisabled);
        appendCssVar(css, "tool-button-text-color-hovered", t.text);
        appendCssVar(css, "tool-button-text-color-pressed", t.text);
        appendCssVar(css, "states-background-card-hover", t.bgHover);
        appendCssVar(css, "states-background-card-pressed", t.bgPressed);
        appendCssVar(css, "states-background-card-selected", t.bgSelected);
        appendCssVar(css, "states-background-card-selected-hover", mixCssAlpha(t.accent, t.dark ? 0.26f : 0.17f));
        appendCssVar(css, "states-background-card-selected-pressed", mixCssAlpha(t.accent, t.dark ? 0.32f : 0.22f));
        appendCssVar(css, "states-background-card-disabled", t.bgSecondary);
        appendCssVar(css, "states-background-highlighted", t.accentFade);
        appendCssVar(css, "states-button-primary-hover", t.accentHover);
        appendCssVar(css, "states-button-primary-pressed", t.accentPressed);
        appendCssVar(css, "states-button-primary-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-primary-contrast-hover", t.bgHover);
        appendCssVar(css, "states-button-primary-contrast-pressed", t.bgPressed);
        appendCssVar(css, "states-button-primary-contrast-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-secondary-hover", t.bgHover);
        appendCssVar(css, "states-button-secondary-pressed", t.bgPressed);
        appendCssVar(css, "states-button-secondary-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-secondary-contrast-hover", t.bgHover);
        appendCssVar(css, "states-button-secondary-contrast-pressed", t.bgPressed);
        appendCssVar(css, "states-button-secondary-contrast-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-ghost-hover", t.bgHover);
        appendCssVar(css, "states-button-ghost-pressed", t.bgPressed);
        appendCssVar(css, "states-button-ghost-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-overlay-hover", rgbaColor(Color.WHITE, t.dark ? 0.20f : 0.78f));
        appendCssVar(css, "states-button-overlay-pressed", rgbaColor(Color.WHITE, t.dark ? 0.26f : 0.86f));
        appendCssVar(css, "states-button-overlay-disabled", rgbaColor(Color.WHITE, t.dark ? 0.08f : 0.42f));
        appendCssVar(css, "states-button-positive-hover", t.positive);
        appendCssVar(css, "states-button-positive-pressed", t.positive);
        appendCssVar(css, "states-button-positive-disabled", t.bgSecondary);
        appendCssVar(css, "states-button-negative-hover", t.negative);
        appendCssVar(css, "states-button-negative-pressed", t.negative);
        appendCssVar(css, "states-button-negative-disabled", t.bgSecondary);
        appendCssVar(css, "states-text-primary-hover", t.text);
        appendCssVar(css, "states-text-primary-pressed", t.text);
        appendCssVar(css, "states-text-primary-disabled", t.textDisabled);
        appendCssVar(css, "states-text-secondary-hover", t.textSecondary);
        appendCssVar(css, "states-text-secondary-pressed", t.textSecondary);
        appendCssVar(css, "states-text-secondary-disabled", t.textDisabled);
        appendCssVar(css, "states-text-themed-hover", t.accentHover);
        appendCssVar(css, "states-text-themed-pressed", t.accentPressed);
        appendCssVar(css, "states-text-themed-disabled", t.textDisabled);
        appendCssVar(css, "states-icon-primary-hover", t.text);
        appendCssVar(css, "states-icon-primary-pressed", t.text);
        appendCssVar(css, "states-icon-primary-disabled", t.textDisabled);
        appendCssVar(css, "states-icon-secondary-hover", t.textSecondary);
        appendCssVar(css, "states-icon-secondary-pressed", t.textSecondary);
        appendCssVar(css, "states-icon-secondary-disabled", t.textDisabled);
        appendCssVar(css, "states-icon-tertiary-hover", t.textSecondary);
        appendCssVar(css, "states-icon-tertiary-pressed", t.textSecondary);
        appendCssVar(css, "states-icon-tertiary-disabled", t.textDisabled);
        appendCssVar(css, "states-icon-themed-hover", t.accentHover);
        appendCssVar(css, "states-icon-themed-pressed", t.accentPressed);
        appendCssVar(css, "states-icon-themed-disabled", t.textDisabled);
        appendCssVar(css, "tabbar-active", t.accent);
        appendCssVar(css, "tabbar-inactive", t.textTertiary);
        appendCssVar(css, "tabbar", t.bg);
        appendCssVar(css, "counter-default", t.bgTertiary);
        appendCssVar(css, "counter-background", t.counter);
        appendCssVar(css, "counter-color", t.buttonText);
        appendCssVar(css, "counter-themed", t.counter);
        appendCssVar(css, "counter-contrast", t.counter);
        appendCssVar(css, "counter-menu", t.counter);
        appendCssVar(css, "counter-mute", t.bgTertiary);
        appendCssVar(css, "counter-attention", t.negative);
        appendCssVar(css, "capsule-outside", t.capsule);
        appendCssVar(css, "capsule-background", t.capsule);
        appendCssVar(css, "capsule-secondary", t.bgTertiary);
        appendCssVar(css, "common-background-capsule", t.capsule);
        appendCssVar(css, "common-text-capsule", t.text);
        appendCssVar(css, "chat-background-background-step-1", t.wallpaper);
        appendCssVar(css, "chat-background-background-step-2", t.wallpaperTo1);
        appendCssVar(css, "chat-background-additional-step-1", t.wallpaperTo1);
        appendCssVar(css, "chat-background-additional-step-2", t.wallpaperTo2);
        appendCssVar(css, "chat-background-additional-step-3", t.wallpaperTo3);
        appendCssVar(css, "chat-background-additional-step-4", t.wallpaperTo2);
        appendCssVar(css, "chat-background-additional-step-5", t.wallpaperTo1);
        appendCssVar(css, "chat-background-additional-step-6", t.wallpaper);
        appendCssVar(css, "chat-background-pattern-step-1", t.wallpaper);
        appendCssVar(css, "chat-background-pattern-step-2", t.wallpaperTo1);
        appendCssVar(css, "chat-background-pattern-step-3", t.wallpaperTo2);
        appendCssVar(css, "chat-background-pattern-step-4", t.wallpaperTo3);
        appendCssVar(css, "chat-background-pattern-step-5", t.wallpaperTo2);
        appendCssVar(css, "chat-background-pattern-step-6", t.wallpaperTo1);
        appendCssVar(css, "chat-background-pattern-gradient-step-1", t.wallpaper);
        appendCssVar(css, "chat-background-pattern-gradient-step-2", t.wallpaperTo1);
        appendCssVar(css, "chat-background-pattern-color", rgbaColor(Color.WHITE, t.dark ? 0.07f : 0.13f));
        appendCssVar(css, "chat-pattern-icon", rgbaColor(Color.WHITE, t.dark ? 0.08f : 0.16f));
        appendCssVar(css, "bubbles-background-bubble", t.inBubble);
        appendCssVar(css, "bubbles-background-bubble-gradient-step-1", t.outBubble1);
        appendCssVar(css, "bubbles-background-bubble-gradient-step-2", t.outBubble2);
        appendCssVar(css, "bubbles-background-bubble-gradient-step-3", t.outBubble3);
        appendCssVar(css, "bubbles-background-bubble-gradient-old-step-1", t.outBubble1);
        appendCssVar(css, "bubbles-background-bubble-gradient-old-step-2", t.outBubble2);
        appendCssVar(css, "bubbles-background-surface-secondary", t.inBubble);
        appendCssVar(css, "bubbles-states-background-hovered-surface-secondary", t.bgHover);
        appendCssVar(css, "bubbles-states-background-pressed-surface-secondary", t.bgPressed);
        appendCssVar(css, "bubbles-background-action", t.accent);
        appendCssVar(css, "bubbles-background-action-secondary", t.bgTertiary);
        appendCssVar(css, "bubbles-background-action-fade", t.accentFade);
        appendCssVar(css, "bubbles-background-text-focus", t.accentFade);
        appendCssVar(css, "bubbles-background-mention", t.accentFade);
        appendCssVar(css, "bubbles-background-mention-pressed", t.bgPressed);
        appendCssVar(css, "bubbles-background-reaction", t.reaction);
        appendCssVar(css, "bubbles-background-reaction-inside-my", t.reaction);
        appendCssVar(css, "bubbles-background-reaction-inside-others", t.reaction);
        appendCssVar(css, "bubbles-background-reaction-outside-my", t.reaction);
        appendCssVar(css, "bubbles-background-reaction-outside-others", t.reaction);
        appendCssVar(css, "bubbles-background-bot-button-default", t.bgTertiary);
        appendCssVar(css, "bubbles-background-bot-button-hovered", t.bgHover);
        appendCssVar(css, "bubbles-background-bot-button-pressed", t.bgPressed);
        appendCssVar(css, "bubbles-background-bot-button-loading", t.bgSecondary);
        appendCssVar(css, "bubbles-background-icon-item", t.bgTertiary);
        appendCssVar(css, "bubbles-background-icon-item-negative", t.negativeFade);
        appendCssVar(css, "bubbles-system-step-1", t.bgCard);
        appendCssVar(css, "bubbles-system-step-2", t.bgTertiary);
        appendCssVar(css, "bubbles-system-step-3", t.bgSecondary);
        appendCssVar(css, "bubbles-system-stroke-step-1", t.strokeTransparent);
        appendCssVar(css, "bubbles-system-stroke-step-2", t.stroke);
        appendCssVar(css, "bubbles-system-stroke-fade-step-1", t.strokeTransparent);
        appendCssVar(css, "bubbles-system-stroke-fade-step-2", t.strokeSecondary);
        appendCssVar(css, "bubbles-system-button-themed", t.accent);
        appendCssVar(css, "states-bubbles-system-button-themed-hover", t.accentHover);
        appendCssVar(css, "states-bubbles-system-button-themed-pressed", t.accentPressed);
        appendCssVar(css, "states-bubbles-system-button-themed-disabled", t.bgSecondary);
        appendCssVar(css, "bubbles-system-icon-themed-contrast", t.buttonText);
        appendCssVar(css, "bubbles-text-body", t.inText);
        appendCssVar(css, "bubbles-text-body-secondary", t.textSecondary);
        appendCssVar(css, "bubbles-text-action", t.accent);
        appendCssVar(css, "bubbles-text-action-fade", t.accentFade);
        appendCssVar(css, "bubbles-text-link", t.inLink);
        appendCssVar(css, "bubbles-text-md-link", t.inLink);
        appendCssVar(css, "bubbles-text-link-underline", t.inLink);
        appendCssVar(css, "bubbles-text-time", t.inTimeText);
        appendCssVar(css, "bubbles-text-author", t.accent);
        appendCssVar(css, "bubbles-text-forward-label", t.textSecondary);
        appendCssVar(css, "bubbles-text-forward-name", t.accent);
        appendCssVar(css, "bubbles-text-reply-body", t.textSecondary);
        appendCssVar(css, "bubbles-text-reply-name", t.accent);
        appendCssVar(css, "bubbles-text-reaction", t.text);
        appendCssVar(css, "bubbles-text-reaction-my", t.text);
        appendCssVar(css, "bubbles-text-reaction-inside-my", t.text);
        appendCssVar(css, "bubbles-text-reaction-inside-others", t.text);
        appendCssVar(css, "bubbles-text-reaction-outside-my", t.text);
        appendCssVar(css, "bubbles-text-reaction-outside-others", t.text);
        appendCssVar(css, "bubbles-icon-action", t.accent);
        appendCssVar(css, "bubbles-icon-action-secondary", t.textSecondary);
        appendCssVar(css, "bubbles-icon-icon-item", t.textTertiary);
        appendCssVar(css, "bubbles-icon-read-status", t.accent);
        appendCssVar(css, "bubbles-icon-read-status-capsule", t.accent);
        appendCssVar(css, "bubbles-icon-reply", t.accent);
        appendCssVar(css, "bubbles-icon-reply-forwarded", t.accent);
        appendCssVar(css, "bubbles-icon-alert", t.negative);
        appendCssVar(css, "bubbles-stroke-action", t.accent);
        appendCssVar(css, "bubbles-stroke-control-inactive", t.strokeSecondary);
        appendCssVar(css, "bubbles-stroke-neutral-secondary", t.strokeSecondary);
        appendCssVar(css, "bubbles-stroke-primary-inverse-static", t.buttonText);
        appendCssVar(css, "bubbles-stroke-reply", t.accent);
        appendCssVar(css, "bubbles-stroke-reply-outside", t.accent);
        appendCssVar(css, "sferum-card", t.sferumCard);
        appendCssVar(css, "states-sferum-card-hover", t.bgHover);
        appendCssVar(css, "states-sferum-card-pressed", t.bgPressed);
        appendCssVar(css, "skeleton-cell-static-background", t.bgTertiary);
        appendCssVar(css, "skeleton-grid-static-background", t.bgTertiary);
        appendCssVar(css, "skeleton-bubble-primary-static-background", t.bgTertiary);
        appendCssVar(css, "skeleton-bubble-secondary-static-background", t.bgSecondary);
        appendCssVar(css, "shadow-elevation-1-primary", t.shadow);
        appendCssVar(css, "shadow-elevation-1-secondary", t.shadow);
        appendCssVar(css, "shadow-elevation-2-primary", t.shadow);
        appendCssVar(css, "shadow-elevation-2-secondary", t.shadow);
        appendCssVar(css, "shadow-elevation-3-primary", t.shadow);
        appendCssVar(css, "shadow-elevation-3-secondary", t.shadow);
        appendCssVar(css, "shadow-elevation-4-primary", t.shadow);
        appendCssVar(css, "shadow-elevation-4-secondary", t.shadow);
        appendCssVar(css, "shadow-modal-color", t.shadow);
        appendCssVar(css, "shadow-tabbar-color", t.shadow);
        appendCssVar(css, "writebar-divider", t.divider);
        appendCssVar(css, "writebar-input-stroke", t.strokeTransparent);
        appendCssVar(css, "writebar-input-text", t.panelText);
        appendCssVar(css, "etg-max-wallpaper-layer", wallpaperLayer);
        appendCssVar(css, "etg-max-wallpaper-gradient", "linear-gradient(135deg," + t.wallpaper + "," + t.wallpaperTo1 + " 55%," + t.wallpaperTo2 + ")");
        css.append("}");
        css.append("html[data-etg-max-theme],html[data-etg-max-theme] body{")
                .append("color-scheme:").append(t.dark ? "dark" : "light").append("!important;")
                .append("background-color:").append(t.bg).append("!important;")
                .append("color:").append(t.text).append("!important;}");
        css.append("html[data-etg-max-theme] body{")
                .append("background-image:var(--etg-max-wallpaper-layer),var(--etg-max-wallpaper-gradient)!important;")
                .append("background-size:cover,cover!important;")
                .append("background-position:center,center!important;")
                .append("background-repeat:no-repeat,no-repeat!important;")
                .append("background-attachment:fixed,fixed!important;}");
        css.append("html[data-etg-max-theme] #app,html[data-etg-max-theme] .app{background-color:transparent!important;}");
        css.append("html[data-etg-max-theme] input,html[data-etg-max-theme] textarea,html[data-etg-max-theme] [contenteditable=true]{")
                .append("color:").append(t.panelText)
                .append("!important;caret-color:").append(t.accent).append("!important;}");
        css.append("html[data-etg-max-theme] .field::placeholder,html[data-etg-max-theme] input::placeholder,html[data-etg-max-theme] textarea::placeholder{color:")
                .append(t.hint).append("!important;}");
        css.append("html[data-etg-max-theme] .button--active,html[data-etg-max-theme] .tab--active,html[data-etg-max-theme] .active-slide,html[data-etg-max-theme] .item--active,html[data-etg-max-theme] .profile--active,html[data-etg-max-theme] .wrapper--selected{color:")
                .append(t.accent).append("!important;}");
        css.append("html[data-etg-max-theme] .background.svelte-1afbb1c,")
                .append("html[data-etg-max-theme] .layer-base.svelte-1afbb1c,")
                .append("html[data-etg-max-theme] .layer-additional.svelte-1afbb1c,")
                .append("html[data-etg-max-theme] .layer-pattern.svelte-1afbb1c,")
                .append("html[data-etg-max-theme] [class*=chat][class*=background],")
                .append("html[data-etg-max-theme] [class*=Chat][class*=Background],")
                .append("html[data-etg-max-theme] [class*=message][class*=background],")
                .append("html[data-etg-max-theme] [class*=Message][class*=Background],")
                .append("html[data-etg-max-theme] [class*=layer-base],")
                .append("html[data-etg-max-theme] [class*=layer-additional],")
                .append("html[data-etg-max-theme] [class*=layer-pattern]{")
                .append("background-image:var(--etg-max-wallpaper-layer),var(--etg-max-wallpaper-gradient)!important;")
                .append("background-color:").append(t.wallpaper).append("!important;")
                .append("background-size:cover,cover!important;")
                .append("background-position:center,center!important;")
                .append("background-repeat:no-repeat,no-repeat!important;}");
        css.append("html[data-etg-max-theme] .container.svelte-fxkkld .message{color:")
                .append(t.text).append("!important;}");
        css.append(".message.svelte-gl41bh{color:").append(t.outText)
                .append("!important;background:linear-gradient(239deg,").append(t.outBubble1).append(" 0%,")
                .append(t.outBubble2).append(" 50%,").append(t.outBubble3).append(" 100%)!important;}");
        css.append(".messageWrapper:not(.messageWrapper--out) .message.svelte-gl41bh{color:").append(t.inText)
                .append("!important;background:").append(t.inBubble).append("!important;}");
        css.append(".message.svelte-gl41bh .meta{color:").append(t.outTimeText).append("!important;}");
        css.append(".messageWrapper:not(.messageWrapper--out) .message.svelte-gl41bh .meta{color:")
                .append(t.inTimeText).append("!important;}");
        css.append(".message.svelte-gl41bh a,.message.svelte-gl41bh .link{color:").append(t.outLink).append("!important;}");
        css.append(".messageWrapper:not(.messageWrapper--out) .message.svelte-gl41bh a,.messageWrapper:not(.messageWrapper--out) .message.svelte-gl41bh .link{color:")
                .append(t.inLink).append("!important;}");
        css.append("svg,path{color:inherit;}");
        return css.toString();
    }

    private static void appendCssVar(StringBuilder css, String name, String value) {
        css.append("--").append(name).append(':').append(value).append("!important;");
    }

    private static String mixCssAlpha(String color, float alpha) {
        try {
            if (color != null && color.startsWith("#") && color.length() == 7) {
                int parsed = Color.parseColor(color);
                return rgbaColor(parsed, alpha);
            }
        } catch (Throwable ignored) {
        }
        return color;
    }

    private static int resolveThemeColor(String fieldName, int fallback) {
        return resolveColor("org.telegram.ui.ActionBar.Theme", fieldName, fallback);
    }

    private static int nonZeroColor(int color, int fallback) {
        return color == 0 ? fallback : color;
    }

    private static boolean isDarkColor(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return luminance < 0.5;
    }

    private static int contrastTextColor(int color) {
        return isDarkColor(color) ? Color.WHITE : Color.BLACK;
    }

    private static int mixColors(int from, int to, float amountTo) {
        float t = Math.max(0f, Math.min(1f, amountTo));
        int a = Math.round(Color.alpha(from) * (1f - t) + Color.alpha(to) * t);
        int r = Math.round(Color.red(from) * (1f - t) + Color.red(to) * t);
        int g = Math.round(Color.green(from) * (1f - t) + Color.green(to) * t);
        int b = Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t);
        return Color.argb(a, r, g, b);
    }

    private static String cssColor(int color) {
        return String.format("#%06X", color & 0x00ffffff);
    }

    private static String rgbaColor(int color, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        return "rgba(" + Color.red(color) + "," + Color.green(color) + "," + Color.blue(color) + "," + trimFloat(a) + ")";
    }

    private static String trimFloat(float value) {
        String text = String.format(java.util.Locale.US, "%.3f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text + "0" : text;
    }

    private static String jsQuote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '<':
                    out.append("\\u003c");
                    break;
                case '>':
                    out.append("\\u003e");
                    break;
                case '&':
                    out.append("\\u0026");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static final class HiddenViewState {
        final View view;
        final int visibility;
        final float alpha;
        final float translationY;
        final boolean enabled;
        final boolean clickable;
        final boolean focusable;
        final boolean focusableInTouchMode;

        HiddenViewState(View view) {
            this.view = view;
            this.visibility = view.getVisibility();
            this.alpha = view.getAlpha();
            this.translationY = view.getTranslationY();
            this.enabled = view.isEnabled();
            this.clickable = view.isClickable();
            this.focusable = view.isFocusable();
            this.focusableInTouchMode = view.isFocusableInTouchMode();
        }

        void restore() {
            try {
                if (view != null) {
                    cancelViewAnimations(view);
                    view.setAlpha(alpha);
                    view.setTranslationY(translationY);
                    view.setEnabled(enabled);
                    view.setClickable(clickable);
                    view.setFocusable(focusable);
                    view.setFocusableInTouchMode(focusableInTouchMode);
                    view.setVisibility(visibility);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class TelegramThemeSnapshot {
        boolean dark;
        String bg;
        String bgSecondary;
        String bgTertiary;
        String bgCard;
        String bgSurface;
        String bgHover;
        String bgPressed;
        String bgSelected;
        String bgOverlay;
        String bgOverlayHard;
        String text;
        String textSecondary;
        String textTertiary;
        String textDisabled;
        String textInverse;
        String buttonText;
        String hint;
        String accent;
        String accentHover;
        String accentPressed;
        String accentFade;
        String actionBar;
        String actionTitle;
        String panel;
        String panelText;
        String inBubble;
        String outBubble;
        String inText;
        String outText;
        String inTimeText;
        String outTimeText;
        String inLink;
        String outLink;
        String outBubble1;
        String outBubble2;
        String outBubble3;
        String wallpaper;
        String wallpaperTo1;
        String wallpaperTo2;
        String wallpaperTo3;
        String divider;
        String dividerStrong;
        String stroke;
        String strokeSecondary;
        String strokeTransparent;
        String positive;
        String positiveFade;
        String negative;
        String negativeFade;
        String capsule;
        String counter;
        String reaction;
        String sferumCard;
        String shadow;
        String wallpaperImage;
    }

    private static void configureWebView(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 16) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
        });
    }

    private static boolean handleWebViewNavigation(WebView view, String url) {
        String maxUrl = normalizeMaxUrl(url);
        if (maxUrl != null) {
            if (!maxUrl.equals(url) && view != null) {
                view.loadUrl(maxUrl);
                return true;
            }
            return false;
        }
        return shouldBlockNavigation(url);
    }

    private static boolean shouldBlockNavigation(String url) {
        if (url == null) {
            return true;
        }
        String value = url.trim().toLowerCase();
        return !(value.startsWith("https://") || value.startsWith("http://"));
    }

    private static boolean isSearchVisible(Object dialogsActivity) {
        if (dialogsActivity == null) {
            return false;
        }
        if (invokeBooleanNoArg(getFieldObject(dialogsActivity, "searchItem"), "isSearchFieldVisible")) {
            return true;
        }
        return invokeBooleanNoArg(getFieldObject(dialogsActivity, "actionBar"), "isSearchFieldVisible");
    }

    private static boolean invokeBooleanNoArg(Object target, String methodName) {
        if (target == null) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void invokeBooleanArgMethod(Object target, String methodName, boolean value) {
        if (target == null) {
            return;
        }
        try {
            Method method = findMethod(target.getClass(), methodName, boolean.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void invokeBooleanPairMethod(Object target, String methodName, boolean first, boolean second) {
        if (target == null) {
            return;
        }
        try {
            Method method = findMethod(target.getClass(), methodName, boolean.class, boolean.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, first, second);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void invokeIntArgMethod(Object target, String methodName, int value) {
        if (target == null) {
            return;
        }
        try {
            Method method = findMethod(target.getClass(), methodName, int.class);
            if (method == null) {
                method = target.getClass().getMethod(methodName, int.class);
            }
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeFloatBooleanArgMethod(Object target, String methodName, float first, boolean second) {
        if (target == null) {
            return;
        }
        try {
            Method method = findMethod(target.getClass(), methodName, float.class, boolean.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, first, second);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelAnimator(Object animator) {
        if (animator == null) {
            return;
        }
        try {
            invokeNoArg(animator, "removeAllListeners");
            invokeNoArg(animator, "cancel");
        } catch (Throwable ignored) {
        }
    }

    private static Object getFieldObject(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hideSystemBars(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        try {
            android.view.Window window = activity.getWindow();
            View decor = window.getDecorView();
            if (decor == null) {
                return;
            }
            if (!systemBarsHidden || systemBarsDecor != decor) {
                restoreSystemBars();
                systemBarsWindow = window;
                systemBarsDecor = decor;
                previousSystemUiVisibility = decor.getSystemUiVisibility();
                if (Build.VERSION.SDK_INT >= 21) {
                    previousStatusBarColor = window.getStatusBarColor();
                    previousNavigationBarColor = window.getNavigationBarColor();
                    previousBarColorsSaved = true;
                } else {
                    previousBarColorsSaved = false;
                }
                systemBarsHidden = true;
                decor.setOnSystemUiVisibilityChangeListener(visibility -> {
                    if (systemBarsHidden && systemBarsDecor == decor) {
                        decor.postDelayed(() -> enforceSystemBarsHidden(decor), 40);
                        decor.postDelayed(() -> enforceSystemBarsHidden(decor), 160);
                    }
                });
            }
            if (Build.VERSION.SDK_INT >= 21) {
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);
            }
            enforceSystemBarsHidden(decor);
            decor.postDelayed(() -> enforceSystemBarsHidden(decor), 40);
            decor.postDelayed(() -> enforceSystemBarsHidden(decor), 180);
            decor.postDelayed(() -> enforceSystemBarsHidden(decor), 520);
        } catch (Throwable ignored) {
        }
    }

    private static void enforceSystemBarsHidden(View decor) {
        try {
            if (decor != null && systemBarsHidden && systemBarsDecor == decor) {
                decor.setSystemUiVisibility((decor.getSystemUiVisibility() | IMMERSIVE_SYSTEM_UI_FLAGS)
                        & ~View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreSystemBars() {
        try {
            View decor = systemBarsDecor;
            if (decor != null) {
                decor.setOnSystemUiVisibilityChangeListener(null);
                decor.setSystemUiVisibility(previousSystemUiVisibility);
            }
            android.view.Window window = systemBarsWindow;
            if (window != null && previousBarColorsSaved && Build.VERSION.SDK_INT >= 21) {
                window.setStatusBarColor(previousStatusBarColor);
                window.setNavigationBarColor(previousNavigationBarColor);
            }
        } catch (Throwable ignored) {
        } finally {
            systemBarsDecor = null;
            systemBarsWindow = null;
            previousSystemUiVisibility = 0;
            previousStatusBarColor = 0;
            previousNavigationBarColor = 0;
            previousBarColorsSaved = false;
            systemBarsHidden = false;
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

    private static float getFloatField(Object target, String name, float fallback) {
        if (target == null) {
            return fallback;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return fallback;
            }
            field.setAccessible(true);
            return field.getFloat(target);
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

    private static void setFloatField(Object target, String name, float value) {
        if (target == null) {
            return;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                field.setFloat(target, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setObjectField(Object target, String name, Object value) {
        if (target == null) {
            return;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                field.set(target, value);
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

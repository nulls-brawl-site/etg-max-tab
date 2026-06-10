package com.etgmax.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
    private static View activeFilterTabs;
    private static Object activeOriginalDelegate;
    private static Class<?> activeDelegateType;
    private static Object activeDialogsActivity;
    private static int restoreTabId = Integer.MIN_VALUE;
    private static int restorePosition = -1;
    private static View systemBarsDecor;
    private static int previousSystemUiVisibility;
    private static boolean systemBarsHidden;

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
        setIntField(filterTabs, "selectedTabId", MAX_TAB_ID);
        setIntField(filterTabs, "currentPosition", maxIndex);
        setIntField(filterTabs, "oldAnimatedTab", maxIndex);
        setBooleanField(filterTabs, "animatingIndicator", false);
        notifyTabsChanged(filterTabs);
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
                return method.invoke(original, args);
            }
            if ("onPageSelected".equals(name) && args != null && args.length > 0) {
                if (isMaxTab(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showOverlay(activity, root, filterTabs, dialogsActivity);
                    return defaultValue(method.getReturnType());
                }
                int targetId = getRegularTargetTabId(filterTabs, args);
                boolean leavingMax = isLeavingMax(root, filterTabs);
                if (leavingMax) {
                    prepareRegularSelectionFromMax(dialogsActivity, filterTabs, targetId);
                }
                Object result = method.invoke(original, args);
                closeOverlayAfterRegularSelection(root);
                if (leavingMax) {
                    scheduleRegularSelectionRepair(dialogsActivity, root, targetId);
                }
                return result;
            }
            if ("onTabSelected".equals(name) && args != null && args.length > 0) {
                if (isMaxTab(args[0])) {
                    rememberRestorePoint(filterTabs, original, delegateType, dialogsActivity);
                    showOverlay(activity, root, filterTabs, dialogsActivity);
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
        normalizeDialogsSelection(dialogsActivity, filterTabs, previousId);
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

    private static void scheduleRegularSelectionRepair(Object dialogsActivity, View root, int targetId) {
        if (dialogsActivity == null || root == null || targetId == Integer.MIN_VALUE || targetId == MAX_TAB_ID) {
            return;
        }
        Runnable repair = () -> repairRegularSelection(dialogsActivity, targetId);
        try {
            root.postDelayed(repair, 120);
            root.postDelayed(repair, 420);
        } catch (Throwable ignored) {
        }
    }

    private static void repairRegularSelection(Object dialogsActivity, int targetId) {
        try {
            View root = getFragmentView(dialogsActivity);
            View filterTabs = getFieldView(dialogsActivity, "filterTabsView");
            if (root == null || filterTabs == null || !isRegularTabId(filterTabs, targetId)) {
                return;
            }
            if (root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(OVERLAY_TAG) != null) {
                return;
            }
            if (getIntField(filterTabs, "selectedTabId", Integer.MIN_VALUE) != targetId) {
                return;
            }
            normalizeDialogsSelection(dialogsActivity, filterTabs, targetId);
            int page0 = getDialogsSelectedType(dialogsActivity, 0);
            int page1 = getDialogsSelectedType(dialogsActivity, 1);
            if (page0 == targetId) {
                forceDialogsSwitchNow(dialogsActivity, Boolean.FALSE);
            } else if (page1 == targetId) {
                forceDialogsSwitchNow(dialogsActivity, Boolean.TRUE);
            } else {
                setDialogsSelectedType(dialogsActivity, 0, targetId);
                forceDialogsSwitchNow(dialogsActivity, Boolean.FALSE);
            }
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
            Field viewPagesField = findField(dialogsActivity.getClass(), "viewPages");
            if (viewPagesField == null) {
                return Integer.MIN_VALUE;
            }
            viewPagesField.setAccessible(true);
            Object value = viewPagesField.get(dialogsActivity);
            if (!(value instanceof Object[])) {
                return Integer.MIN_VALUE;
            }
            Object[] pages = (Object[]) value;
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
            if (pageIndex < 0 || pageIndex >= pages.length || pages[pageIndex] == null) {
                return;
            }
            setIntField(pages[pageIndex], "selectedType", selectedType);
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
        hideOverlay(root);
    }

    private static void hideOverlay(View root) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    ((ViewGroup) root).removeView(overlay);
                    destroyWebViewsLater(root, overlay);
                }
                restoreSelectionIfCurrentMax();
            }
        } finally {
            restoreSystemBars();
        }
    }

    private static void closeOverlayAfterRegularSelection(View root) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    ((ViewGroup) root).removeView(overlay);
                    destroyWebViewsLater(root, overlay);
                }
            }
            activeFilterTabs = null;
            activeOriginalDelegate = null;
            activeDelegateType = null;
            activeDialogsActivity = null;
            restoreTabId = Integer.MIN_VALUE;
            restorePosition = -1;
        } finally {
            restoreSystemBars();
        }
    }

    private static void closeOverlayForSearch(View root, Object dialogsActivity) {
        try {
            if (root instanceof ViewGroup) {
                View overlay = ((ViewGroup) root).findViewWithTag(OVERLAY_TAG);
                if (overlay != null) {
                    ((ViewGroup) root).removeView(overlay);
                    destroyWebViewsLater(root, overlay);
                }
            }
            int restoredId = restoreSelectionFieldsOnly();
            scheduleSearchSafeReload(dialogsActivity, restoredId);
        } finally {
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
            updateOverlayBounds(parent, filterTabs, existing);
            existing.bringToFront();
            if (Build.VERSION.SDK_INT >= 21) {
                existing.setElevation(dp(activity, 256));
                existing.setTranslationZ(dp(activity, 256));
            }
            existing.requestFocus();
            hideSystemBars(activity);
            WebView webView = findWebView(existing);
            if (webView != null && !URL.equals(loadUrl)) {
                webView.loadUrl(loadUrl);
            }
            return;
        } else if (old != null) {
            parent.removeView(old);
            destroyWebViewsLater(parent, old);
        }

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(resolveColor("org.telegram.ui.ActionBar.Theme", "key_windowBackgroundWhite", Color.WHITE));
        overlay.setClickable(true);
        overlay.setFocusable(true);
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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebViewNavigation(view, url);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request != null && handleWebViewNavigation(view, String.valueOf(request.getUrl()));
            }
        });

        int top = estimateTopMargin(parent, filterTabs);
        parent.addView(overlay, makeOverlayLayoutParams(parent, top));
        overlay.bringToFront();
        if (Build.VERSION.SDK_INT >= 21) {
            overlay.setElevation(dp(activity, 256));
            overlay.setTranslationZ(dp(activity, 256));
        }
        overlay.requestFocus();
        hideSystemBars(activity);
        scheduleOverlayGuard(activity, parent, filterTabs, overlay, dialogsActivity);
        webView.loadUrl(loadUrl);
    }

    private static void scheduleOverlayGuard(Activity activity, ViewGroup root, View filterTabs, FrameLayout overlay, Object dialogsActivity) {
        final Runnable[] guard = new Runnable[1];
        final int[] invalidFrames = new int[]{0};
        final int[] nonMaxFrames = new int[]{0};
        guard[0] = () -> {
            try {
                if (overlay.getParent() == null) {
                    restoreSelectionIfCurrentMax();
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
                    if (selectedId != Integer.MIN_VALUE && findTabPositionById(currentFilterTabs, selectedId) >= 0) {
                        nonMaxFrames[0]++;
                        if (nonMaxFrames[0] >= 2) {
                            int maxIndex = findTabPositionById(currentFilterTabs, MAX_TAB_ID);
                            if (maxIndex >= 0) {
                                setIntField(currentFilterTabs, "selectedTabId", MAX_TAB_ID);
                                setIntField(currentFilterTabs, "currentPosition", maxIndex);
                                setIntField(currentFilterTabs, "oldAnimatedTab", maxIndex);
                                setBooleanField(currentFilterTabs, "animatingIndicator", false);
                                notifyTabsChanged(currentFilterTabs);
                            }
                        }
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
                hideSystemBars(activity);
                overlay.postDelayed(guard[0], 250);
            } catch (Throwable ignored) {
                hideOverlay(root);
            }
        };
        overlay.postDelayed(guard[0], 250);
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
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!systemBarsHidden || systemBarsDecor != decor) {
                restoreSystemBars();
                systemBarsDecor = decor;
                previousSystemUiVisibility = decor.getSystemUiVisibility();
                systemBarsHidden = true;
                decor.setOnSystemUiVisibilityChangeListener(visibility -> {
                    if (systemBarsHidden && systemBarsDecor == decor) {
                        decor.postDelayed(() -> {
                            if (systemBarsHidden && systemBarsDecor == decor) {
                                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | flags);
                            }
                        }, 80);
                    }
                });
            }
            decor.setSystemUiVisibility(previousSystemUiVisibility | flags);
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
        } catch (Throwable ignored) {
        } finally {
            systemBarsDecor = null;
            previousSystemUiVisibility = 0;
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

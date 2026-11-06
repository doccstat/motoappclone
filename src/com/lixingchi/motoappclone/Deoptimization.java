package com.lixingchi.motoappclone;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Inlined clone checks must also be invalidated in their callers. */
final class Deoptimization {
    static void member(Member member) throws Exception {
        member(XposedBridge.class.getMethod("deoptimizeMethod", Member.class), member);
    }

    static void member(Method api, Member member) throws Exception {
        if (api.getReturnType() != void.class && api.getReturnType() != boolean.class) {
            throw new IllegalStateException("Unsupported deoptimization API");
        }
        Object result = api.invoke(null, member);
        if (api.getReturnType() == boolean.class && !Boolean.TRUE.equals(result)) {
            throw new IllegalStateException("Deoptimization refused: " + member);
        }
    }

    static void type(Class<?> type) throws Exception {
        type(type, new HashSet<>());
    }

    private static void type(Class<?> type, Set<Class<?>> visited) throws Exception {
        if (!visited.add(type)) return;
        for (Class<?> nested : type.getDeclaredClasses()) type(nested, visited);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) member(constructor);
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) && !Modifier.isNative(method.getModifiers())) member(method);
        }
    }

    @SuppressWarnings("deprecation")
    static void application(ClassLoader loader) throws Exception {
        Set<Class<?>> visited = new HashSet<>();
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (!(current instanceof BaseDexClassLoader)) continue;
            Object pathList = XposedHelpers.getObjectField(current, "pathList");
            for (Object element : (Object[]) XposedHelpers.getObjectField(pathList, "dexElements")) {
                DexFile dex = (DexFile) XposedHelpers.getObjectField(element, "dexFile");
                if (dex == null) continue;
                Enumeration<String> names = dex.entries();
                while (names.hasMoreElements()) type(Class.forName(names.nextElement(), false, current), visited);
            }
        }
        if (visited.isEmpty()) throw new IllegalStateException("No application DEX classes available");
    }

    static void platform(ClassLoader loader, boolean server) throws Exception {
        Set<Class<?>> visited = new HashSet<>();
        if (server) {
            for (String name : new String[]{
                    "com.android.server.pm.UserManagerService", "com.android.server.pm.UserTypeDetails",
                    "com.android.server.pm.Settings", "com.android.server.pm.PackageSetting",
                    "com.android.server.pm.pkg.PackageStateInternal", "com.android.server.pm.ComputerEngine",
                    "com.android.server.pm.AppsFilterBase", "com.android.server.pm.AppsFilterImpl",
                    "com.android.server.pm.ResolveIntentHelper", "com.android.server.pm.DeletePackageHelper",
                    "com.android.server.pm.permission.PermissionManagerServiceImpl",
                    "com.motorola.server.pm.AppCloneHelper", "motorola.core_services.os.MotoAppClone",
                    "com.android.server.am.ProcessList", "com.android.server.am.UserController",
                    "com.android.server.am.ActiveServices", "com.android.server.am.BroadcastController",
                    "com.android.server.accounts.AccountManagerService", "com.android.server.appwidget.AppWidgetServiceImpl",
                    "com.android.server.biometrics.AuthService", "com.android.server.biometrics.sensors.fingerprint.FingerprintService",
                    "com.android.server.inputmethod.InputMethodManagerService", "com.android.server.inputmethod.ZeroJankProxy",
                    "com.android.server.om.OverlayManagerService", "com.android.server.usage.UsageStatsService",
                    "com.android.server.wm.ActivityTaskManagerService", "com.motorola.server.appop.MotoPrcPermissionService",
                    "motorola.wrap.android.os.UserHandle_wrap", "com.android.server.notification.NotificationManagerService",
                    "com.android.server.uri.UriGrantsManagerService", "com.android.server.wm.ActivityStarter",
                    "com.android.server.wm.ActivityStartInterceptor"}) {
                Class<?> type = XposedHelpers.findClassIfExists(name, loader);
                if (type != null) type(type, visited);
            }
        }
        for (String name : new String[]{"android.os.UserHandle", "android.os.UserManager",
                "android.content.ContentProvider", "android.content.pm.PackageItemInfo",
                "android.content.pm.PackageInstaller", "android.app.slice.SliceProvider",
                "android.app.ApplicationPackageManager", "android.app.ContextImpl", "android.app.ActivityThread",
                "com.android.internal.app.SuspendedAppActivity", "com.android.internal.app.ResolverListController"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, null);
            if (type != null) type(type, visited);
        }
    }
}

package com.lixingchi.motoappclone;

import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Exact-firmware experiment. Does not create, start, remove, or renumber users. */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String UMS = "com.android.server.pm.UserManagerService";
    private static final String UI = "com.motorola.deviceguard";
    private static final String TAG = "MotoAppClone: ";
    private static boolean installed;
    private static volatile Object userService;
    private static volatile boolean server;
    private static volatile boolean ready;
    private static volatile boolean lookupFailureLogged;
    private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

    @Override public synchronized void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        // shortcut: process-local hooks need per-user scope; add coordinated provisioning before unattended use.
        if (installed || !ClonePolicy.supported(Build.FINGERPRINT, Build.VERSION.SDK_INT)) return;
        installed = true;
        server = "android".equals(pkg.packageName) && "android".equals(pkg.processName);
        // Some framework versions report the actual system_server process name.
        server |= "android".equals(pkg.packageName) && "system_server".equals(pkg.processName);
        try {
            if (!(Boolean) XposedHelpers.callStaticMethod(android.os.UserHandle.class,
                    "supportsAppClone")) throw new IllegalStateException("Native app cloning is disabled");
            if (server) installServer(pkg.classLoader);
            installRecognition();
            if (UI.equals(pkg.packageName)) installUiLimit();
            if ("com.motorola.launcher3".equals(pkg.packageName)) installLauncherRule(pkg.classLoader);
            deoptimizeRecognitionCallers(pkg.classLoader);
            if (UI.equals(pkg.packageName)) {
                Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
                for (String name : new String[]{"E2.f", "E2.j", "v2.d", "p4.b", "H2.c", "n2.b",
                        "Q5.x", "C.d", "O1.f", "P1.b", "k6.f", "J.i", "s3.e", "G2.a", "f3.b",
                        "M3.c", "W1.b", "com.motorola.deviceguard.storage.activity.JunkCardDetailActivity"}) {
                    deoptimizeClass(XposedHelpers.findClass(name, pkg.classLoader), deopt);
                }
            }
            XposedBridge.log(TAG + "hooks registered in " + pkg.packageName
                    + "; registration is not an end-to-end readiness check");
        } catch (Throwable error) {
            ready = false;
            for (int i = hooks.size() - 1; i >= 0; i--) hooks.get(i).unhook();
            hooks.clear();
            XposedBridge.log(TAG + "REFUSED activation: " + error);
        }
    }

    static void hook(Class<?> type, String name, Object... parameters) {
        hooks.add(XposedHelpers.findAndHookMethod(type, name, parameters));
    }

    private static Object field(Object object, String name) {
        return XposedHelpers.getObjectField(object, name);
    }

    private static boolean infoIsClone(Object info) {
        return info != null && ClonePolicy.isClone(XposedHelpers.getIntField(info, "id"),
                (String) field(info, "userType"), XposedHelpers.getIntField(info, "profileGroupId"));
    }

    @SuppressWarnings("unchecked") private static SparseArray<Object> users(Object service) {
        return (SparseArray<Object>) field(service, "mUsers");
    }

    private static SparseBooleanArray removing(Object service) {
        return (SparseBooleanArray) field(service, "mRemovingUserIds");
    }

    private static int nextId(Object service, boolean clone) {
        synchronized (field(service, "mUsersLock")) {
            SparseArray<Object> existing = users(service);
            SparseBooleanArray deleted = removing(service);
            java.util.function.IntPredicate unavailable = id -> existing.indexOfKey(id) >= 0 || deleted.get(id);
            return clone ? ClonePolicy.nextCloneId(unavailable) : ClonePolicy.nextOrdinaryId(unavailable);
        }
    }

    private static boolean nativeClone(int id) {
        if (id < ClonePolicy.MIN_ID || id > ClonePolicy.MAX_ID) return false;
        Object service = userService;
        if (service == null) return false;
        Object lock = field(service, "mUsersLock");
        if (lock == null) return false;
        synchronized (lock) {
            if (users(service) == null) return false;
            Object data = users(service).get(id);
            return data != null && infoIsClone(field(data, "info"));
        }
    }

    private static boolean isClone(int id) {
        if (server) return nativeClone(id);
        if (id < ClonePolicy.MIN_ID || id > ClonePolicy.MAX_ID) return false;
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", null);
            IBinder binder = (IBinder) XposedHelpers.callStaticMethod(sm, "getService", "user");
            if (binder == null) return false;
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.IUserManager$Stub", null), "asInterface", binder);
            // Privileged clients can inspect the parent directly. Never cache reusable user IDs.
            try {
                return infoIsClone(XposedHelpers.callMethod(service, "getUserInfo", id));
            } catch (Throwable denied) {
                if (!causedBySecurityException(denied)) throw denied;
            }
            if (!ClonePolicy.TYPE.equals(XposedHelpers.callMethod(service, "getProfileType", id))) return false;
            try {
                Object parent = XposedHelpers.callMethod(service, "getProfileParent", id);
                return parent != null && XposedHelpers.getIntField(parent, "id") == 0;
            } catch (Throwable denied) {
                if (!causedBySecurityException(denied)) throw denied;
            }
            // Without QUERY_USERS/CREATE_USERS/MANAGE_USERS/INTERACT_ACROSS_USERS,
            // this succeeds only for a caller in owner's actual profile group.
            return "".equals(XposedHelpers.callMethod(service, "getProfileType", 0));
        } catch (Throwable failure) {
            if (!lookupFailureLogged && !causedBySecurityException(failure)) {
                lookupFailureLogged = true;
                XposedBridge.log(TAG + "profile lookup failed closed: " + failure);
            }
            return false;
        }
    }

    private static boolean causedBySecurityException(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SecurityException) return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    private static void installRecognition() {
        Class<?> handle = android.os.UserHandle.class;
        hook(handle, "isAppCloneUser", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(isClone((Integer) p.args[0]));
            }
        });
        hook(handle, "isSystemOrAppCloneUser", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int id = (Integer) p.args[0];
                p.setResult(id == 0 || isClone(id));
            }
        });
        hook(handle, "isAppCloneUserPair", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int first = (Integer) p.args[0], second = (Integer) p.args[1];
                p.setResult(ClonePolicy.isClonePair(isClone(first), first, isClone(second), second));
            }
        });
    }

    private static void installUiLimit() {
        hook(Resources.class, "getInteger", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                int id = (Integer) p.args[0];
                if (id == 0x010e0084 && "android:integer/config_default_max_clone_profiles".equals(
                        ((Resources) p.thisObject).getResourceName(id))) p.setResult(ClonePolicy.LIMIT);
            }
        });
    }

    private static void installLauncherRule(ClassLoader loader) throws Exception {
        Class<?> rule = XposedHelpers.findClass("com.motorola.plugin.core.rule.UserRule", loader);
        hook(rule, "matchUserAlias", String.class, String.class, android.os.UserManager.class,
                boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ("APP_CLONE".equalsIgnoreCase((String) p.args[1])) {
                    boolean clone = isClone(android.os.Process.myUid() / 100000);
                    p.setResult((Boolean) p.args[3] ? !clone : clone);
                }
            }
        });
        deoptimizeClass(rule, XposedBridge.class.getMethod("deoptimizeMethod", Member.class));
    }

    private static void installServer(ClassLoader loader) throws Exception {
        Class<?> ums = XposedHelpers.findClass(UMS, loader);
        if (XposedHelpers.callStaticMethod(ums, "getInstance") != null) {
            throw new IllegalStateException("Cold framework start required; late injection is unsupported");
        }
        // Resolve required members before installing any hooks or changing the quota.
        for (String name : new String[]{"mUsers", "mUsersLock", "mRemovingUserIds",
                "mRecentlyRemovedIds", "mAppCloneUserRemoveTimes", "mUserTypes", "mSupportsAppClone"}) {
            XposedHelpers.findField(ums, name);
        }
        Class<?> details = XposedHelpers.findClass("com.android.server.pm.UserTypeDetails", loader);
        XposedHelpers.findField(details, "mMaxAllowedPerParent");
        XposedHelpers.findField(details, "mMaxAllowed");
        Class<?> trace = XposedHelpers.findClass("com.android.server.utils.TimingsTraceAndSlog", loader);
        hook(ums, "createUserInternalUncheckedNoTracing", String.class, String.class, int.class,
                int.class, boolean.class, String[].class, trace, Object.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ClonePolicy.TYPE.equals(p.args[1]) && (!ready || (Integer) p.args[3] != 0)) {
                    p.setThrowable(new IllegalStateException("MotoAppClone not ready or parent is not owner 0"));
                }
            }
        });
        hook(ums, "getNextAvailableAppCloneId", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { p.setResult(ready ? nextId(p.thisObject, true) : -1); }
                catch (Throwable error) { p.setThrowable(error); }
            }
        });
        hook(ums, "scanNextAvailableIdLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { p.setResult(nextId(p.thisObject, false)); }
                catch (Throwable error) { p.setThrowable(error); }
            }
        });
        hook(ums, "getNextAvailableId", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int next = nextId(p.thisObject, false);
                    // Reusing a clone ID for a work user can retain access through a living
                    // owner's GIDs. Do not fall through to stock's in-boot ID recycling.
                    if (next < 0) throw new IllegalStateException("No unquarantined user ID available");
                    p.setResult(next);
                } catch (Throwable error) { p.setThrowable(error); }
            }
        });
        hook(ums, "addRemovingUserIdLocked", int.class, new XC_MethodHook() {
            @SuppressWarnings("unchecked") @Override protected void beforeHookedMethod(MethodHookParam p) {
                int id = (Integer) p.args[0];
                try {
                    synchronized (field(p.thisObject, "mUsersLock")) {
                        removing(p.thisObject).put(id, true);
                        LinkedList<Integer> recent = (LinkedList<Integer>) field(p.thisObject, "mRecentlyRemovedIds");
                        recent.add(id);
                        // The stock diagnostics array stays ten entries; extended IDs never index it.
                        if (ClonePolicy.legacyId(id) && nativeClone(id)) {
                            ((long[]) field(p.thisObject, "mAppCloneUserRemoveTimes"))[id - 900] = SystemClock.elapsedRealtime();
                        }
                        if (recent.size() > 100) recent.removeFirst();
                    }
                    p.setResult(null);
                } catch (Throwable error) {
                    // Never fall through to stock's userId-900 array indexing on hook failure.
                    p.setThrowable(error);
                }
            }
        });
        hook(ums, "hasAppClone", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int caller = Binder.getCallingUid() / 100000;
                if (!XposedHelpers.getBooleanField(p.thisObject, "mSupportsAppClone")
                        || (caller != 0 && !nativeClone(caller))) { p.setResult(false); return; }
                synchronized (field(p.thisObject, "mUsersLock")) {
                    SparseArray<Object> existing = users(p.thisObject);
                    boolean found = false;
                    for (int i = 0; i < existing.size(); i++) {
                        if (infoIsClone(field(existing.valueAt(i), "info"))) { found = true; break; }
                    }
                    p.setResult(found);
                }
            }
        });
        Class<?> processes = XposedHelpers.findClass("com.android.server.am.ProcessList", loader);
        hook(processes, "computeGidsForProcess", int.class, int.class, int[].class, boolean.class,
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || userService == null) return;
                int user = (Integer) p.args[1] / 100000;
                if (user != 0 && !nativeClone(user)) return;
                List<Integer> ids = new ArrayList<>();
                synchronized (field(userService, "mUsersLock")) {
                    SparseArray<Object> existing = users(userService);
                    for (int i = 0; i < existing.size(); i++) {
                        if (infoIsClone(field(existing.valueAt(i), "info"))
                                && !removing(userService).get(existing.keyAt(i))) ids.add(existing.keyAt(i));
                    }
                }
                int[] clones = new int[ids.size()];
                for (int i = 0; i < clones.length; i++) clones[i] = ids.get(i);
                p.setResult(ClonePolicy.storageGroups((int[]) p.getResult(), clones));
            }
        });
        RuntimeFixes.install(loader, MainHook::nativeClone);
        hooks.addAll(XposedBridge.hookAllConstructors(ums, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                userService = p.thisObject;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || ready) return;
                try {
                    if (!XposedHelpers.getBooleanField(p.thisObject, "mSupportsAppClone")) {
                        throw new IllegalStateException("Native cloning unavailable");
                    }
                    synchronized (field(p.thisObject, "mUsersLock")) {
                        SparseArray<Object> existing = users(p.thisObject);
                        for (int i = 0; i < existing.size(); i++) {
                            Object info = field(existing.valueAt(i), "info");
                            if (ClonePolicy.legacyId(XposedHelpers.getIntField(info, "id")) && !infoIsClone(info)) {
                                throw new IllegalStateException("Non-clone user occupies a stock reserved clone ID");
                            }
                            if (ClonePolicy.TYPE.equals(field(info, "userType")) && !infoIsClone(info)) {
                                throw new IllegalStateException("Existing clone outside owner 0 / supported ID pool");
                            }
                        }
                        Object clone = ((Map<?, ?>) field(p.thisObject, "mUserTypes")).get(ClonePolicy.TYPE);
                        if (clone == null) throw new IllegalStateException("Missing CLONE definition");
                        XposedHelpers.setIntField(clone, "mMaxAllowed", ClonePolicy.LIMIT);
                        XposedHelpers.setIntField(clone, "mMaxAllowedPerParent", ClonePolicy.LIMIT);
                        ready = true;
                    }
                    XposedBridge.log(TAG + "server policy active: version=0.2, quota=21472, shared IDs=10..21472");
                } catch (Throwable error) {
                    // Recognition/removal remain available for already-existing extended profiles.
                    ready = false;
                    XposedBridge.log(TAG + "creation blocked: " + error);
                }
            }
        }));
    }

    private static void deoptimizeRecognitionCallers(ClassLoader loader) throws Exception {
        // Hooking an ART method does not repair already-inlined copies of its old range test.
        Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
        String[] classes = server ? new String[]{
                UMS, UMS + "$LocalService", "com.android.server.pm.UserTypeDetails",
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
                "com.android.server.wm.ActivityStartInterceptor"
        } : new String[0];
        for (String name : classes) deoptimizeClass(XposedHelpers.findClass(name, loader), deopt);
        for (String name : new String[]{"android.os.UserHandle", "android.os.UserManager",
                "android.content.ContentProvider", "android.content.pm.PackageItemInfo",
                "android.content.pm.PackageInstaller", "android.app.slice.SliceProvider",
                "android.app.ApplicationPackageManager", "android.app.ContextImpl", "android.app.ActivityThread",
                "com.android.internal.app.SuspendedAppActivity", "com.android.internal.app.ResolverListController"}) {
            deoptimizeClass(XposedHelpers.findClass(name, null), deopt);
        }
    }

    private static void deoptimizeClass(Class<?> type, Method deopt) throws Exception {
        for (Class<?> nested : type.getDeclaredClasses()) deoptimizeClass(nested, deopt);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) deopt.invoke(null, constructor);
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) && !Modifier.isNative(method.getModifiers())) {
                deopt.invoke(null, method);
            }
        }
    }
}

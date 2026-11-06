package com.lixingchi.motoappclone;

import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import static com.lixingchi.motoappclone.Hooks.hook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Adapts Motorola's native clone services without creating or renumbering profiles. */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String UMS = "com.android.server.pm.UserManagerService";
    private static final String UI = "com.motorola.deviceguard";
    private static final String TAG = "MotoAppClone: ";
    private static boolean installed;
    private static volatile Object userService;
    private static ClonePolicy policy;
    private static volatile boolean server;
    private static volatile boolean ready;
    private static volatile boolean lookupFailureLogged;

    @Override public synchronized void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (installed) return;
        installed = true;
        server = "android".equals(pkg.packageName) && "android".equals(pkg.processName);
        // Some framework versions report the actual system_server process name.
        server |= "android".equals(pkg.packageName) && "system_server".equals(pkg.processName);
        try {
            if (!(Boolean) XposedHelpers.callStaticMethod(android.os.UserHandle.class,
                    "supportsAppClone")) throw new IllegalStateException("Native app cloning is disabled");
            if (XposedHelpers.getStaticIntField(android.os.UserHandle.class, "PER_USER_RANGE")
                    != ClonePolicy.USER_RANGE) throw new IllegalStateException("Unsupported Android UID layout");
            if (server) installServer(pkg.classLoader);
            installRecognition();
            if (UI.equals(pkg.packageName)) installUiLimit();
            if ("com.motorola.launcher3".equals(pkg.packageName)) installLauncherRule(pkg.classLoader);
            Deoptimization.platform(pkg.classLoader, server);
            if (UI.equals(pkg.packageName)) Deoptimization.application(pkg.classLoader);
            XposedBridge.log(TAG + "hooks registered in " + pkg.packageName);
        } catch (Throwable error) {
            ready = false;
            Hooks.rollback();
            XposedBridge.log(TAG + "REFUSED activation: " + error);
        }
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
            return clone ? policy.nextCloneId(unavailable) : policy.nextOrdinaryId(unavailable);
        }
    }

    private static boolean nativeClone(int id) {
        if (id <= 0 || id > ClonePolicy.MAX_ID) return false;
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
        if (id <= 0 || id > ClonePolicy.MAX_ID) return false;
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
        hook(handle, "isAppCloneUser", boolean.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(isClone((Integer) p.args[0]));
            }
        });
        hook(handle, "isSystemOrAppCloneUser", boolean.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int id = (Integer) p.args[0];
                p.setResult(id == 0 || isClone(id));
            }
        });
        hook(handle, "isAppCloneUserPair", boolean.class, int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int first = (Integer) p.args[0], second = (Integer) p.args[1];
                p.setResult(ClonePolicy.isClonePair(isClone(first), first, isClone(second), second));
            }
        });
    }

    private static void installUiLimit() {
        hook(Resources.class, "getInteger", int.class, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                int id = (Integer) p.args[0];
                if ("android:integer/config_default_max_clone_profiles".equals(
                        ((Resources) p.thisObject).getResourceName(id))) p.setResult(ClonePolicy.LIMIT);
            }
        });
    }

    private static void installLauncherRule(ClassLoader loader) throws Exception {
        Class<?> rule = XposedHelpers.findClass("com.motorola.plugin.core.rule.UserRule", loader);
        hook(rule, "matchUserAlias", boolean.class, String.class, String.class, android.os.UserManager.class,
                boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ("APP_CLONE".equalsIgnoreCase((String) p.args[1])) {
                    boolean clone = isClone(android.os.Process.myUid() / ClonePolicy.USER_RANGE);
                    p.setResult((Boolean) p.args[3] ? !clone : clone);
                }
            }
        });
        Deoptimization.type(rule);
    }

    private static void installServer(ClassLoader loader) throws Exception {
        Class<?> ums = XposedHelpers.findClass(UMS, loader);
        if (XposedHelpers.callStaticMethod(ums, "getInstance") != null) {
            throw new IllegalStateException("Cold framework start required; late injection is unsupported");
        }
        // Validate storage contracts before installing hooks or changing quotas.
        Hooks.field(ums, "mUsers", SparseArray.class);
        Hooks.field(ums, "mUsersLock", Object.class);
        Hooks.field(ums, "mRemovingUserIds", SparseBooleanArray.class);
        Hooks.field(ums, "mRecentlyRemovedIds", List.class);
        Hooks.field(ums, "mAppCloneUserRemoveTimes", long[].class);
        Hooks.field(ums, "mUserTypes", Map.class);
        Hooks.field(ums, "mSupportsAppClone", boolean.class);
        policy = new ClonePolicy(XposedHelpers.getStaticIntField(ums, "MIN_USER_ID"),
                XposedHelpers.getStaticIntField(ums, "MAX_USER_ID"),
                XposedHelpers.getStaticIntField(android.os.UserHandle.class, "MIN_APP_CLONE_USER_ID"),
                XposedHelpers.getStaticIntField(android.os.UserHandle.class, "MAX_APP_CLONE_USER_ID"));
        Class<?> details = XposedHelpers.findClass("com.android.server.pm.UserTypeDetails", loader);
        Hooks.field(details, "mMaxAllowedPerParent", int.class);
        Hooks.field(details, "mMaxAllowed", int.class);
        Class<?> trace = XposedHelpers.findClass("com.android.server.utils.TimingsTraceAndSlog", loader);
        hook(ums, "createUserInternalUncheckedNoTracing", XposedHelpers.findClass("android.content.pm.UserInfo", null),
                String.class, String.class, int.class,
                int.class, boolean.class, String[].class, trace, Object.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ClonePolicy.TYPE.equals(p.args[1]) && (!ready || (Integer) p.args[3] != 0)) {
                    p.setThrowable(new IllegalStateException("MotoAppClone not ready or parent is not owner 0"));
                }
            }
        });
        hook(ums, "getNextAvailableAppCloneId", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { p.setResult(ready ? nextId(p.thisObject, true) : -1); }
                catch (Throwable error) { p.setThrowable(error); }
            }
        });
        hook(ums, "scanNextAvailableIdLocked", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { p.setResult(nextId(p.thisObject, false)); }
                catch (Throwable error) { p.setThrowable(error); }
            }
        });
        hook(ums, "getNextAvailableId", int.class, new XC_MethodHook() {
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
        int historyLimit = XposedHelpers.getStaticIntField(ums, "MAX_RECENTLY_REMOVED_IDS_SIZE");
        if (historyLimit < 1) throw new IllegalStateException("Unsupported removal history bound");
        hook(ums, "addRemovingUserIdLocked", void.class, int.class, new XC_MethodHook() {
            @SuppressWarnings("unchecked") @Override protected void beforeHookedMethod(MethodHookParam p) {
                int id = (Integer) p.args[0];
                try {
                    synchronized (field(p.thisObject, "mUsersLock")) {
                        removing(p.thisObject).put(id, true);
                        List<Integer> recent = (List<Integer>) field(p.thisObject, "mRecentlyRemovedIds");
                        recent.add(id);
                        // Preserve native diagnostics without indexing extended IDs into the stock array.
                        long[] times = (long[]) field(p.thisObject, "mAppCloneUserRemoveTimes");
                        if (policy.nativeId(id) && nativeClone(id) && id - policy.nativeFirst < times.length) {
                            times[id - policy.nativeFirst] = SystemClock.elapsedRealtime();
                        }
                        if (recent.size() > historyLimit) recent.remove(0);
                    }
                    p.setResult(null);
                } catch (Throwable error) {
                    // Never fall through to stock's fixed-range array indexing on hook failure.
                    p.setThrowable(error);
                }
            }
        });
        hook(ums, "hasAppClone", boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int caller = Binder.getCallingUid() / ClonePolicy.USER_RANGE;
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
        hook(processes, "computeGidsForProcess", int[].class, int.class, int.class, int[].class, boolean.class,
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || userService == null) return;
                int user = (Integer) p.args[1] / ClonePolicy.USER_RANGE;
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
        Hooks.constructors(ums, new XC_MethodHook() {
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
                            if (policy.nativeId(XposedHelpers.getIntField(info, "id")) && !infoIsClone(info)) {
                                throw new IllegalStateException("Non-clone user occupies a stock reserved clone ID");
                            }
                            int id = XposedHelpers.getIntField(info, "id");
                            if (ClonePolicy.TYPE.equals(field(info, "userType"))
                                    && (!infoIsClone(info) || id < policy.minId || id > policy.maxId)) {
                                throw new IllegalStateException("Existing clone outside owner 0 / supported ID pool");
                            }
                        }
                        Object clone = ((Map<?, ?>) field(p.thisObject, "mUserTypes")).get(ClonePolicy.TYPE);
                        if (clone == null) throw new IllegalStateException("Missing CLONE definition");
                        XposedHelpers.setIntField(clone, "mMaxAllowed", ClonePolicy.LIMIT);
                        XposedHelpers.setIntField(clone, "mMaxAllowedPerParent", ClonePolicy.LIMIT);
                        ready = true;
                    }
                    XposedBridge.log(TAG + "server policy active: quota=" + ClonePolicy.LIMIT
                            + ", shared IDs=" + policy.minId + ".." + policy.maxId);
                } catch (Throwable error) {
                    // Recognition/removal remain available for already-existing extended profiles.
                    ready = false;
                    XposedBridge.log(TAG + "creation blocked: " + error);
                }
            }
        });
    }
}

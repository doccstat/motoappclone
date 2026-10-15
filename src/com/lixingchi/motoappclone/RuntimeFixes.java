package com.lixingchi.motoappclone;

import android.os.Binder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Server-side fixes also cover callers which do not load the module. */
final class RuntimeFixes {
    private static final ThreadLocal<Integer> profileStarts = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> providerRetryUser = new ThreadLocal<>();
    private static final String REQUEST_USERS = "MotoAppClone:providerRequestUsers";

    static void install(ClassLoader loader, IntPredicate isClone) throws Exception {
        Class<?> controller = XposedHelpers.findClass("com.android.server.am.UserController", loader);
        MainHook.hook(controller, "startProfiles", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                profileStarts.set(profileStarts.get() + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int remaining = profileStarts.get() - 1;
                if (remaining == 0) profileStarts.remove(); else profileStarts.set(remaining);
            }
        });
        // Motorola's Injector returns the service directly, bypassing android.os.UserManager.
        Class<?> userManager = XposedHelpers.findClass("com.android.server.pm.UserManagerService", loader);
        MainHook.hook(userManager, "removeUser", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Keep the startup budget; a stopped clone is not a corrupt clone.
                if (profileStarts.get() > 0 && isClone.test((Integer) p.args[0])) {
                    XposedBridge.log("MotoAppClone: retained stopped clone " + p.args[0]);
                    p.setResult(false);
                }
            }
        });

        Class<?> helper = XposedHelpers.findClass("com.android.server.am.ContentProviderHelper", loader);
        Class<?> appThread = XposedHelpers.findClass("android.app.IApplicationThread", null);
        Class<?> provider = XposedHelpers.findClass("com.android.server.am.ContentProviderRecord", loader);
        Class<?> process = XposedHelpers.findClass("com.android.server.am.ProcessRecord", loader);
        Class<?> processes = XposedHelpers.findClass("com.android.server.am.ProcessList", loader);
        MainHook.hook(helper, "getContentProvider", appThread, String.class, String.class,
                int.class, boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                int target = (Integer) p.args[3];
                int caller = Binder.getCallingUid() / 100000;
                if (p.hasThrowable() || p.getResult() != null || !isClone.test(target)
                        || (caller != 0 && !isClone.test(caller))) return;
                Object[] args = p.args.clone();
                args[3] = 0;
                Integer previous = providerRetryUser.get();
                providerRetryUser.set(target);
                try {
                    // Same Binder caller, package validation and provider permission checks.
                    p.setResult(original(p, args));
                } catch (Throwable error) {
                    p.setThrowable(error);
                } finally {
                    if (previous == null) providerRetryUser.remove(); else providerRetryUser.set(previous);
                }
            }
        });
        MainHook.hook(helper, "incProviderCountLocked", process, provider,
                XposedHelpers.findClass("android.os.IBinder", null), int.class, String.class,
                String.class, boolean.class, boolean.class, long.class, processes, int.class,
                new XC_MethodHook() {
            @SuppressWarnings("unchecked") @Override protected void afterHookedMethod(MethodHookParam p) {
                Integer target = providerRetryUser.get();
                Object connection = p.getResult();
                if (p.hasThrowable() || target == null || connection == null
                        || XposedHelpers.getObjectField(p.args[1], "provider") != null) return;
                // Called under AMS's lock, before publication can race the returned holder.
                Set<Integer> users = (Set<Integer>) XposedHelpers.getAdditionalInstanceField(connection, REQUEST_USERS);
                if (users == null) {
                    users = new HashSet<>();
                    XposedHelpers.setAdditionalInstanceField(connection, REQUEST_USERS, users);
                }
                users.add(target);
            }
        });
        MainHook.hook(provider, "onProviderPublishStatusLocked", boolean.class, new XC_MethodHook() {
            @SuppressWarnings("unchecked") @Override protected void beforeHookedMethod(MethodHookParam p) {
                boolean published = (Boolean) p.args[0];
                for (Object connection : (List<?>) XposedHelpers.getObjectField(p.thisObject, "connections")) {
                    Set<Integer> users = (Set<Integer>) XposedHelpers.removeAdditionalInstanceField(connection, REQUEST_USERS);
                    if (users == null || !XposedHelpers.getBooleanField(connection, "waiting")) continue;
                    Object client = XposedHelpers.getObjectField(connection, "client");
                    if (client == null) continue;
                    Object thread = XposedHelpers.callMethod(client, "getThread");
                    if (thread == null) continue;
                    Object info = XposedHelpers.getObjectField(p.thisObject, "info");
                    for (int user : users) {
                        try {
                            Object holder = XposedHelpers.callMethod(p.thisObject, "newHolder",
                                    published ? connection : null, false);
                            // Keep stock callbacks too: concurrent owner/clone requests use different keys.
                            XposedHelpers.callMethod(thread, "notifyContentProviderPublishStatus", holder,
                                    XposedHelpers.getObjectField(info, "authority"), user, published);
                        } catch (Throwable error) {
                            XposedBridge.log("MotoAppClone: provider publication callback failed: " + error);
                        }
                    }
                }
            }
        });

        Class<?> computer = XposedHelpers.findClass("com.android.server.pm.ComputerEngine", loader);
        MainHook.hook(computer, "getApplicationInfo", String.class, long.class, int.class,
                new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                int callerUid = Binder.getCallingUid();
                if (p.hasThrowable() || p.getResult() != null || callerUid / 100000 != 0
                        || !isClone.test((Integer) p.args[2])) return;
                Object[] args = p.args.clone();
                args[2] = 0;
                try {
                    Object info = original(p, args);
                    // Owner services such as NFC need their own code for a clone Context, not installation.
                    if (info != null && XposedHelpers.getIntField(info, "uid") == callerUid) p.setResult(info);
                } catch (Throwable error) {
                    p.setThrowable(error);
                }
            }
        });

        // Deoptimize the direct callers as well as the hooked methods.
        deopt(XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader),
                "getContentProvider", appThread, String.class, String.class, int.class, boolean.class);
        deopt(XposedHelpers.findClass("com.android.server.pm.IPackageManagerBase", loader),
                "getApplicationInfo", String.class, long.class, int.class);
        deopt(controller, "startProfiles");
        deopt(helper, "getContentProviderImpl", appThread, String.class,
                XposedHelpers.findClass("android.os.IBinder", null), int.class, String.class,
                String.class, boolean.class, int.class);
        deopt(helper, "publishContentProviders", appThread, List.class);
    }

    private static Object original(XC_MethodHook.MethodHookParam p, Object[] args) throws Throwable {
        try {
            return XposedBridge.invokeOriginalMethod(p.method, p.thisObject, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static void deopt(Class<?> type, String name, Class<?>... args) throws Exception {
        XposedBridge.class.getMethod("deoptimizeMethod", Member.class).invoke(null,
                XposedHelpers.findMethodExact(type, name, args));
    }
}

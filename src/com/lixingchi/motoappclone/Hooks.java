package com.lixingchi.motoappclone;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** One process-local hook transaction with explicit method contracts. */
final class Hooks {
    private static final List<XC_MethodHook.Unhook> registrations = new ArrayList<>();

    static void hook(Class<?> type, String name, Class<?> result, Object... parameters) {
        Class<?>[] signature = new Class<?>[parameters.length - 1];
        for (int i = 0; i < signature.length; i++) signature[i] = (Class<?>) parameters[i];
        Method method = XposedHelpers.findMethodExact(type, name, signature);
        if (method.getReturnType() != result) throw new IllegalArgumentException("Unsupported return type: " + method);
        registrations.add(XposedBridge.hookMethod(method, (XC_MethodHook) parameters[signature.length]));
    }

    static void field(Class<?> type, String name, Class<?> expected) {
        if (!expected.isAssignableFrom(XposedHelpers.findField(type, name).getType())) {
            throw new IllegalArgumentException("Unsupported field type: " + type.getName() + "." + name);
        }
    }

    static void constructors(Class<?> type, XC_MethodHook callback) {
        registrations.addAll(XposedBridge.hookAllConstructors(type, callback));
    }

    static void rollback() {
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try { registrations.get(i).unhook(); }
            catch (Throwable error) { XposedBridge.log("MotoAppClone: hook rollback failed: " + error); }
        }
        registrations.clear();
    }
}

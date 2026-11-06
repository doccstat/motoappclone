package com.lixingchi.motoappclone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Dispatches callbacks registered by the production Hooks class. */
final class MainHook {
    static Object invoke(Object receiver, String name, Object... args) throws Throwable {
        Method method = XposedHelpers.methodFor(receiver, name, args);
        XC_MethodHook hook = XposedBridge.HOOKS.get(method);
        XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
        p.method = method; p.thisObject = receiver; p.args = args;
        if (hook != null) hook.before(p);
        if (!p.returnEarly) {
            try { p.setResult(method.invoke(receiver, args)); }
            catch (InvocationTargetException error) { p.setThrowable(error.getCause()); }
        }
        if (hook != null) hook.after(p);
        if (p.hasThrowable()) throw p.getThrowable();
        return p.getResult();
    }
}

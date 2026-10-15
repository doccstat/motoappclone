package com.lixingchi.motoappclone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Registers the production RuntimeFixes callbacks against fake framework methods. */
final class MainHook {
    private static final Map<Method, XC_MethodHook> hooks = new HashMap<>();
    static void hook(Class<?> type, String name, Object... parameters) {
        Class<?>[] args = new Class<?>[parameters.length - 1];
        for (int i = 0; i < args.length; i++) args[i] = (Class<?>) parameters[i];
        Method method = XposedHelpers.findMethodExact(type, name, args);
        if (hooks.put(method, (XC_MethodHook) parameters[args.length]) != null) throw new AssertionError("Duplicate hook");
    }
    static Object invoke(Object receiver, String name, Object... args) throws Throwable {
        Method method = XposedHelpers.methodFor(receiver, name, args);
        XC_MethodHook hook = hooks.get(method);
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

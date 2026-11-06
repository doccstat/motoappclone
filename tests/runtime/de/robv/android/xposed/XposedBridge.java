package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XposedBridge {
    public static final Map<Member, XC_MethodHook> HOOKS = new LinkedHashMap<>();
    public static final List<Member> DEOPTIMIZED = new ArrayList<>();
    public static boolean acceptDeoptimization = true;
    public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args) throws Throwable {
        return ((Method) member).invoke(receiver, args);
    }
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (HOOKS.put(method, callback) != null) throw new AssertionError("Duplicate hook");
        return callback.new Unhook(method);
    }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> hooks = new LinkedHashSet<>();
        for (Member constructor : type.getDeclaredConstructors()) hooks.add(hookMethod(constructor, callback));
        return hooks;
    }
    public static boolean deoptimizeMethod(Member member) {
        DEOPTIMIZED.add(member);
        return acceptDeoptimization;
    }
    public static void log(String message) {}
}

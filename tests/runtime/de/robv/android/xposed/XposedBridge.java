package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

public final class XposedBridge {
    public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args) throws Throwable {
        return ((Method) member).invoke(receiver, args);
    }
    public static boolean deoptimizeMethod(Member member) { return true; }
    public static void log(String message) {}
}

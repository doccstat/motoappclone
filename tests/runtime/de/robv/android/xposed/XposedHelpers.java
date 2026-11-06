package de.robv.android.xposed;

import com.lixingchi.motoappclone.RuntimeHooksTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class XposedHelpers {
    private static final Map<Object, Map<String, Object>> extras = new IdentityHashMap<>();
    public static Class<?> findClass(String name, ClassLoader loader) {
        Class<?> result = RuntimeHooksTest.TYPES.get(name);
        if (result == null) throw new AssertionError("Unmapped framework class: " + name);
        return result;
    }
    public static Class<?> findClassIfExists(String name, ClassLoader loader) {
        return RuntimeHooksTest.TYPES.get(name);
    }
    public static Field findField(Class<?> type, String name) {
        try { return type.getField(name); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    public static Method findMethodExact(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    public static Object callMethod(Object receiver, String name, Object... args) {
        try { return methodFor(receiver, name, args).invoke(receiver, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    public static Method methodFor(Object receiver, String name, Object[] args) {
        for (Method method : receiver.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) return method;
        }
        throw new AssertionError(name);
    }
    public static Object getObjectField(Object receiver, String name) {
        try { return receiver.getClass().getField(name).get(receiver); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    public static boolean getBooleanField(Object receiver, String name) { return (Boolean) getObjectField(receiver, name); }
    public static int getIntField(Object receiver, String name) { return (Integer) getObjectField(receiver, name); }
    public static Object getAdditionalInstanceField(Object receiver, String key) {
        Map<String, Object> fields = extras.get(receiver);
        return fields == null ? null : fields.get(key);
    }
    public static Object setAdditionalInstanceField(Object receiver, String key, Object value) {
        return extras.computeIfAbsent(receiver, ignored -> new HashMap<>()).put(key, value);
    }
    public static Object removeAdditionalInstanceField(Object receiver, String key) {
        Map<String, Object> fields = extras.get(receiver);
        return fields == null ? null : fields.remove(key);
    }
}

package android.os;

public final class Binder {
    private static final ThreadLocal<Integer> uid = ThreadLocal.withInitial(() -> 1000);
    public static int getCallingUid() { return uid.get(); }
    public static void setCallingUid(int value) { uid.set(value); }
}

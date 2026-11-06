package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Test double for callback ordering, results and exception propagation. */
public abstract class XC_MethodHook {
    public final class Unhook {
        private final Member member;
        public Unhook(Member member) { this.member = member; }
        public void unhook() { XposedBridge.HOOKS.remove(member); }
    }
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public boolean returnEarly;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public boolean hasThrowable() { return throwable != null; }
        public Throwable getThrowable() { return throwable; }
        public void setResult(Object value) { result = value; throwable = null; returnEarly = true; }
        public void setThrowable(Throwable error) { throwable = error; result = null; returnEarly = true; }
    }
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
    public final void before(MethodHookParam p) throws Throwable { beforeHookedMethod(p); }
    public final void after(MethodHookParam p) throws Throwable { afterHookedMethod(p); }
}

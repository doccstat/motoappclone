package dalvik.system;

public final class BaseDexClassLoader extends ClassLoader {
    public final PathList pathList = new PathList();
    public BaseDexClassLoader(String... names) { pathList.dexElements = new Element[]{new Element(names)}; }
    public static final class PathList { public Object[] dexElements; }
    public static final class Element {
        public final DexFile dexFile;
        Element(String[] names) { dexFile = new DexFile(names); }
    }
}

package dalvik.system;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;

public final class DexFile {
    private final String[] names;
    DexFile(String[] names) { this.names = names; }
    public Enumeration<String> entries() { return Collections.enumeration(Arrays.asList(names)); }
}

package com.lixingchi.motoappclone;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** Platform-independent policy; no device mutation or persistent identity cache. */
public final class ClonePolicy {
    public static final String TYPE = "android.os.usertype.profile.CLONE";
    public static final String FINGERPRINT =
            "motorola/rtwo_cn/rtwo:16/W1TR36H.1-33/865b8-1204f:user/release-keys";
    public static final int LIMIT = 21472;
    public static final int MIN_ID = 10;
    public static final int MAX_ID = 21472;
    public static final int LEGACY_FIRST = 900;
    public static final int LEGACY_LAST = 909;

    private ClonePolicy() {}

    public static boolean supported(String fingerprint, int sdk) {
        return sdk == 36 && FINGERPRINT.equals(fingerprint);
    }

    public static boolean legacyId(int id) {
        return id >= LEGACY_FIRST && id <= LEGACY_LAST;
    }

    public static boolean isClone(int id, String type, int parent) {
        return id >= MIN_ID && id <= MAX_ID && TYPE.equals(type) && parent == 0;
    }

    public static boolean isClonePair(boolean firstClone, int first,
                                      boolean secondClone, int second) {
        return (firstClone || secondClone)
                && (first == 0 || firstClone) && (second == 0 || secondClone);
    }

    /** Keep existing Moto numbering first, then use the rest of Android's shared pool. */
    public static int nextCloneId(IntPredicate unavailable) {
        for (int id = LEGACY_FIRST; id <= MAX_ID; id++) {
            if (!unavailable.test(id)) return id;
        }
        for (int id = MIN_ID; id < LEGACY_FIRST; id++) {
            if (!unavailable.test(id)) return id;
        }
        return -1;
    }

    public static int nextOrdinaryId(IntPredicate unavailable) {
        for (int id = MIN_ID; id <= MAX_ID; id++) {
            // Stock reserves these ten IDs even while they are unallocated.
            if (!legacyId(id) && !unavailable.test(id)) return id;
        }
        return -1;
    }

    /** Keep stock groups; add only real clones, never every future ID in the shared pool. */
    public static int[] storageGroups(int[] original, int[] clones) {
        Set<Integer> groups = new LinkedHashSet<>();
        for (int gid : original) groups.add(gid);
        groups.add(9997);
        for (int id : clones) {
            if (id < MIN_ID || id > MAX_ID) throw new IllegalArgumentException("Invalid clone ID");
            groups.add(Math.addExact(Math.multiplyExact(id, 100000), 9997));
        }
        int[] result = new int[groups.size()];
        int index = 0;
        for (int gid : groups) result[index++] = gid;
        return result;
    }

}

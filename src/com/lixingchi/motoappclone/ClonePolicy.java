package com.lixingchi.motoappclone;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** Profile identity and allocation policy, independent of a particular device or build. */
public final class ClonePolicy {
    public static final String TYPE = "android.os.usertype.profile.CLONE";
    public static final int LIMIT = 21472;
    public static final int USER_RANGE = 100000;
    public static final int SHARED_APP_GID = 9997;
    public static final int MAX_ID = Math.min(LIMIT, Integer.MAX_VALUE / USER_RANGE - 1);
    public final int minId, maxId, nativeFirst, nativeLast;

    public ClonePolicy(int minId, int maxExclusive, int nativeFirst, int nativeLast) {
        this.minId = minId;
        this.maxId = Math.min(MAX_ID, maxExclusive - 1);
        this.nativeFirst = nativeFirst;
        this.nativeLast = nativeLast;
        if (minId < 1 || maxExclusive <= minId || this.maxId < minId
                || nativeFirst < minId || nativeLast < nativeFirst || nativeLast > this.maxId) {
            throw new IllegalArgumentException("Unsupported user ID bounds");
        }
    }

    public boolean nativeId(int id) {
        return id >= nativeFirst && id <= nativeLast;
    }

    public static boolean isClone(int id, String type, int parent) {
        return id > 0 && id <= MAX_ID && TYPE.equals(type) && parent == 0;
    }

    public static boolean isClonePair(boolean firstClone, int first,
                                      boolean secondClone, int second) {
        return (firstClone || secondClone)
                && (first == 0 || firstClone) && (second == 0 || secondClone);
    }

    /** Prefer the platform's native clone range, then the rest of its shared user pool. */
    public int nextCloneId(IntPredicate unavailable) {
        for (int id = nativeFirst; id <= maxId; id++) {
            if (!unavailable.test(id)) return id;
        }
        for (int id = minId; id < nativeFirst; id++) {
            if (!unavailable.test(id)) return id;
        }
        return -1;
    }

    public int nextOrdinaryId(IntPredicate unavailable) {
        for (int id = minId; id <= maxId; id++) {
            if (!nativeId(id) && !unavailable.test(id)) return id;
        }
        return -1;
    }

    /** Preserve stock groups and add only existing clones, never hypothetical future users. */
    public static int[] storageGroups(int[] original, int[] clones) {
        Set<Integer> groups = new LinkedHashSet<>();
        for (int gid : original) groups.add(gid);
        groups.add(SHARED_APP_GID);
        for (int id : clones) {
            if (id <= 0 || id > MAX_ID) throw new IllegalArgumentException("Invalid clone ID");
            groups.add(Math.addExact(Math.multiplyExact(id, USER_RANGE), SHARED_APP_GID));
        }
        int[] result = new int[groups.size()];
        int index = 0;
        for (int gid : groups) result[index++] = gid;
        return result;
    }
}

package com.lixingchi.motoappclone;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class ClonePolicyTest {
    private static int checks;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks);
    }

    public static void main(String[] args) {
        check(ClonePolicy.supported(ClonePolicy.FINGERPRINT, 36));
        check(!ClonePolicy.supported(ClonePolicy.FINGERPRINT, 37));
        check(!ClonePolicy.supported(ClonePolicy.FINGERPRINT + "-ota", 36));
        check(!ClonePolicy.supported(null, 36));
        check(ClonePolicy.LIMIT == 21472);
        for (int id : new int[]{10, 899, 900, 909, 910, 1024, 21472}) {
            check(ClonePolicy.isClone(id, ClonePolicy.TYPE, 0));
            check(!ClonePolicy.isClone(id, ClonePolicy.TYPE, 10));
            check(!ClonePolicy.isClone(id, "android.os.usertype.profile.MANAGED", 0));
            check(!ClonePolicy.isClone(id, "android.os.usertype.full.SECONDARY", 0));
            check(!ClonePolicy.isClone(id, null, 0));
        }
        for (int id : new int[]{Integer.MIN_VALUE, -1, 0, 9, 21473, Integer.MAX_VALUE}) {
            check(!ClonePolicy.isClone(id, ClonePolicy.TYPE, 0));
        }
        check(ClonePolicy.isClonePair(true, 910, false, 0));
        check(ClonePolicy.isClonePair(false, 0, true, 21472));
        check(ClonePolicy.isClonePair(true, 900, true, 910));
        check(!ClonePolicy.isClonePair(false, 0, false, 0));
        check(!ClonePolicy.isClonePair(true, 910, false, 10));
        check(!ClonePolicy.isClonePair(false, -1, true, 910));

        Set<Integer> used = new HashSet<>(Arrays.asList(0, 10, 900, 901, 902, 903, 904));
        check(ClonePolicy.nextCloneId(used::contains) == 905);
        for (int id = 905; id <= 909; id++) used.add(id);
        check(ClonePolicy.nextCloneId(used::contains) == 910);
        // A work profile or an ID still being removed is just as unavailable as a live clone.
        used.add(910);
        check(ClonePolicy.nextCloneId(used::contains) == 911);
        check(ClonePolicy.nextOrdinaryId(used::contains) == 11);
        check(ClonePolicy.nextOrdinaryId(id -> id < 900) == 910);
        check(ClonePolicy.nextCloneId(id -> id != 21472) == 21472);
        check(ClonePolicy.nextCloneId(id -> id >= 900) == 10);
        check(ClonePolicy.nextCloneId(id -> id != 899) == 899);
        check(ClonePolicy.nextCloneId(id -> true) == -1);
        check(ClonePolicy.nextOrdinaryId(id -> true) == -1);
        // Exhaust the entire pool in memory, without any real users or device commands.
        boolean[] occupied = new boolean[ClonePolicy.MAX_ID + 1];
        int count = 0, id;
        while ((id = ClonePolicy.nextCloneId(value -> occupied[value])) != -1) {
            check(id >= 10 && id <= 21472 && !occupied[id]);
            occupied[id] = true;
            count++;
        }
        check(count == 21463);
        // Removal/reuse does not carry a stale "clone" bit into a managed user.
        occupied[910] = false;
        check(ClonePolicy.nextCloneId(value -> occupied[value]) == 910);
        check(!ClonePolicy.isClone(910, "android.os.usertype.profile.MANAGED", 0));

        int[] original = {3003, 9997, 90009997, 90909997, 3003, 1079};
        int[] copy = original.clone();
        check(Arrays.equals(ClonePolicy.storageGroups(original, new int[]{900, 910, 21472}),
                new int[]{3003, 9997, 90009997, 90909997, 1079, 91009997, 2147209997}));
        check(Arrays.equals(original, copy));
        check(Arrays.equals(ClonePolicy.storageGroups(original, new int[0]),
                new int[]{3003, 9997, 90009997, 90909997, 1079}));
        check(21472L * 100000 + 99999 <= Integer.MAX_VALUE);
        for (int invalid : new int[]{-1, 0, 9, 21473, Integer.MAX_VALUE}) {
            try {
                ClonePolicy.storageGroups(original, new int[]{invalid});
                throw new AssertionError("Accepted invalid ID " + invalid);
            } catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("PASS: " + checks + " policy checks; all 21,463 ID slots exhausted safely in memory");
    }
}

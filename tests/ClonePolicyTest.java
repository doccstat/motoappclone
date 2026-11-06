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
        ClonePolicy policy = new ClonePolicy(10, 21473, 900, 909);
        check(ClonePolicy.LIMIT == 21472);
        for (int id : new int[]{1, 10, 899, 900, 909, 910, 1024, 21472}) {
            check(ClonePolicy.isClone(id, ClonePolicy.TYPE, 0));
            check(!ClonePolicy.isClone(id, ClonePolicy.TYPE, 10));
            check(!ClonePolicy.isClone(id, "android.os.usertype.profile.MANAGED", 0));
            check(!ClonePolicy.isClone(id, "android.os.usertype.full.SECONDARY", 0));
            check(!ClonePolicy.isClone(id, null, 0));
        }
        for (int id : new int[]{Integer.MIN_VALUE, -1, 0, 21473, Integer.MAX_VALUE}) {
            check(!ClonePolicy.isClone(id, ClonePolicy.TYPE, 0));
        }
        check(ClonePolicy.isClonePair(true, 910, false, 0));
        check(ClonePolicy.isClonePair(false, 0, true, 21472));
        check(ClonePolicy.isClonePair(true, 900, true, 910));
        check(!ClonePolicy.isClonePair(false, 0, false, 0));
        check(!ClonePolicy.isClonePair(true, 910, false, 10));
        check(!ClonePolicy.isClonePair(false, -1, true, 910));

        Set<Integer> used = new HashSet<>(Arrays.asList(0, 10, 900, 901, 902, 903, 904));
        check(policy.nextCloneId(used::contains) == 905);
        for (int id = 905; id <= 909; id++) used.add(id);
        check(policy.nextCloneId(used::contains) == 910);
        used.add(910); // Occupied or quarantined IDs of every type are unavailable.
        check(policy.nextCloneId(used::contains) == 911);
        check(policy.nextOrdinaryId(used::contains) == 11);
        check(policy.nextOrdinaryId(id -> id < 900) == 910);
        check(policy.nextCloneId(id -> id != 21472) == 21472);
        check(policy.nextCloneId(id -> id >= 900) == 10);
        check(policy.nextCloneId(id -> id != 899) == 899);
        check(policy.nextCloneId(id -> true) == -1);
        check(policy.nextOrdinaryId(id -> true) == -1);
        boolean[] occupied = new boolean[ClonePolicy.MAX_ID + 1];
        int count = 0, id;
        while ((id = policy.nextCloneId(value -> occupied[value])) != -1) {
            check(id >= policy.minId && id <= policy.maxId && !occupied[id]);
            occupied[id] = true;
            count++;
        }
        check(count == 21463);
        occupied[910] = false;
        check(policy.nextCloneId(value -> occupied[value]) == 910);
        check(!ClonePolicy.isClone(910, "android.os.usertype.profile.MANAGED", 0));

        // Different platform bounds and native ranges must not inherit 900..909 assumptions.
        ClonePolicy alternate = new ClonePolicy(20, 100, 40, 44);
        check(alternate.nextCloneId(value -> false) == 40);
        check(alternate.nextCloneId(value -> value < 45) == 45);
        check(alternate.nextCloneId(value -> value >= 40) == 20);
        check(alternate.nextOrdinaryId(value -> value < 40) == 45);
        check(alternate.nativeId(44) && !alternate.nativeId(45));
        check(new ClonePolicy(10, Integer.MAX_VALUE, 900, 909).maxId == ClonePolicy.MAX_ID);
        for (int[] bounds : new int[][]{{0, 100, 40, 44}, {20, 20, 40, 44}, {20, 100, 10, 44},
                {20, 100, 44, 40}, {20, 100, 40, 100}, {20, Integer.MIN_VALUE, 40, 44}}) {
            try {
                new ClonePolicy(bounds[0], bounds[1], bounds[2], bounds[3]);
                throw new AssertionError("Invalid platform bounds accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }

        int[] original = {3003, 9997, 90009997, 90909997, 3003, 1079};
        int[] copy = original.clone();
        check(Arrays.equals(ClonePolicy.storageGroups(original, new int[]{900, 910, 21472}),
                new int[]{3003, 9997, 90009997, 90909997, 1079, 91009997, 2147209997}));
        check(Arrays.equals(original, copy));
        check(Arrays.equals(ClonePolicy.storageGroups(original, new int[0]),
                new int[]{3003, 9997, 90009997, 90909997, 1079}));
        check((long) ClonePolicy.MAX_ID * ClonePolicy.USER_RANGE + ClonePolicy.USER_RANGE - 1 <= Integer.MAX_VALUE);
        for (int invalid : new int[]{-1, 0, 21473, Integer.MAX_VALUE}) {
            try {
                ClonePolicy.storageGroups(original, new int[]{invalid});
                throw new AssertionError("Accepted invalid ID " + invalid);
            } catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("PASS: " + checks + " policy checks; all 21,463 ID slots exhausted safely in memory");
    }
}

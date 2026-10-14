package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Arrays;

/** Per-ArraysCache request extents; the original pool, locks and array identities remain intact. */
public final class OtgArrayClearState {
    private static final int MODULE = OptimizationModule.OTG_BIOME_ARRAY_CLEAR.ordinal();
    private final int[][] lengths = { new int[128], new int[16], new int[16], new int[16] };

    public static int[] record(int[] array, OtgArrayClearState state, int group, int slot, int requested) {
        int[] sizes = state.lengths[group];
        if (slot >= sizes.length) state.lengths[group] = sizes = Arrays.copyOf(sizes, Math.max(slot + 1, sizes.length * 2));
        // A negative request has no valid prefix contract (e.g. overflow in a caller).
        sizes[slot] = requested < 0 ? array.length : requested;
        return array;
    }

    public static void clear(int[] array, int value, OtgArrayClearState state, int group, int slot) {
        if (OptimizerBridge.isEnabled(MODULE)) {
            // OTG's layer loops only write the requested rectangle. The unused tail
            // was already zero at checkout and is therefore still zero at release.
            Arrays.fill(array, 0, state.lengths[group][slot], value);
        } else {
            Arrays.fill(array, value);
        }
    }
}

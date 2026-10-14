package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.List;

/** Stable counting distribution replaces readResources' 256 complete block-list scans. */
public final class OtgBo4ColumnOrder {
    private static final int MODULE = OptimizationModule.OTG_BO4_LAYOUT.ordinal();

    private OtgBo4ColumnOrder() { }

    public static boolean order(OtgBo4ColumnAccess access, List<?> source, Object[] result) {
        if (!OptimizerBridge.isEnabled(MODULE)) return false;
        int[] next = new int[256];
        int[] columns = new int[source.size()];
        for (int i = 0; i < columns.length; i++) {
            int column = columns[i] = access.ice$column(source.get(i));
            if (column >= 0) next[column]++;
        }
        int offset = 0;
        for (int column = 0; column < next.length; column++) {
            int count = next[column];
            next[column] = offset;
            offset += count;
        }
        for (int i = 0; i < columns.length; i++) {
            int column = columns[i];
            if (column >= 0) result[next[column]++] = source.get(i);
        }
        return true;
    }
}

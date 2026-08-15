package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.RandomAccess;

/** Stable first-minimum selection for an SRP list that never escapes its AI method. */
public final class SrpTargetSearchBridge {
    private static final String MODULE = "srp-target-search";
    private static volatile boolean activated;

    private SrpTargetSearchBridge() {
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void selectFirst(List values, Comparator comparator) {
        if (!OptimizerBridge.isEnabled(MODULE) || !(values instanceof RandomAccess) || values.size() < 2) {
            Collections.sort(values, comparator);
            return;
        }
        int bestIndex = 0;
        Object best = values.get(0);
        for (int i = 1, size = values.size(); i < size; i++) {
            Object candidate = values.get(i);
            if (comparator.compare(candidate, best) < 0) {
                best = candidate;
                bestIndex = i;
            }
        }
        if (bestIndex != 0) Collections.swap(values, 0, bestIndex);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRP 最近目标由完整排序改为稳定线性选择");
        }
    }
}

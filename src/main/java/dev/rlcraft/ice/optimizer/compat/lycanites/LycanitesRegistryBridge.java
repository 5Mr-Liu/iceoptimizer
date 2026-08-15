package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Map;

/** Removes ObjectManager's redundant containsKey + get double lookup. */
public final class LycanitesRegistryBridge {
    private static final String MODULE = "lycanites-registry-lookup";
    private static volatile boolean activated;

    private LycanitesRegistryBridge() {
    }

    @SuppressWarnings("rawtypes")
    public static Object lookup(Map values, String name) {
        String normalized = name.toLowerCase();
        if (!OptimizerBridge.isEnabled(MODULE)) {
            return values.containsKey(normalized) ? values.get(normalized) : null;
        }
        Object result = values.get(normalized);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "Lycanites 高频注册表读取已消除重复 HashMap 探测");
        }
        return result;
    }

    /** Dregora 2.0.8.10 getter variant: preserve its exact, case-sensitive key semantics. */
    @SuppressWarnings("rawtypes")
    public static Object lookupExact(Map values, String name) {
        if (!OptimizerBridge.isEnabled(MODULE)) {
            return values.containsKey(name) ? values.get(name) : null;
        }
        Object result = values.get(name);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "Lycanites 高频注册表读取已消除重复 HashMap 探测");
        }
        return result;
    }
}

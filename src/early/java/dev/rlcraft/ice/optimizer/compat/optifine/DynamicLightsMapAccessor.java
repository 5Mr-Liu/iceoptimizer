package dev.rlcraft.ice.optimizer.compat.optifine;

import java.util.List;

/** Early-load ABI injected into OptiFine's private dynamic-light map. */
public interface DynamicLightsMapAccessor {
    List<?> ice$valueList();
}

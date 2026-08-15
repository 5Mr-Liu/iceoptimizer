package dev.rlcraft.ice.optimizer.compat.lycanites;

import com.google.common.base.Predicate;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.entity.Entity;

/** Exact helpers for Lycanites' all-living-entity potion event. */
public final class LycanitesEffectBridge {
    private static final String MODULE = "lycanites-effect-cache";
    private static final Predicate<Entity> ACCEPT_ALL = input -> true;
    private static final ClassValue<Predicate<Entity>> FILTERS = new ClassValue<Predicate<Entity>>() {
        @Override
        protected Predicate<Entity> computeValue(final Class<?> type) {
            return input -> type.isAssignableFrom(input.getClass());
        }
    };
    private static volatile boolean activated;

    private LycanitesEffectBridge() {
    }

    public static boolean useEffectCache() {
        boolean enabled = OptimizerBridge.isEnabled(MODULE);
        if (enabled && !activated) {
            activated = true;
            OptimizerBridge.activate(MODULE,
                "Lycanites 常量药水效果与附近实体 Predicate 已进入稳定槽缓存");
        }
        return enabled;
    }

    @SuppressWarnings("unchecked")
    public static Predicate<Entity> nearbyPredicate(final Class<?> requiredType) {
        if (!OptimizerBridge.isEnabled(MODULE)) {
            return input -> requiredType == null || requiredType.isAssignableFrom(input.getClass());
        }
        useEffectCache();
        return requiredType == null ? ACCEPT_ALL : FILTERS.get(requiredType);
    }
}

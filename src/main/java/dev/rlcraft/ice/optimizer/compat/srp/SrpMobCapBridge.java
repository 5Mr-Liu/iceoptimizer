package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.world.World;

/** One invocation-local count of the categories requested by SRPMixins' tick handler. */
public final class SrpMobCapBridge {
    private static final int MODULE = OptimizationModule.SRP_MOB_CAP.ordinal();
    private static volatile boolean activated;

    private SrpMobCapBridge() { }

    public static Object compileCategories(Class<?>[] categories) {
        return new Categories(categories);
    }

    public static Object begin(World world, Object categories, int requested) {
        if (!OptimizerBridge.isEnabled(MODULE)) return null;
        Counts result = count(world.loadedEntityList, (Categories) categories, requested);
        if (!activated) {
            activated = true;
            OptimizerBridge.activate(MODULE, "SRPMixins 生物上限分类已合并为每次事件一次实体遍历");
        }
        return result;
    }

    public static int count(World world, Class<?> category, Object invocation, int index) {
        if (invocation == null) return world.countEntities(category);
        return ((Counts) invocation).values[index];
    }

    static Counts count(List<? extends Entity> entities, Class<?>[] categories, int requested) {
        return count(entities, new Categories(categories), requested);
    }

    static Counts count(List<? extends Entity> entities, Categories categories, int requested) {
        Counts result = new Counts(categories.types.length);
        for (Entity entity : entities) {
            // Exactly World.countEntities(Class)'s exclusion, shared across all categories.
            if (entity instanceof EntityLiving && ((EntityLiving) entity).isNoDespawnRequired()) continue;
            int mask = categories.membership.get(entity.getClass()).intValue() & requested;
            while (mask != 0) {
                result.values[Integer.numberOfTrailingZeros(mask)]++;
                mask &= mask - 1;
            }
        }
        return result;
    }

    static final class Counts {
        final int[] values;
        Counts(int size) {
            this.values = new int[size];
        }
    }

    /** Class inheritance is immutable; entity persistence is deliberately read every invocation. */
    static final class Categories {
        final Class<?>[] types;
        final ClassValue<Integer> membership = new ClassValue<Integer>() {
            @Override protected Integer computeValue(Class<?> actual) {
                int mask = 0;
                for (int i = 0; i < types.length; i++) {
                    if (types[i].isAssignableFrom(actual)) mask |= 1 << i;
                }
                return Integer.valueOf(mask);
            }
        };
        Categories(Class<?>[] types) { this.types = types.clone(); }
    }
}

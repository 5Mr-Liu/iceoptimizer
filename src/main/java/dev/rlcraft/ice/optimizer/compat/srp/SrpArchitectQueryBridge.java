package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.util.ClassInheritanceMultiMap;

/** Reads the section's existing mutation-maintained sparse bucket in its original order. */
public final class SrpArchitectQueryBridge {
    private static final ClassValue<Boolean> ARCHITECT = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return "com.dhanantry.scapeandrunparasites.entity.ai.misc.EntityPStationaryArchitect".equals(type.getName());
        }
    };
    private static final ClassValue<Boolean> STANDARD_COLLECTION = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return type == ClassInheritanceMultiMap.class
                || "pl.asie.foamfix.coremod.common.FoamyClassInheritanceMultiMap".equals(type.getName());
        }
    };
    private SrpArchitectQueryBridge() { }
    public static boolean isEnabled(Class<?> type) {
        return OptimizerBridge.isEnabled(OptimizationModule.SRP_ARCHITECT_QUERY.ordinal()) && ARCHITECT.get(type);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Iterator<?> iterator(ClassInheritanceMultiMap<?> section, Class<?> type) {
        // Unknown collection overrides keep their own virtual getByClass/iterator contracts.
        if (!STANDARD_COLLECTION.get(section.getClass())) return section.getByClass((Class) type).iterator();
        SrpArchitectBucketAccess access = (SrpArchitectBucketAccess) section;
        Class<?> resolved = access.ice$architectClass();
        if (resolved == null) {
            resolved = access.ice$resolveEntityClass(type);
            access.ice$architectClass(resolved);
        }
        List<?> values = (List<?>) access.ice$classBuckets().get(resolved);
        return values == null ? Collections.emptyIterator() : values.iterator();
    }
}

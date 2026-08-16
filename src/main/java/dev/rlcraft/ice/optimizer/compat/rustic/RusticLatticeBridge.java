package dev.rlcraft.ice.optimizer.compat.rustic;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;

/** Canonicalizes Rustic lattice connection states and their 64 bounding boxes. */
public final class RusticLatticeBridge {
    private static final String MODULE = "rustic-lattice-state";
    private static final int TABLE_SIZE = 2048;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final int MAX_PROBES = 16;
    private static final AtomicReferenceArray<Transition> TRANSITIONS =
        new AtomicReferenceArray<Transition>(TABLE_SIZE);
    private static final AxisAlignedBB[] BOUNDING_BOXES = buildBoundingBoxes();
    private static volatile boolean activated;

    private RusticLatticeBridge() {
    }

    public static boolean isEnabled() {
        return OptimizerBridge.isEnabled(MODULE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static IExtendedBlockState withProperty(IExtendedBlockState state,
                                                   IUnlistedProperty property,
                                                   Object value) {
        if (!OptimizerBridge.isEnabled(MODULE) || state == null || property == null
            || (value != Boolean.TRUE && value != Boolean.FALSE)) {
            return state.withProperty(property, value);
        }
        boolean booleanValue = value == Boolean.TRUE;
        int slot = mixedIdentity(state, property, booleanValue) & TABLE_MASK;
        for (int probe = 0; probe < MAX_PROBES; probe++, slot = (slot + 1) & TABLE_MASK) {
            Transition cached = TRANSITIONS.get(slot);
            if (cached != null) {
                if (cached.matches(state, property, booleanValue)) return cached.result;
                continue;
            }
            IExtendedBlockState result = state.withProperty(property, value);
            Transition candidate = new Transition(state, property, booleanValue, result);
            if (TRANSITIONS.compareAndSet(slot, null, candidate)) {
                activate();
                OptimizerBridge.success(MODULE);
                return result;
            }
            Transition raced = TRANSITIONS.get(slot);
            if (raced != null && raced.matches(state, property, booleanValue)) return raced.result;
        }
        return state.withProperty(property, value);
    }

    @SuppressWarnings("unchecked")
    public static AxisAlignedBB boundingBox(IExtendedBlockState state, Object[] connections) {
        if (!OptimizerBridge.isEnabled(MODULE) || state == null
            || connections == null || connections.length != 6) return null;
        try {
            int mask = 0;
            for (int i = 0; i < 6; i++) {
                if (!(connections[i] instanceof IUnlistedProperty)) return null;
                Object value = state.getValue((IUnlistedProperty<Object>) connections[i]);
                if (!(value instanceof Boolean)) return null;
                if (((Boolean) value).booleanValue()) mask |= 1 << i;
            }
            activate();
            OptimizerBridge.success(MODULE);
            return BOUNDING_BOXES[mask];
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return null;
        }
    }

    private static void activate() {
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE,
            "Rustic 栅栏六向扩展状态已规范化，64 种包围盒已预构建");
    }

    private static AxisAlignedBB[] buildBoundingBoxes() {
        AxisAlignedBB[] result = new AxisAlignedBB[64];
        for (int mask = 0; mask < result.length; mask++) {
            double minX = (mask & 1 << 4) != 0 ? 0.0D : 0.375D;
            double minY = (mask & 1) != 0 ? 0.0D : 0.375D;
            double minZ = (mask & 1 << 2) != 0 ? 0.0D : 0.375D;
            double maxX = (mask & 1 << 5) != 0 ? 1.0D : 0.625D;
            double maxY = (mask & 1 << 1) != 0 ? 1.0D : 0.625D;
            double maxZ = (mask & 1 << 3) != 0 ? 1.0D : 0.625D;
            result[mask] = new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return result;
    }

    private static int mixedIdentity(Object state, Object property, boolean value) {
        int hash = System.identityHashCode(state);
        hash = hash * 31 + System.identityHashCode(property);
        hash = hash * 31 + (value ? 1 : 0);
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash;
    }

    private static final class Transition {
        private final IExtendedBlockState source;
        private final IUnlistedProperty<?> property;
        private final boolean value;
        private final IExtendedBlockState result;

        private Transition(IExtendedBlockState source, IUnlistedProperty<?> property,
                           boolean value, IExtendedBlockState result) {
            this.source = source;
            this.property = property;
            this.value = value;
            this.result = result;
        }

        private boolean matches(IExtendedBlockState source, IUnlistedProperty<?> property,
                                boolean value) {
            return this.source == source && this.property == property && this.value == value;
        }
    }
}

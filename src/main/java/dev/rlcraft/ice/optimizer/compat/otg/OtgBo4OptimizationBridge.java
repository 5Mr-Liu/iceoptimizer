package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;

/** Pure BO4 layout helpers; no OTG implementation type is linked into the optimizer JAR. */
public final class OtgBo4OptimizationBridge {
    private static final ThreadLocal<ColumnOffsets> COLUMN_OFFSETS =
        new ThreadLocal<ColumnOffsets>() {
            @Override protected ColumnOffsets initialValue() { return new ColumnOffsets(); }
        };

    private OtgBo4OptimizationBridge() {
    }

    public static int columnBlockIndex(Object owner, short[][] columnSizes, int columnX, int columnZ) {
        ColumnOffsets cached = COLUMN_OFFSETS.get();
        if (cached.source != columnSizes) cached.reset(columnSizes);
        if (!cached.accelerated && enabled()) {
            try {
                cached.build(columnSizes);
                OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT).recordSuccess();
            } catch (LinkageError | RuntimeException error) {
                cached.accelerated = false;
                OptimizerRegistry.breaker(OptimizationModule.OTG_BO4_LAYOUT).recordFailure(error);
            }
        }
        if (cached.accelerated && columnX >= 0 && columnX < 16 && columnZ >= 0 && columnZ < 16) {
            return cached.offsets[(columnX << 4) | columnZ];
        }
        return originalColumnBlockIndex(columnSizes, columnX, columnZ);
    }

    static int originalColumnBlockIndex(short[][] columnSizes, int columnX, int columnZ) {
        int blockIndex = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (columnX == x && columnZ == z) return blockIndex;
                blockIndex += columnSizes[x][z];
            }
        }
        return blockIndex;
    }

    private static boolean enabled() {
        try {
            return OptimizerRegistry.isOperational(OptimizationModule.OTG_BO4_LAYOUT);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private static final class ColumnOffsets {
        private short[][] source;
        private final int[] offsets = new int[256];
        private boolean accelerated;

        private void reset(short[][] newSource) {
            source = newSource;
            accelerated = false;
        }

        private void build(short[][] sizes) {
            int offset = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    offsets[(x << 4) | z] = offset;
                    offset += sizes[x][z];
                }
            }
            accelerated = true;
        }
    }
}

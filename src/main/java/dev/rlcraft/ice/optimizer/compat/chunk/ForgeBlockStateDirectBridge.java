package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Exact Forge virtual calls used in place of OptiFine's Object[] reflection. */
public final class ForgeBlockStateDirectBridge {
    public static final int INT_FALLBACK = Integer.MIN_VALUE;
    public static final int BOOLEAN_FALLBACK = -1;
    private static final int MODULE =
        OptimizationModule.FORGE_BLOCKSTATE_DIRECT_CALLS.ordinal();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();

    private ForgeBlockStateDirectBridge() {
    }

    public static int tryStateLightValue(Object rawState, Object rawAccess, Object rawPosition) {
        if (!OptimizerBridge.isEnabled(MODULE)) return INT_FALLBACK;
        try {
            int value = ((IBlockState) rawState).getLightValue(
                (IBlockAccess) rawAccess, (BlockPos) rawPosition);
            activateOnce();
            return value;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return INT_FALLBACK;
        }
    }

    public static int tryBlockLightValue(Object rawState, Object rawAccess, Object rawPosition) {
        if (!OptimizerBridge.isEnabled(MODULE)) return INT_FALLBACK;
        try {
            IBlockState state = (IBlockState) rawState;
            int value = state.getBlock().getLightValue(
                state, (IBlockAccess) rawAccess, (BlockPos) rawPosition);
            activateOnce();
            return value;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return INT_FALLBACK;
        }
    }

    public static int tryDoesSideBlockRendering(Object rawState, Object rawAccess,
                                                Object rawPosition, Object rawSide) {
        if (!OptimizerBridge.isEnabled(MODULE)) return BOOLEAN_FALLBACK;
        try {
            IBlockState state = (IBlockState) rawState;
            boolean value = state.getBlock().doesSideBlockRendering(state,
                (IBlockAccess) rawAccess, (BlockPos) rawPosition, (EnumFacing) rawSide);
            activateOnce();
            return value ? 1 : 0;
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return BOOLEAN_FALLBACK;
        }
    }

    private static void activateOnce() {
        if (ACTIVATED.compareAndSet(false, true)) {
            OptimizerBridge.activate(MODULE,
                "OptiFine 区块光照与侧面遮挡已改为等价 Forge 虚调用，移除 Object[]/Method.invoke");
        }
    }
}

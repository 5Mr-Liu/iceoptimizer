package dev.rlcraft.ice.optimizer.compat.iceandfire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.Test;

public final class IceAndFirePathingBridgeTest {
    @Test
    public void cachesAreScopedToOneSynchronousSearch() {
        enable();
        final int[] rawCalls = new int[1];
        final int[] stateCalls = new int[1];
        final IBlockState state = state();
        IBlockAccess access = access(state, stateCalls);
        IceAndFireRawNodeAccessor processor = processor(rawCalls);
        try {
            IceAndFirePathingBridge.begin(processor, access);
            assertSame(PathNodeType.OPEN,
                IceAndFirePathingBridge.rawNodeType(processor, access, 11, 64, -9));
            assertSame(PathNodeType.OPEN,
                IceAndFirePathingBridge.rawNodeType(processor, access, 11, 64, -9));
            assertSame(state,
                IceAndFirePathingBridge.blockState(access, new BlockPos(11, 64, -9)));
            assertSame(state,
                IceAndFirePathingBridge.blockState(access, new BlockPos(11, 64, -9)));
            assertEquals(1, rawCalls[0]);
            assertEquals(1, stateCalls[0]);
            IceAndFirePathingBridge.end(processor);

            IceAndFirePathingBridge.begin(processor, access);
            IceAndFirePathingBridge.rawNodeType(processor, access, 11, 64, -9);
            IceAndFirePathingBridge.blockState(access, new BlockPos(11, 64, -9));
            assertEquals(2, rawCalls[0]);
            assertEquals(2, stateCalls[0]);
            IceAndFirePathingBridge.end(processor);

            IceAndFirePathingBridge.rawNodeType(processor, access, 11, 64, -9);
            IceAndFirePathingBridge.blockState(access, new BlockPos(11, 64, -9));
            assertEquals(3, rawCalls[0]);
            assertEquals(3, stateCalls[0]);
        } finally {
            IceAndFirePathingBridge.end(processor);
            disable();
        }
    }

    @Test
    public void nestedProcessorsRestoreTheOuterSearchByIdentity() {
        enable();
        final int[] outerCalls = new int[1];
        final int[] innerCalls = new int[1];
        final int[] stateCalls = new int[1];
        IBlockAccess access = access(state(), stateCalls);
        IceAndFireRawNodeAccessor outer = processor(outerCalls);
        IceAndFireRawNodeAccessor inner = processor(innerCalls);
        try {
            IceAndFirePathingBridge.begin(outer, access);
            IceAndFirePathingBridge.rawNodeType(outer, access, 1, 70, 2);
            IceAndFirePathingBridge.begin(inner, access);
            IceAndFirePathingBridge.rawNodeType(inner, access, 3, 70, 4);
            IceAndFirePathingBridge.rawNodeType(inner, access, 3, 70, 4);
            IceAndFirePathingBridge.end(inner);
            IceAndFirePathingBridge.rawNodeType(outer, access, 1, 70, 2);
            assertEquals(1, outerCalls[0]);
            assertEquals(1, innerCalls[0]);
        } finally {
            IceAndFirePathingBridge.end(inner);
            IceAndFirePathingBridge.end(outer);
            disable();
        }
    }

    @Test
    public void mismatchedWorldNeverUsesAnotherSearchCache() {
        enable();
        final int[] rawCalls = new int[1];
        final int[] firstStateCalls = new int[1];
        final int[] secondStateCalls = new int[1];
        IBlockAccess first = access(state(), firstStateCalls);
        IBlockAccess second = access(state(), secondStateCalls);
        IceAndFireRawNodeAccessor processor = processor(rawCalls);
        try {
            IceAndFirePathingBridge.begin(processor, first);
            IceAndFirePathingBridge.rawNodeType(processor, second, 5, 80, 6);
            IceAndFirePathingBridge.rawNodeType(processor, second, 5, 80, 6);
            IceAndFirePathingBridge.blockState(second, new BlockPos(5, 80, 6));
            IceAndFirePathingBridge.blockState(second, new BlockPos(5, 80, 6));
            assertEquals(2, rawCalls[0]);
            assertEquals(0, firstStateCalls[0]);
            assertEquals(2, secondStateCalls[0]);
        } finally {
            IceAndFirePathingBridge.end(processor);
            disable();
        }
    }

    @Test
    public void packedCoordinatesPreserveMinecraftSignedWorldBounds() {
        long origin = IceAndFirePathingBridge.packed(0, 0, 0);
        long minimum = IceAndFirePathingBridge.packed(-30000000, 0, -30000000);
        long maximum = IceAndFirePathingBridge.packed(30000000, 255, 30000000);
        long negativeY = IceAndFirePathingBridge.packed(0, -1, 0);
        assertNotEquals(origin, minimum);
        assertNotEquals(origin, maximum);
        assertNotEquals(minimum, maximum);
        assertNotEquals(origin, negativeY);
    }

    private static void enable() {
        OptimizerRegistry.breaker(OptimizationModule.ICEANDFIRE_PATH_NODE_CACHE)
            .configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.ICEANDFIRE_PATH_NODE_CACHE)
            .patchInstalled("ExperimentalWalkNodeProcessor", "test");
    }

    private static void disable() {
        OptimizerRegistry.breaker(OptimizationModule.ICEANDFIRE_PATH_NODE_CACHE)
            .configure(false, 3);
    }

    private static IceAndFireRawNodeAccessor processor(final int[] calls) {
        return new IceAndFireRawNodeAccessor() {
            @Override
            public PathNodeType ice$rawNodeType(IBlockAccess source, int x, int y, int z) {
                calls[0]++;
                return PathNodeType.OPEN;
            }
        };
    }

    private static IBlockAccess access(final IBlockState state, final int[] calls) {
        return proxy(IBlockAccess.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getBlockState".equals(method.getName())
                    || "func_180495_p".equals(method.getName())) {
                    calls[0]++;
                    return state;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static IBlockState state() {
        return proxy(IBlockState.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0.0F;
        if (type == Double.TYPE) return 0.0D;
        if (type == Character.TYPE) return (char) 0;
        return null;
    }
}

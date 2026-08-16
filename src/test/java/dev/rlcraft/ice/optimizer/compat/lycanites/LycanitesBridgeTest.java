package dev.rlcraft.ice.optimizer.compat.lycanites;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.ResourceLocation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.Test;

public class LycanitesBridgeTest {
    @Test
    public void pathCachesAreScopedToOneBeginEndLifecycleAndUsePackedCoordinates() {
        enable(OptimizationModule.LYCANITES_PATH_NODE_CACHE, "processor");
        final int[] rawCalls = new int[1];
        final int[] stateCalls = new int[1];
        final IBlockState state = proxy(IBlockState.class, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        });
        IBlockAccess access = proxy(IBlockAccess.class, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getBlockState".equals(method.getName()) || "func_180495_p".equals(method.getName())) {
                    stateCalls[0]++;
                    return state;
                }
                return defaultValue(method.getReturnType());
            }
        });
        LycanitesRawNodeAccessor processor = new LycanitesRawNodeAccessor() {
            @Override public PathNodeType ice$rawNodeType(IBlockAccess source, int x, int y, int z) {
                rawCalls[0]++;
                return PathNodeType.OPEN;
            }
        };
        try {
            LycanitesPathingBridge.begin(processor, access);
            assertSame(PathNodeType.OPEN, LycanitesPathingBridge.rawNodeType(processor, access, 11, 64, -9));
            assertSame(PathNodeType.OPEN, LycanitesPathingBridge.rawNodeType(processor, access, 11, 64, -9));
            assertSame(state, LycanitesPathingBridge.blockState(access, new BlockPos(11, 64, -9)));
            assertSame(state, LycanitesPathingBridge.blockState(access, new BlockPos(11, 64, -9)));
            assertEquals(1, rawCalls[0]);
            assertEquals(1, stateCalls[0]);
        } finally {
            LycanitesPathingBridge.end(processor);
        }

        LycanitesPathingBridge.rawNodeType(processor, access, 11, 64, -9);
        LycanitesPathingBridge.blockState(access, new BlockPos(11, 64, -9));
        assertEquals(2, rawCalls[0]);
        assertEquals(2, stateCalls[0]);
        OptimizerRegistry.breaker(OptimizationModule.LYCANITES_PATH_NODE_CACHE).configure(false, 3);
    }

    @Test
    public void registryBridgeUsesOneProbeWhenEnabledAndExactOriginalPathWhenDisabled() {
        CountingMap map = new CountingMap();
        Object value = new Object();
        map.put("fear", value);
        enable(OptimizationModule.LYCANITES_REGISTRY_LOOKUP, "manager");
        assertSame(value, LycanitesRegistryBridge.lookup(map, "FEAR"));
        assertEquals(0, map.containsCalls);
        assertEquals(1, map.getCalls);

        map.reset();
        OptimizerRegistry.breaker(OptimizationModule.LYCANITES_REGISTRY_LOOKUP).configure(false, 3);
        assertSame(value, LycanitesRegistryBridge.lookup(map, "FEAR"));
        assertEquals(1, map.containsCalls);
        assertEquals(1, map.getCalls);
    }

    @Test
    public void registryBridgeKeepsDregoraLookupCaseSensitive() {
        CountingMap map = new CountingMap();
        Object value = new Object();
        map.put("fear", value);
        enable(OptimizationModule.LYCANITES_REGISTRY_LOOKUP, "dregora-manager");
        assertSame(value, LycanitesRegistryBridge.lookupExact(map, "fear"));
        org.junit.Assert.assertNull(LycanitesRegistryBridge.lookupExact(map, "FEAR"));
        assertEquals(0, map.containsCalls);
        assertEquals(2, map.getCalls);

        map.reset();
        OptimizerRegistry.breaker(OptimizationModule.LYCANITES_REGISTRY_LOOKUP).configure(false, 3);
        org.junit.Assert.assertNull(LycanitesRegistryBridge.lookupExact(map, "FEAR"));
        assertEquals(1, map.containsCalls);
        assertEquals(0, map.getCalls);
    }

    @Test
    public void packedCoordinateKeyKeepsSignedExtremesDistinct() {
        long origin = LycanitesPathingBridge.packed(0, 0, 0);
        long negative = LycanitesPathingBridge.packed(-1, -1, -1);
        long positive = LycanitesPathingBridge.packed(1, 1, 1);
        org.junit.Assert.assertNotEquals(origin, negative);
        org.junit.Assert.assertNotEquals(origin, positive);
        org.junit.Assert.assertNotEquals(negative, positive);
    }

    @Test
    public void blockMembershipIndexTracksNormalListMutationsWithoutChangingSemantics() {
        enable(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP, "block-list");
        List<ResourceLocation> original = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 12; i++) original.add(new ResourceLocation("test", "block_" + i));
        try {
            List<ResourceLocation> tracked = LycanitesBlockMembershipBridge.track(original);
            org.junit.Assert.assertTrue(LycanitesBlockMembershipBridge.isIndexedForTest(tracked));
            ResourceLocation replacement = new ResourceLocation("test", "replacement");
            assertSame(original.get(3), tracked.get(3));
            org.junit.Assert.assertTrue(tracked.contains(original.get(8)));
            tracked.set(3, replacement);
            org.junit.Assert.assertTrue(tracked.contains(replacement));
            org.junit.Assert.assertFalse(tracked.contains(original.get(3)));
            tracked.remove(replacement);
            org.junit.Assert.assertFalse(tracked.contains(replacement));

            List<ResourceLocation> view = tracked.subList(0, 2);
            ResourceLocation viaView = new ResourceLocation("test", "via_view");
            view.set(0, viaView);
            org.junit.Assert.assertTrue(tracked.contains(viaView));
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP)
                .configure(false, 3);
        }
    }

    private static void enable(OptimizationModule module, String target) {
        OptimizerRegistry.breaker(module).configure(true, 3);
        OptimizerRegistry.breaker(module).patchInstalled(target, "test");
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

    private static final class CountingMap extends HashMap<String, Object> {
        private int containsCalls;
        private int getCalls;

        @Override public boolean containsKey(Object key) {
            containsCalls++;
            return super.containsKey(key);
        }

        @Override public Object get(Object key) {
            getCalls++;
            return super.get(key);
        }

        private void reset() {
            containsCalls = 0;
            getCalls = 0;
        }
    }
}

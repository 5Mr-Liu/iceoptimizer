package dev.rlcraft.ice.optimizer.compat.lycanites;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.junit.Test;
import sun.misc.Unsafe;

public final class LycanitesSpawnScanBridgeTest {
    @Test public void blockIdentityAndResourceMembershipShareMutationInvalidation() {
        Bootstrap.register();
        enable(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP);
        try {
            List<ResourceLocation> source = new ArrayList<ResourceLocation>();
            for (int i = 0; i < 12; i++) source.add(new ResourceLocation("test", "block_" + i));
            List<ResourceLocation> tracked = LycanitesBlockMembershipBridge.track(source);
            assertFalse(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.STONE));
            tracked.set(0, Blocks.STONE.getRegistryName());
            assertTrue(tracked.contains(Blocks.STONE.getRegistryName()));
            assertTrue(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.STONE));
            tracked.set(0, Blocks.DIRT.getRegistryName());
            assertTrue(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.DIRT));
            assertFalse(tracked.contains(Blocks.STONE.getRegistryName()));
            assertFalse(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.STONE));
            tracked.subList(0, 1).set(0, Blocks.STONE.getRegistryName());
            assertTrue(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.STONE));
            assertFalse(LycanitesBlockMembershipBridge.containsBlock(tracked, Blocks.DIRT));
        } finally { OptimizerRegistry.breaker(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP).configure(false, 3); }
    }
    @Test
    public void membershipIndexDoesNotDisableSpawnStateReuse() throws Exception {
        Bootstrap.register();
        enable(OptimizationModule.LYCANITES_SPAWN_SCAN);
        enable(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP);
        try {
            List<ResourceLocation> original = new ArrayList<ResourceLocation>();
            for (int i = 0; i < 12; i++) {
                original.add(new ResourceLocation("test", "block_" + i));
            }
            assertScanStateReused(original);
            List<ResourceLocation> indexed = LycanitesBlockMembershipBridge.track(original);
            assertTrue(LycanitesBlockMembershipBridge.isIndexedForTest(indexed));
            assertScanStateReused(indexed);
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.LYCANITES_SPAWN_SCAN).configure(false, 3);
            OptimizerRegistry.breaker(OptimizationModule.LYCANITES_BLOCK_MEMBERSHIP).configure(false, 3);
        }
    }

    @Test
    public void customMembershipSemanticsStillRequireTheOriginalRead() {
        List<ResourceLocation> custom = new ArrayList<ResourceLocation>() {
            @Override public boolean contains(Object value) { return false; }
        };
        assertFalse(LycanitesBlockMembershipBridge.hasStandardMembership(custom));
        assertFalse(LycanitesBlockMembershipBridge.hasStandardMembership(null));
    }

    private static void assertScanStateReused(final List<ResourceLocation> blockIds)
            throws Exception {
        LycanitesSpawnScanAccessor owner = new LycanitesSpawnScanAccessor() {
            @Override public boolean ice$spawnSurface() { return true; }
            @Override public boolean ice$spawnUnderground() { return true; }
            @Override public List<ResourceLocation> ice$spawnBlockIds() { return blockIds; }
        };
        Field field = LycanitesSpawnScanBridge.class.getDeclaredField("SCOPES");
        field.setAccessible(true);
        Object stack = ((ThreadLocal<?>) field.get(null)).get();
        Method push = stack.getClass().getDeclaredMethod("push", LycanitesSpawnScanAccessor.class,
            boolean.class);
        push.setAccessible(true);
        // Enter the same active scope installed by the transformed mod class.
        long token = ((Long) push.invoke(stack, owner, true)).longValue();
        try {
            Method current = stack.getClass().getDeclaredMethod("current");
            current.setAccessible(true);
            Object scope = current.invoke(stack);
            Method remember = scope.getClass().getDeclaredMethod("remember", World.class,
                BlockPos.class, IBlockState.class);
            remember.setAccessible(true);
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            World world = (World) unsafe.allocateInstance(WorldServer.class);
            BlockPos position = new BlockPos(12, 64, -9);
            IBlockState state = Blocks.STONE.getDefaultState();
            remember.invoke(scope, world, position, state);
            // An uninitialized world's original getBlockState cannot succeed;
            // this checks that production validation consumes the scanned state.
            assertSame(state, LycanitesSpawnScanBridge.validationState(world, position));
        } finally {
            LycanitesSpawnScanBridge.end(token);
        }
    }

    private static void enable(OptimizationModule module) {
        OptimizerRegistry.breaker(module).configure(true, 3);
        OptimizerRegistry.breaker(module).patchInstalled("test.spawn-scan", "test");
    }
}

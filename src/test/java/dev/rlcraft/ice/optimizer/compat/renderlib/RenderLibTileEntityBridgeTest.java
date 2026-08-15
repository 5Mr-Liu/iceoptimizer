package dev.rlcraft.ice.optimizer.compat.renderlib;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import org.junit.Test;
import sun.misc.Unsafe;

public class RenderLibTileEntityBridgeTest {
    @Test
    public void legacyAndHolderVariantsDeliverTheExactOriginalListShape() throws Exception {
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY)
            .patchInstalled("TileEntityUtil", "test");
        try {
            List<TileEntity> loaded = new ArrayList<TileEntity>();
            List<TileEntity> holder = new ArrayList<TileEntity>();
            List<TileEntity> pending = new ArrayList<TileEntity>();
            HolderWorld world = allocate(loaded, holder, pending);
            final AtomicReference<List<TileEntity>> accepted = new AtomicReference<List<TileEntity>>();
            Consumer<List<TileEntity>> consumer = new Consumer<List<TileEntity>>() {
                @Override public void accept(List<TileEntity> value) { accepted.set(value); }
            };

            assertTrue(RenderLibTileEntityBridge.tryProcess(world, consumer));
            assertSame(loaded, accepted.get());
            assertFalse(processing(world));

            accepted.set(null);
            assertTrue(RenderLibTileEntityBridge.tryProcessHolder(world, consumer));
            assertSame(holder, accepted.get());
            assertFalse(processing(world));
        } finally {
            OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).configure(false, 3);
        }
    }

    private static HolderWorld allocate(List<TileEntity> loaded, List<TileEntity> holder,
                                        List<TileEntity> pending) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        HolderWorld world = (HolderWorld) unsafe.allocateInstance(HolderWorld.class);
        world.holderTiles = holder;
        set(World.class, world, "loadedTileEntityList", loaded);
        set(World.class, world, "addedTileEntityList", pending);
        Field processing = World.class.getDeclaredField("processingLoadedTiles");
        processing.setAccessible(true);
        processing.setBoolean(world, false);
        return world;
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean processing(World world) throws Exception {
        Field field = World.class.getDeclaredField("processingLoadedTiles");
        field.setAccessible(true);
        return field.getBoolean(world);
    }

    private static final class HolderWorld extends World {
        private List<TileEntity> holderTiles;

        private HolderWorld() {
            super(null, null, null, null, false);
        }

        public List<TileEntity> getTileEntities() {
            return holderTiles;
        }

        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return false; }
    }
}

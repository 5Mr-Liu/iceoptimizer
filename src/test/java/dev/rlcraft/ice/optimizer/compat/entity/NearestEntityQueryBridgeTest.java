package dev.rlcraft.ice.optimizer.compat.entity;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.junit.BeforeClass;
import org.junit.Test;

public class NearestEntityQueryBridgeTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void preservesRadiusSectionsLastTieAndMatchesOriginalCompleteListSelection() throws Exception {
        EntityQueryTestWorld world = EntityQueryTestWorld.create();
        TestEntity origin = entity(world, 0, 62, 0, 1);
        TestEntity first = entity(world, -1, 62, 0, 1);
        TestEntity last = entity(world, 1, 62, 0, 1);
        add(world, first); add(world, last); add(world, origin);
        AxisAlignedBB box = new AxisAlignedBB(-5, 60, -5, 5, 66, 5);
        assertSame(last, NearestEntityQueryBridge.nearest(world, TestEntity.class, box, origin, 2));
        Random random = new Random(99621L);
        for (int i = 0; i < 200; i++) add(world, entity(world, random.nextDouble() * 128 - 64,
            random.nextDouble() * 100, random.nextDouble() * 128 - 64, i % 17 == 0 ? 25 : 1));
        for (int i = 0; i < 100; i++) {
            double x = random.nextDouble() * 70 - 35, z = random.nextDouble() * 70 - 35;
            box = new AxisAlignedBB(x, 25, z, x + 15, 82, z + 15);
            assertSame(original(world, TestEntity.class, box, origin, 32),
                NearestEntityQueryBridge.nearest(world, TestEntity.class, box, origin, 32));
        }
    }

    @Test public void finishesAllQueryCallbacksBeforeDistanceSelectionAndReleasesOnException() throws Exception {
        EntityQueryTestWorld world = EntityQueryTestWorld.create();
        List<String> calls = new ArrayList<String>();
        ObservedEntity origin = EntityQueryTestWorld.allocate(ObservedEntity.class);
        origin.name = "origin"; origin.calls = calls;
        origin.posY = 62;
        ObservedEntity first = observed("first", calls, -1), second = observed("second", calls, 1);
        add(world, first); add(world, second);
        AxisAlignedBB box = new AxisAlignedBB(-5, 60, -5, 5, 66, 5);
        assertSame(second, NearestEntityQueryBridge.nearest(world, ObservedEntity.class, box, origin, 2));
        assertEquals(java.util.Arrays.asList("box:first", "box:second", "distance:first", "distance:second"), calls);
        calls.clear();
        origin.fail = true;
        try { NearestEntityQueryBridge.nearest(world, ObservedEntity.class, box, origin, 2); fail(); }
        catch (IllegalStateException expected) { }
        origin.fail = false;
        calls.clear();
        assertSame(second, NearestEntityQueryBridge.nearest(world, ObservedEntity.class, box, origin, 2));
        assertEquals(4, calls.size());
    }

    private static ObservedEntity observed(String name, List<String> calls, double x) throws Exception {
        ObservedEntity entity = EntityQueryTestWorld.allocate(ObservedEntity.class);
        entity.name = name; entity.calls = calls;
        entity.posX = x; entity.posY = 62;
        entity.setEntityBoundingBox(new AxisAlignedBB(x - .2, 62, -.2, x + .2, 63, .2));
        return entity;
    }

    private static <T extends Entity> T original(World world, Class<T> type, AxisAlignedBB box, T origin, double radius) {
        List<T> all = new ArrayList<T>();
        for (int x = MathHelper.floor(box.minX - radius) >> 4; x <= MathHelper.floor(box.maxX + radius) >> 4; x++)
            for (int z = MathHelper.floor(box.minZ - radius) >> 4; z <= MathHelper.floor(box.maxZ + radius) >> 4; z++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                if (chunk == null || box.maxY < 0 || box.minY >= 256) continue;
                for (int y = Math.max(MathHelper.floor(box.minY - radius) >> 4, 0);
                     y <= Math.min(MathHelper.floor(box.maxY + radius) >> 4, 15); y++)
                    for (T entity : chunk.getEntityLists()[y].getByClass(type))
                        if (entity.getEntityBoundingBox().intersects(box) && EntitySelectors.NOT_SPECTATING.apply(entity)) all.add(entity);
            }
        T result = null;
        double distance = Double.MAX_VALUE;
        for (T entity : all) if (entity != origin) {
            double next = origin.getDistanceSq(entity);
            if (next <= distance) { distance = next; result = entity; }
        }
        return result;
    }
    private static TestEntity entity(World world, double x, double y, double z, double size) throws Exception {
        TestEntity entity = EntityQueryTestWorld.allocate(TestEntity.class);
        entity.posX = x; entity.posY = y; entity.posZ = z;
        entity.setEntityBoundingBox(new AxisAlignedBB(x - size, y, z - size, x + size, y + size, z + size));
        return entity;
    }
    private static void add(World world, Entity entity) {
        world.getChunk(MathHelper.floor(entity.posX) >> 4, MathHelper.floor(entity.posZ) >> 4)
            .getEntityLists()[MathHelper.clamp(MathHelper.floor(entity.posY) >> 4, 0, 15)].add(entity);
    }
    private static class TestEntity extends Entity {
        TestEntity(World world) { super(world); }
        @Override protected void entityInit() { }
        @Override protected void readEntityFromNBT(NBTTagCompound tag) { }
        @Override protected void writeEntityToNBT(NBTTagCompound tag) { }
    }
    private static class ObservedEntity extends TestEntity {
        String name;
        List<String> calls;
        boolean fail;
        ObservedEntity(World world) { super(world); }
        @Override public AxisAlignedBB getEntityBoundingBox() { calls.add("box:" + name); return super.getEntityBoundingBox(); }
        @Override public double getDistanceSq(Entity entity) {
            calls.add("distance:" + ((ObservedEntity) entity).name);
            if (fail) throw new IllegalStateException("fixture");
            return super.getDistanceSq(entity);
        }
    }
}

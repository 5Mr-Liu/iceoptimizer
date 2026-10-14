package dev.rlcraft.ice.optimizer.compat.srp;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.world.World;
import org.junit.Test;
import sun.misc.Unsafe;

public final class SrpMobCapBridgeTest {
    @Test public void countsSubclassOverlapsExcludesPersistentAndDoesNotReuseAcrossCalls() throws Exception {
        TestLiving living = allocate(TestLiving.class);
        ChildLiving child = allocate(ChildLiving.class);
        TestLiving persistent = allocate(TestLiving.class);
        persistent.persistent = true;
        List<Entity> entities = Arrays.<Entity>asList(living, child, persistent);
        Class<?>[] categories = { Entity.class, TestLiving.class, ChildLiving.class, String.class };
        SrpMobCapBridge.Categories compiled = (SrpMobCapBridge.Categories)
            SrpMobCapBridge.compileCategories(categories);
        SrpMobCapBridge.Counts first = SrpMobCapBridge.count(entities, compiled, 15);
        assertArrayEquals(new int[] { 2, 2, 1, 0 }, first.values);
        for (int i = 0; i < categories.length; i++) {
            assertEquals(first.values[i], SrpMobCapBridge.count(null, categories[i], first, i));
        }
        assertEquals("persistence queried once per entity", 1, living.checks);
        assertEquals(1, child.checks);
        assertEquals(1, persistent.checks);
        assertArrayEquals(new int[] { 0, 2, 0, 0 },
            SrpMobCapBridge.count(entities, compiled, 2).values);
        living.persistent = true;
        assertArrayEquals(new int[] { 1, 1, 1, 0 },
            SrpMobCapBridge.count(entities, compiled, 15).values);
        assertArrayEquals("previous invocation snapshot stays local", new int[] { 2, 2, 1, 0 }, first.values);
        categories[0] = String.class;
        assertArrayEquals("category publication owns its definition", new int[] { 1, 1, 1, 0 },
            SrpMobCapBridge.count(entities, compiled, 15).values);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    public static class TestLiving extends EntityLiving {
        boolean persistent;
        int checks;
        public TestLiving(World world) { super(world); }
        @Override public boolean isNoDespawnRequired() { checks++; return persistent; }
    }

    public static class ChildLiving extends TestLiving {
        public ChildLiving(World world) { super(world); }
    }
}

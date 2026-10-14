package dev.rlcraft.ice.optimizer.compat.events;

import static org.junit.Assert.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import net.minecraft.world.chunk.Chunk;
import org.junit.Test;
import sun.misc.Unsafe;

public class TrackedLoadedChunkMapTest {
    @Test public void mutationsAndRehashPreserveOriginalOrderAndImmutableSnapshots() throws Exception {
        TrackedLoadedChunkMap actual = new TrackedLoadedChunkMap();
        Long2ObjectOpenHashMap<Chunk> expected = new Long2ObjectOpenHashMap<Chunk>();
        Chunk[] objects = new Chunk[80];
        for (int i = 0; i < objects.length; i++) objects[i] = chunk();
        for (int i = 0; i < objects.length; i++) {
            actual.put(i * 13L, objects[i]); expected.put(i * 13L, objects[i]);
            assertArrayEquals(expected.values().toArray(), snapshot(actual));
        }
        Chunk[] old = snapshot(actual), content = old.clone();
        actual.remove(13L); expected.remove(13L);
        assertArrayEquals(content, old);
        assertArrayEquals(expected.values().toArray(), snapshot(actual));
        assertSame(snapshot(actual), snapshot(actual));
        actual.trim(); expected.trim();
        assertArrayEquals(expected.values().toArray(), snapshot(actual));
        TrackedLoadedChunkMap clone = actual.clone();
        assertNotSame(snapshot(actual), snapshot(clone));
        clone.clear(); assertFalse(actual.isEmpty());
    }
    @Test public void allMutableViewsInvalidateThePublishedSnapshot() throws Exception {
        TrackedLoadedChunkMap map = new TrackedLoadedChunkMap();
        Chunk a = chunk(), b = chunk(), c = chunk();
        map.put(1, a); map.put(2, b); map.put(3, c);
        Chunk[] first = snapshot(map);
        map.values().remove(a);
        assertNotSame(first, snapshot(map)); assertEquals(2, snapshot(map).length);
        first = snapshot(map);
        map.keySet().remove(2);
        assertNotSame(first, snapshot(map)); assertArrayEquals(new Chunk[] { c }, snapshot(map));
        first = snapshot(map);
        map.long2ObjectEntrySet().iterator().next().setValue(b);
        assertNotSame(first, snapshot(map)); assertArrayEquals(new Chunk[] { b }, snapshot(map));
        first = snapshot(map);
        map.replaceAll((key, value) -> a);
        assertNotSame(first, snapshot(map)); assertArrayEquals(new Chunk[] { a }, snapshot(map));
        first = snapshot(map);
        map.compute(3L, (key, value) -> c);
        assertNotSame(first, snapshot(map)); assertArrayEquals(new Chunk[] { c }, snapshot(map));
        map.values().removeAll(Collections.singleton(c)); assertEquals(0, snapshot(map).length);
        map.put(4, b);
        it.unimi.dsi.fastutil.objects.ObjectIterator<?> iterator = map.long2ObjectEntrySet().fastIterator();
        iterator.next(); iterator.remove(); assertEquals(0, snapshot(map).length);
    }
    private static Chunk[] snapshot(TrackedLoadedChunkMap map) {
        return ((TrackedLoadedChunkMap.Values) map.values()).orderedSnapshot(map.size());
    }
    private static Chunk chunk() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (Chunk) ((Unsafe) field.get(null)).allocateInstance(Chunk.class);
    }
}

package dev.rlcraft.ice.optimizer.compat.sound;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class SoundSearchBridgeTest {
    @Test public void randomizedFloodFillKeepsOriginalRemovalOrderAndMembership() {
        SoundSearchBridge.Frontier indexed = new SoundSearchBridge.Frontier();
        LinkedList<Object> original = new LinkedList<Object>(); Random random = new Random(8435L);
        for (int i = 0; i < 20000; i++) {
            if (!original.isEmpty() && random.nextBoolean()) {
                int index = random.nextInt(original.size()); assertSame(original.remove(index), indexed.remove(index));
            } else {
                Position p = new Position(random.nextInt(64) - 32, random.nextInt(16), random.nextInt(64) - 32);
                assertEquals(original.contains(p), indexed.contains(p));
                if (!original.contains(p)) { original.add(p); indexed.add(p); }
            }
            assertEquals(original.size(), indexed.size());
            for (Object p : original) assertTrue(indexed.contains(p));
            assertEquals(original, indexed);
        }
    }
    @Test public void invocationBuffersAreReusedClearedAndIsolatedAcrossNestedCalls() {
        int a = SoundSearchBridge.begin(); SoundSearchBridge.Frontier outer = SoundSearchBridge.frontier();
        outer.add(new Position(1, 2, 3));
        int b = SoundSearchBridge.begin(); SoundSearchBridge.Frontier inner = SoundSearchBridge.frontier();
        assertNotSame(outer, inner); inner.add(new Position(4, 5, 6));
        SoundSearchBridge.end(b); assertTrue(inner.isEmpty()); assertEquals(1, outer.size());
        SoundSearchBridge.end(a); assertTrue(outer.isEmpty());
        a = SoundSearchBridge.begin(); assertSame(outer, SoundSearchBridge.frontier()); SoundSearchBridge.end(a);
    }
    private static final class Position implements SoundPositionAccess {
        final float x, y, z; Position(float x, float y, float z) { this.x=x;this.y=y;this.z=z; }
        public float ice$soundX() { return x; } public float ice$soundY() { return y; } public float ice$soundZ() { return z; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Position)) return false; Position p=(Position)value;
            return Math.abs(x-p.x)<0.1F && Math.abs(y-p.y)<0.1F && Math.abs(z-p.z)<0.1F;
        }
    }
}

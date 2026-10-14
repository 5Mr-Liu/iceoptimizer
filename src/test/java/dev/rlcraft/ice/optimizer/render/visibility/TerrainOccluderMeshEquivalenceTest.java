package dev.rlcraft.ice.optimizer.render.visibility;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;

public final class TerrainOccluderMeshEquivalenceTest {
    @Test public void fixedPointMergingPreservesEveryRectangleAndItsFinalOrder() {
        Random random = new Random(0xC0111DEL);
        for (int scene = 0; scene < 1000; scene++) {
            int count = 1 + random.nextInt(540);
            ByteBuffer bytes = ByteBuffer.allocate(count * 112).order(ByteOrder.nativeOrder());
            for (int q = 0; q < count; q++) {
                int axis = random.nextInt(3);
                float plane = random.nextInt(4) - 2;
                float u = random.nextInt(12), v = random.nextInt(12);
                float du = 1 + random.nextInt(3), dv = 1 + random.nextInt(3);
                boolean reversed = random.nextBoolean();
                for (int vertex = 0; vertex < 4; vertex++) {
                    int corner = reversed ? (4 - vertex) & 3 : vertex;
                    for (int component = 0; component < 3; component++) {
                        float position = component == axis ? plane
                            : component == (axis + 1) % 3 ? u + (corner == 1 || corner == 2 ? du : 0)
                            : v + (corner >= 2 ? dv : 0);
                        bytes.putFloat(position);
                    }
                    bytes.putInt(q % 13 == 0 ? 0x7fffffff : -1);
                    bytes.putFloat(corner == 1 || corner == 2 ? .9F : .1F);
                    bytes.putFloat(corner >= 2 ? .9F : .1F);
                    bytes.putInt(0);
                }
            }
            bytes.flip();
            float scale = (scene & 1) == 0 ? 1F : 1.000001F;
            float translation = 8F * scale - 8F;
            TerrainOccluderMesh actual = TerrainOccluderMesh.capture(bytes, 28, count * 4,
                (a,b,c,d) -> true, scale, translation);
            ReferenceTerrainOccluderMesh expected = ReferenceTerrainOccluderMesh.capture(bytes, 28, count * 4,
                (a,b,c,d) -> true, scale, translation);
            assertArrayEquals("scene=" + scene, expected.quads, actual.quads, 0F);
            assertEquals(expected.minX, actual.minX, 0D); assertEquals(expected.maxX, actual.maxX, 0D);
            assertEquals(expected.minY, actual.minY, 0D); assertEquals(expected.maxY, actual.maxY, 0D);
            assertEquals(expected.minZ, actual.minZ, 0D); assertEquals(expected.maxZ, actual.maxZ, 0D);
            assertEquals(0, bytes.position());
        }
    }
}

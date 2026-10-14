package dev.rlcraft.ice.optimizer.render.entity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModelPosePackingTest {
    @Test public void wideningPreservesEveryPackedByteAcrossOverlappingVertices() {
        Random random = new Random(112);
        for (int vertices : new int[] {1, 4, 96, 512}) {
            byte[] source = new byte[vertices * 24]; random.nextBytes(source);
            ByteBuffer target = ByteBuffer.allocate(19 + vertices * 28 + 7).order(ByteOrder.nativeOrder());
            for (int i = 0; i < target.capacity(); i++) target.put(i, (byte) 0x6a);
            target.position(19); target.put(source);
            LwjglModelPoseBatch.expandVertices(target, 19, vertices, 127);
            for (int vertex = 0; vertex < vertices; vertex++) {
                for (int b = 0; b < 24; b++) assertEquals(source[vertex * 24 + b], target.get(19 + vertex * 28 + b));
                assertEquals(127F, target.getFloat(19 + vertex * 28 + 24), 0F);
            }
            assertEquals((byte) 0x6a, target.get(18));
            assertEquals((byte) 0x6a, target.get(19 + vertices * 28));
        }
    }
}

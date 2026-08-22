package dev.rlcraft.ice.optimizer.render.entity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.List;
import org.junit.Test;

public final class DrawPacketStreamTest {
    @Test
    public void batchesOnlyConsecutiveEqualStateAndNeverReordersTransparency() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderStateKey firstState = state(1);
        RenderStateKey secondState = state(2);
        DrawPacketStream stream = new DrawPacketStream(16);
        assertTrue(stream.record(packet(1L, 10L, firstState, stamp, true)));
        assertTrue(stream.record(packet(2L, 11L, firstState, stamp, true)));
        assertTrue(stream.record(packet(3L, 12L, secondState, stamp, true)));
        assertTrue(stream.record(packet(4L, 13L, firstState, stamp, true)));
        List<DrawPacketBatch> batches = stream.flushAtBarrier();
        assertEquals(3, batches.size());
        assertEquals(2, batches.get(0).getPackets().size());
        assertEquals(10L, batches.get(0).getPackets().get(0).getSequence());
        assertEquals(11L, batches.get(0).getPackets().get(1).getSequence());
        assertEquals(13L, batches.get(2).getPackets().get(0).getSequence());
        assertFalse(stream.record(packet(5L, 9L, firstState, stamp, true))
            && stream.record(packet(6L, 8L, firstState, stamp, true)));
    }

    @Test
    public void rollbackIsIdentitySafeAndSeparateAlphaFactorsSplitBatches() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        RenderStateKey rgbAlpha = new RenderStateKey(RenderPass.ENTITY_PASS_0,
            0, 1, 2, true, 770, 771, 1, 771,
            true, false, true, 15);
        RenderStateKey separateAlpha = new RenderStateKey(RenderPass.ENTITY_PASS_0,
            0, 1, 2, true, 770, 771, 770, 771,
            true, false, true, 15);
        DrawPacket first = packet(1L, 1L, rgbAlpha, stamp, true);
        DrawPacket second = packet(2L, 2L, separateAlpha, stamp, true);
        DrawPacketStream stream = new DrawPacketStream(16);
        assertTrue(stream.record(first));
        assertFalse(stream.rollbackLast(second));
        assertTrue(stream.record(second));
        assertEquals(2, stream.flushAtBarrier().size());

        assertTrue(stream.record(first));
        assertTrue(stream.rollbackLast(first));
        assertEquals(0, stream.size());
        stream.discardAtBarrier();
        assertEquals(2L, stream.getBarriers());
    }

    private static DrawPacket packet(long mesh, long sequence, RenderStateKey state,
                                     FrameStamp stamp, boolean transparent) {
        float[] matrix = new float[16];
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1.0F;
        return new DrawPacket(mesh, matrix, new float[0], state, stamp,
            1L, sequence, transparent);
    }

    private static RenderStateKey state(int texture) {
        return new RenderStateKey(RenderPass.ENTITY_PASS_0, 0, texture, 2,
            true, 770, 771, true, false, true, 15);
    }
}

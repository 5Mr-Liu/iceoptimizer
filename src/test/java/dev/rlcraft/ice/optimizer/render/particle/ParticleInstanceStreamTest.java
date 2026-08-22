package dev.rlcraft.ice.optimizer.render.particle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class ParticleInstanceStreamTest {
    @Test
    public void preservesListOrderAndFlushesAtEveryStateChange() {
        ParticleState first = new ParticleState(0, 1, 0, 770, 771, false, false, 1L);
        ParticleState second = new ParticleState(1, 2, 0, 770, 771, true, true, 1L);
        ParticleInstanceStream stream = new ParticleInstanceStream(64);
        assertTrue(stream.record(first, instance(0L)));
        assertTrue(stream.record(first, instance(1L)));
        assertTrue(stream.record(second, instance(2L)));
        List<ParticleBatch> batches = stream.flush();
        assertEquals(2, batches.size());
        assertEquals(0L, batches.get(0).getInstances().get(0).getSequence());
        assertEquals(1L, batches.get(0).getInstances().get(1).getSequence());
        assertEquals(2L, batches.get(1).getInstances().get(0).getSequence());
    }

    @Test(expected = IllegalArgumentException.class)
    public void fbpAdapterRejectsOverlappingOriginalFlushRanges() {
        new FbpParticlePacket(new float[32], 4, new int[] { 0, 2 },
            new int[] { 4, 4 }, new ParticleState(0, 1, 0, 1, 0,
                false, false, 0L), 0L);
    }

    private static ParticleInstance instance(long sequence) {
        return new ParticleInstance(1, 2, 3, 1, 0, -1, 0,
            0, 0, 1, 1, sequence);
    }
}

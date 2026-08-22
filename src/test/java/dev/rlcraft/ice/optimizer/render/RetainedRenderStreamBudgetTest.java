package dev.rlcraft.ice.optimizer.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.entity.DrawPacketStream;
import dev.rlcraft.ice.optimizer.render.hud.HudVertexStream;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.texture.AnimationTextureCommandQueue;
import dev.rlcraft.ice.optimizer.render.texture.TextureUploadStream;
import dev.rlcraft.ice.optimizer.render.visibility.PrimitiveSectionGrid;
import org.junit.Test;

public final class RetainedRenderStreamBudgetTest {
    @Test
    public void everyFixedBackingArrayIsChargedAndReleased() {
        CacheBudget budget = new CacheBudget(32L * 1024L * 1024L, 1L, 1L);

        ParticleInstanceStream particles = new ParticleInstanceStream(64, budget);
        assertEquals(ParticleInstanceStream.heapBytesForCapacity(64),
            budget.snapshot().getHeapUsed());
        particles.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());

        DrawPacketStream packets = new DrawPacketStream(16, budget);
        assertEquals(DrawPacketStream.heapBytesForCapacity(16),
            budget.snapshot().getHeapUsed());
        packets.close();

        HudVertexStream hud = new HudVertexStream(64, budget);
        assertEquals(HudVertexStream.heapBytesForCapacity(64),
            budget.snapshot().getHeapUsed());
        hud.close();

        TextureUploadStream uploads = new TextureUploadStream(16, 4096L, budget);
        assertEquals(TextureUploadStream.heapBytesForCapacity(16),
            budget.snapshot().getHeapUsed());
        uploads.close();

        AnimationTextureCommandQueue animation =
            new AnimationTextureCommandQueue(16, 4096L, budget);
        assertEquals(AnimationTextureCommandQueue.heapBytesForCapacity(16),
            budget.snapshot().getHeapUsed());
        animation.close();

        PrimitiveSectionGrid grid = new PrimitiveSectionGrid(0, 0, 0,
            4, 2, 4, budget);
        assertEquals(PrimitiveSectionGrid.heapBytesForCells(32),
            budget.snapshot().getHeapUsed());
        grid.close();
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }

    @Test
    public void budgetFailureDoesNotPublishAnUntrackedArray() {
        CacheBudget budget = new CacheBudget(1L, 1L, 1L);
        try {
            new ParticleInstanceStream(64, budget);
            fail("expected retained Heap rejection");
        } catch (IllegalStateException expected) {
            assertEquals("particle instance stream Heap budget exhausted",
                expected.getMessage());
        }
        assertEquals(0L, budget.snapshot().getHeapUsed());
    }
}

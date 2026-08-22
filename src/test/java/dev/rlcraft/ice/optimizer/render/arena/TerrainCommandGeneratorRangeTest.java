package dev.rlcraft.ice.optimizer.render.arena;

import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.terrain.ChunkMeshPayload;
import dev.rlcraft.ice.optimizer.render.terrain.MaterialSegment;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainCommandGenerator;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainLayer;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainMesh;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import org.junit.Test;

public final class TerrainCommandGeneratorRangeTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsForgedNegativeArenaOffsetBeforeNarrowing() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        ChunkMeshPayload payload = new ChunkMeshPayload(1L, stamp,
            TerrainLayer.SOLID, new byte[28], 1, 28, 0, 0, 0,
            new double[] { 0, 0, 0, 16, 16, 16 },
            new MaterialSegment[] { new MaterialSegment(0, 1, 0) }, null, 0L);
        ArenaRange forged = new ArenaRange(1L, 1L, -28L, 28L, 1L);
        new TerrainCommandGenerator().emit(new TerrainMesh(payload, forged),
            TerrainLayer.SOLID, 0, new TerrainCommandGenerator.CommandSink() {
                @Override public void accept(int count, int first, int baseInstance,
                                             int originX, int originY, int originZ,
                                             long sequence, long checksum) { }
            });
    }
}

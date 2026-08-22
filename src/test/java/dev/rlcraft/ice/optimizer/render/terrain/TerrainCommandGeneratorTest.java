package dev.rlcraft.ice.optimizer.render.terrain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.util.Arrays;
import org.junit.Test;

public final class TerrainCommandGeneratorTest {
    @Test
    public void payloadIsImmutableAndCommandsRetainExactTranslucentOrder() {
        ClientEpochs epochs = new ClientEpochs();
        FrameStamp stamp = new FrameStamp(1L, 1L, epochs.snapshot());
        byte[] firstBytes = new byte[32];
        firstBytes[0] = 7;
        ChunkMeshPayload first = payload(1L, stamp, firstBytes, 9L, 0);
        firstBytes[0] = 99;
        assertEquals(7, first.copyVertexData()[0]);
        assertArrayEquals(new int[] { 0 }, first.getTransparentQuadOrder());
        assertFalse(first.matchesGeneration(new FrameStamp(2L, 2L,
            invalidatedResources(epochs))));

        ChunkMeshPayload second = payload(2L, stamp, new byte[32], 3L, 16);
        GpuArenaAllocator arena = new GpuArenaAllocator(64L, 128L, 16L, 1L);
        ArenaRange firstRange = arena.allocate(first.getByteCount());
        ArenaRange secondRange = arena.allocate(second.getByteCount());
        TerrainCommandBatch batch = new TerrainCommandGenerator().generate(Arrays.asList(
            new TerrainMesh(first, firstRange), new TerrainMesh(second, secondRange)),
            TerrainLayer.TRANSLUCENT);
        assertEquals(2, batch.getCommands().size());
        assertEquals(9L, batch.getCommands().get(0).getSequence());
        assertEquals(3L, batch.getCommands().get(1).getSequence());
        assertEquals(0, batch.getCommands().get(0).getBaseInstance());
        assertEquals(1, batch.getCommands().get(1).getBaseInstance());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidTransparentPermutation() {
        ClientEpochs epochs = new ClientEpochs();
        new ChunkMeshPayload(1L, new FrameStamp(1L, 1L, epochs.snapshot()),
            TerrainLayer.TRANSLUCENT, new byte[64], 8, 8, 0, 0, 0,
            new double[] { 0, 0, 0, 1, 1, 1 }, null,
            new int[] { 0, 0 }, 0L);
    }

    @Test
    public void productionMetadataDoesNotRetainUploadedVertexPayload() {
        GpuArenaAllocator allocator = new GpuArenaAllocator(128L, 128L, 4L, 1L);
        allocator.allocate(56L);
        ArenaRange range = allocator.allocate(28L);
        TerrainMesh mesh = TerrainMesh.metadataOnly(TerrainLayer.SOLID, 1, 28,
            -16, 32, 48, 7L, 11L, range);
        assertNull(mesh.getPayload());
        final long[] command = new long[8];
        new TerrainCommandGenerator().emit(mesh, TerrainLayer.SOLID, 3,
            new TerrainCommandGenerator.CommandSink() {
                @Override public void accept(int count, int first,
                                             int baseInstance, int originX,
                                             int originY, int originZ,
                                             long sequence, long checksum) {
                    command[0] = count;
                    command[1] = first;
                    command[2] = baseInstance;
                    command[3] = originX;
                    command[4] = originY;
                    command[5] = originZ;
                    command[6] = sequence;
                    command[7] = checksum;
                }
            });
        assertArrayEquals(new long[] { 1L, 2L, 3L, -16L, 32L, 48L, 7L, 11L },
            command);
    }

    private static ChunkMeshPayload payload(long key, FrameStamp stamp, byte[] bytes,
                                            long sequence, int chunkX) {
        return new ChunkMeshPayload(key, stamp, TerrainLayer.TRANSLUCENT,
            bytes, 4, 8, chunkX, 0, 0,
            new double[] { chunkX, 0, 0, chunkX + 16, 16, 16 },
            new MaterialSegment[] { new MaterialSegment(0, 4, 1) },
            new int[] { 0 }, sequence);
    }

    private static dev.rlcraft.ice.optimizer.runtime.EpochToken invalidatedResources(
        ClientEpochs epochs) {
        epochs.invalidateResources();
        return epochs.snapshot();
    }
}

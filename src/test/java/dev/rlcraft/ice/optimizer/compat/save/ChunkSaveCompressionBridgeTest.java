package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public final class ChunkSaveCompressionBridgeTest {
    @Test
    public void reusableDeflaterProducesReadableEquivalentNbtAcrossRuns() throws Exception {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("name", "ICE save pipeline");
        source.setInteger("value", 42);
        source.setByteArray("payload", new byte[256 * 1024]);

        byte[] first = ChunkSaveCompressionBridge.compressForTest(source);
        byte[] second = ChunkSaveCompressionBridge.compressForTest(source);
        assertEquivalent(source, first);
        assertEquivalent(source, second);
    }

    @Test
    public void compressionWorkersAdaptToCpuAndHeapWhileLeavingMainCapacity() {
        long gib = 1024L * 1024L * 1024L;
        assertEquals(1, ChunkSaveCompressionBridge.workerCountForTest(2, gib));
        assertEquals(1, ChunkSaveCompressionBridge.workerCountForTest(16, gib));
        assertEquals(2, ChunkSaveCompressionBridge.workerCountForTest(4, 2L * gib));
        assertEquals(4, ChunkSaveCompressionBridge.workerCountForTest(16, 4L * gib));
        assertEquals(4, ChunkSaveCompressionBridge.workerCountForTest(64, 16L * gib));
    }

    @Test
    public void cancellingQueuedCompressionCanNeverStrandFileIo() throws Exception {
        assertTrue(ChunkSaveCompressionBridge.discardedTaskCompletesForTest());
    }

    private static void assertEquivalent(NBTTagCompound expected, byte[] compressed)
        throws Exception {
        DataInputStream input = new DataInputStream(new InflaterInputStream(
            new ByteArrayInputStream(compressed)));
        NBTTagCompound actual;
        try {
            actual = CompressedStreamTools.read(input);
        } finally {
            input.close();
        }
        assertEquals(expected.getString("name"), actual.getString("name"));
        assertEquals(expected.getInteger("value"), actual.getInteger("value"));
        assertArrayEquals(expected.getByteArray("payload"), actual.getByteArray("payload"));
    }
}

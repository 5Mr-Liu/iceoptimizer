package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.*;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

public final class NbtGzipBufferBridgeTest {
    @Test public void gzipBytesAndNbtReadbackMatchWithFewerOutputWrites() throws Exception {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("name", "保存验证");
        byte[] payload = new byte[256 * 1024];
        new Random(102L).nextBytes(payload);
        nbt.setByteArray("payload", payload);
        CountingOutput original = new CountingOutput();
        CountingOutput buffered = new CountingOutput();
        CompressedStreamTools.writeCompressed(nbt, original);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
            NbtGzipBufferBridge.buffered(buffered)))) {
            CompressedStreamTools.write(nbt, output);
        }
        assertArrayEquals(original.toByteArray(), buffered.toByteArray());
        assertEquals(nbt, CompressedStreamTools.readCompressed(new ByteArrayInputStream(buffered.toByteArray())));
        assertTrue("output batching should remove tiny compressed writes", buffered.writes < original.writes / 4);
        assertEquals(1, buffered.closes);
    }

    @Test public void emptyGzipStillWritesTheStandardHeaderTrailerAndCloses() throws Exception {
        CountingOutput original = new CountingOutput();
        CountingOutput buffered = new CountingOutput();
        new java.util.zip.GZIPOutputStream(original).close();
        NbtGzipBufferBridge.buffered(buffered).close();
        assertArrayEquals(original.toByteArray(), buffered.toByteArray());
        assertEquals(1, buffered.closes);
    }

    @Test public void underlyingWriteFailurePropagates() throws Exception {
        final IOException failure = new IOException("disk failure");
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
        };
        try {
            NbtGzipBufferBridge.buffered(broken);
            fail("header write must fail as in standard gzip");
        } catch (IOException error) { assertSame(failure, error); }
    }

    private static final class CountingOutput extends ByteArrayOutputStream {
        int writes;
        int closes;
        @Override public void write(byte[] bytes, int offset, int length) {
            writes++;
            super.write(bytes, offset, length);
        }
        @Override public void close() { closes++; }
    }
}

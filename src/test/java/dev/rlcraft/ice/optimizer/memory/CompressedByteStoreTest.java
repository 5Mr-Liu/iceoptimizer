package dev.rlcraft.ice.optimizer.memory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CompressedByteStoreTest {
    @Test
    public void lz4RoundTripIsLossless() {
        byte[] source = new byte[32768];
        byte[] pattern = "RLCraft exact geometry payload".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < source.length; i++) source[i] = pattern[i % pattern.length];
        CompressedByteStore store = CompressedByteStore.compress(source);
        assertArrayEquals(source, store.restore());
        assertTrue(store.getCompressedLength() < source.length);
    }
}

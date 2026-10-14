package dev.rlcraft.ice.optimizer.compat.otg;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;

public final class OtgStructureCompressionBridgeTest {
    @Test public void compressorReuseKeepsExactZlibAndIndependentResults() throws Exception {
        for (int size : new int[] {0, 1, 32, 65536, 1024 * 1024}) {
            byte[] input = new byte[size];
            new Random(size).nextBytes(input);
            byte[] result = OtgStructureCompressionBridge.compress(input);
            assertArrayEquals(reference(input), result);
            byte[] retained = result.clone();
            Arrays.fill(input, (byte) 0);
            assertArrayEquals(reference(input), OtgStructureCompressionBridge.compress(input));
            assertArrayEquals(retained, result);
        }
    }

    @Test public void concurrentCallersOwnSeparateNativeCompressorAndOutput() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<byte[]>> results = new java.util.ArrayList<Future<byte[]>>();
            for (int i = 0; i < 20; i++) {
                final int seed = i;
                results.add(pool.submit(new Callable<byte[]>() {
                    @Override public byte[] call() throws Exception {
                        byte[] input = new byte[32768 + seed]; new Random(seed).nextBytes(input);
                        byte[] actual = OtgStructureCompressionBridge.compress(input);
                        assertArrayEquals(reference(input), actual);
                        return actual;
                    }
                }));
            }
            for (Future<byte[]> result : results) assertTrue(result.get().length > 0);
        } finally { pool.shutdownNow(); }
    }

    private static byte[] reference(byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(output)) { compressed.write(input); }
        return output.toByteArray();
    }
}

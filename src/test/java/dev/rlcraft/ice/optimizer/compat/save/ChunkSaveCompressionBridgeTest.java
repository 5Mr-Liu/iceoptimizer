package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
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
    public void compressionWorkersAlwaysStartFromOneSafeWorker() {
        assertEquals(1, ChunkSaveCompressionBridge.initialWorkerCountForTest());
        assertEquals(1, new ChunkSaveCompressionBridge.AdaptiveWorkerPolicy()
            .targetWorkers());
    }

    @Test
    public void sustainedQueueAndFileIoPressureScaleUpWithinTheFixedCap() {
        ChunkSaveCompressionBridge.AdaptiveWorkerPolicy policy =
            new ChunkSaveCompressionBridge.AdaptiveWorkerPolicy();
        for (int sample = 0; sample < 3; sample++) {
            policy.observe(64, policy.targetWorkers(), false, false);
        }
        assertEquals("a transient burst must not change concurrency", 1,
            policy.targetWorkers());

        for (int sample = 0; sample < 32; sample++) {
            policy.observe(64, policy.targetWorkers(), true, false);
        }
        assertEquals(4, policy.targetWorkers());
        for (int sample = 0; sample < 64; sample++) {
            policy.observe(64, policy.targetWorkers(), true, false);
        }
        assertEquals("online pressure must never exceed the safety cap", 4,
            policy.targetWorkers());
    }

    @Test
    public void retainedResultPressureScalesWorkersBackDownWithHysteresis() {
        ChunkSaveCompressionBridge.AdaptiveWorkerPolicy policy =
            new ChunkSaveCompressionBridge.AdaptiveWorkerPolicy();
        for (int sample = 0; sample < 32; sample++) {
            policy.observe(64, policy.targetWorkers(), true, false);
        }
        assertEquals(4, policy.targetWorkers());

        for (int sample = 0; sample < 24; sample++) {
            policy.observe(0, policy.targetWorkers(), false, true);
        }
        assertEquals(1, policy.targetWorkers());
    }

    @Test
    public void cancellingQueuedCompressionCanNeverStrandFileIo() throws Exception {
        assertTrue(ChunkSaveCompressionBridge.discardedTaskCompletesForTest());
    }

    @Test
    public void compressorThreadExitExplicitlyEndsItsThreadLocalDeflater()
        throws Exception {
        final AtomicBoolean ended = new AtomicBoolean();
        final Deflater injected = new Deflater() {
            @Override public void end() {
                ended.set(true);
                super.end();
            }
        };
        Class<?> factoryType = Class.forName(
            ChunkSaveCompressionBridge.class.getName()
                + "$CompressionThreadFactory");
        Constructor<?> constructor = factoryType.getDeclaredConstructor();
        constructor.setAccessible(true);
        ThreadFactory factory = (ThreadFactory) constructor.newInstance();
        Thread worker = factory.newThread(new Runnable() {
            @Override public void run() {
                try {
                    Field threadLocalField = ChunkSaveCompressionBridge.class
                        .getDeclaredField("DEFLATERS");
                    threadLocalField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> threadLocal = (ThreadLocal<Object>)
                        threadLocalField.get(null);
                    Object holder = threadLocal.get();
                    Field deflaterField = holder.getClass()
                        .getDeclaredField("deflater");
                    deflaterField.setAccessible(true);
                    deflaterField.set(holder, injected);
                } catch (Exception reflectionFailure) {
                    throw new AssertionError(reflectionFailure);
                }
            }
        });
        assertFalse(ended.get());
        worker.start();
        worker.join(2000L);
        assertFalse("compressor worker did not terminate", worker.isAlive());
        assertTrue("thread-local native Deflater was not ended", ended.get());
    }

    @Test
    public void fatalLeaseCleanupWinsWithoutLosingTheClosePrimary()
        throws Exception {
        final IOException primary = new IOException("sink close primary");
        final OutOfMemoryError fatal = new OutOfMemoryError(
            "deflater cleanup fatal");
        OutputStream sink = new OutputStream() {
            @Override public void write(int value) { }
            @Override public void close() throws IOException { throw primary; }
        };
        Deflater deflater = new Deflater() {
            @Override public void end() {
                super.end();
                throw new IllegalStateException("wrapped cleanup", fatal);
            }
        };
        OutputStream compressed = ChunkSaveCompressionBridge
            .pooledStreamForTest(sink, deflater);
        try {
            compressed.close();
            throw new AssertionError("fatal cleanup was swallowed");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
            assertEquals(1, expected.getSuppressed().length);
            assertSame(primary, expected.getSuppressed()[0]);
        }
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

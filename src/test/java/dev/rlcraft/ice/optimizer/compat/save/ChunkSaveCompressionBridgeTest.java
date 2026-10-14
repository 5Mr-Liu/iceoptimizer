package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
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
    public void pausingReleasesFileIoThatAlreadyClaimedAStillQueuedTask() throws Exception {
        assertClaimedTaskFinishesAfterPause(true);
    }

    @Test
    public void pausingLetsAnAlreadyDequeuedTaskCompleteAndFallBack() throws Exception {
        assertClaimedTaskFinishesAfterPause(false);
    }

    private static void assertClaimedTaskFinishesAfterPause(boolean stillQueued) throws Exception {
        final CountDownLatch workerHasTask = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), runnable -> {
                Thread thread = new Thread(runnable, "ICE compression pause test");
                thread.setDaemon(true);
                return thread;
            }) {
                @Override protected void beforeExecute(Thread thread, Runnable task) {
                    workerHasTask.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try { releaseWorker.await(); break; }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted) thread.interrupt();
                }
            };
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
            OptimizationModule.VANILLA_CHUNK_COMPRESSION);
        boolean previouslyOperational = breaker.isOperational();
        boolean previouslyPaused = OptimizerRegistry.isRuntimePaused();
        ChunkSaveCompressionBridge.shutdown();
        OptimizerRegistry.setRuntimePaused(false);
        breaker.configure(true, 3);
        breaker.patchInstalled("test.AnvilChunkLoader", "test");
        final NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("name", "ICE save pipeline");
        snapshot.setInteger("value", 42);
        snapshot.setByteArray("payload", new byte[] { 1, 2, 3, 4 });
        NBTTagCompound original = snapshot.copy();
        FutureTask<Boolean> pendingWrite = new FutureTask<Boolean>(() ->
            ChunkSaveCompressionBridge.tryWrite(new File("unused-save-directory"),
                new ChunkPos(0, 0), snapshot));
        Thread fileIo = new Thread(pendingWrite, "ICE FILE_IO pause test");
        fileIo.setDaemon(true);
        try {
            if (stillQueued) pool.execute(() -> { });
            Field executorField = privateField("executor");
            Object lock = privateField("LOCK").get(null);
            synchronized (lock) { executorField.set(null, pool); }
            ChunkSaveCompressionBridge.schedule(snapshot);
            assertTrue("compression worker did not take its task",
                workerHasTask.await(2L, TimeUnit.SECONDS));
            assertEquals(stillQueued ? 1 : 0, pool.getQueue().size());
            fileIo.start();
            awaitClaimedSnapshot(snapshot, lock);
            assertFalse(pendingWrite.isDone());

            OptimizerRegistry.setRuntimePaused(true);
            ChunkSaveCompressionBridge.shutdown();
            if (stillQueued) {
                assertFalse("queued work must release FILE_IO without waiting for a worker",
                    pendingWrite.get(2L, TimeUnit.SECONDS));
                assertEquals(1L, releaseWorker.getCount());
            } else {
                assertFalse("a task already taken by a worker finishes naturally", pendingWrite.isDone());
                releaseWorker.countDown();
                assertFalse(pendingWrite.get(2L, TimeUnit.SECONDS));
            }
            assertEquals("optional compression must leave vanilla's snapshot intact", original, snapshot);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream fallback = new DataOutputStream(
                ChunkSaveCompressionBridge.createDeflaterStream(bytes))) {
                CompressedStreamTools.write(snapshot, fallback);
            }
            assertEquivalent(original, bytes.toByteArray());
        } finally {
            releaseWorker.countDown();
            fileIo.interrupt();
            fileIo.join(2000L);
            pool.shutdownNow();
            assertTrue("compression test worker did not finish", pool.awaitTermination(2L, TimeUnit.SECONDS));
            ChunkSaveCompressionBridge.shutdown();
            breaker.configure(previouslyOperational, 3);
            OptimizerRegistry.setRuntimePaused(previouslyPaused);
        }
    }

    private static void awaitClaimedSnapshot(NBTTagCompound snapshot, Object lock) throws Exception {
        Map<?, ?> tasks = (Map<?, ?>) privateField("TASKS").get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        do {
            synchronized (lock) {
                if (!tasks.containsKey(snapshot)) return;
            }
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("FILE_IO did not claim the scheduled snapshot");
    }

    private static Field privateField(String name) throws Exception {
        Field field = ChunkSaveCompressionBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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

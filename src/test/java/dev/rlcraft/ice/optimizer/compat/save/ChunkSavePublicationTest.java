package dev.rlcraft.ice.optimizer.compat.save;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.RegionFileCache;

public final class ChunkSavePublicationTest {
    @Rule public final TemporaryFolder directory = new TemporaryFolder();
    private boolean paused;
    private boolean operational;
    private ModuleCircuitBreaker breaker;

    @Before public void enable() {
        ChunkSaveCompressionBridge.shutdown();
        paused = OptimizerRegistry.isRuntimePaused();
        breaker = OptimizerRegistry.breaker(OptimizationModule.VANILLA_CHUNK_COMPRESSION);
        operational = breaker.isOperational();
        OptimizerRegistry.setRuntimePaused(false);
        breaker.configure(true, 3);
        breaker.patchInstalled("test.AnvilChunkLoader", "test");
    }

    @After public void restore() throws Exception {
        ChunkSaveCompressionBridge.shutdown();
        breaker.configure(operational, 3);
        OptimizerRegistry.setRuntimePaused(paused);
        assertEquals("all result ownership released", 0L, resultBytes().get());
    }

    @Test public void snapshotIsRegisteredBeforePendingMapBecomesVisible() throws Exception {
        final CountDownLatch visible = new CountDownLatch(1);
        final CountDownLatch finishPut = new CountDownLatch(1);
        Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>() {
            @Override public Object put(Object key, Object value) {
                Object old = super.put(key, value);
                visible.countDown();
                try { assertTrue(finishPut.await(2L, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return old;
            }
        };
        NBTTagCompound snapshot = snapshot(101);
        FutureTask<Object> publisher = new FutureTask<Object>(() ->
            ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot));
        Thread thread = new Thread(publisher, "pending publication test");
        thread.setDaemon(true);
        thread.start();
        Object task = null;
        try {
            assertTrue(visible.await(2L, TimeUnit.SECONDS));
            assertSame(snapshot, pending.get("chunk"));
            task = claim(snapshot);
            assertNotNull("IO-visible NBT must already have a registered task", task);
            assertFalse(done(task));
            finishPut.countDown();
            assertNull(publisher.get(2L, TimeUnit.SECONDS));
            assertEquivalent(snapshot, awaitResult(task));
        } finally {
            finishPut.countDown();
            thread.join(2000L);
            if (task != null) discard(task);
        }
    }

    @Test public void replacementRemovesQueuedWorkAndReleasesItsSnapshotImmediately() throws Exception {
        BlockedPool pool = new BlockedPool();
        install(pool);
        Object secondTask = null;
        try {
            Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>();
            NBTTagCompound first = snapshot(1), second = snapshot(2);
            ChunkSaveCompressionBridge.publish(pending, "chunk", first);
            Object firstTask = lookup(first);
            assertEquals(1, pool.getQueue().size());
            assertEquals("queued saves do not pre-reserve 16 MiB each", 0L, resultBytes().get());
            assertSame(first, ChunkSaveCompressionBridge.publish(pending, "chunk", second));
            assertNull(lookup(first));
            assertNull(member(firstTask, "snapshot"));
            assertTrue(done(firstTask));
            assertEquals("superseded runnable removed from executor", 1, pool.getQueue().size());
            secondTask = claim(second);
            pool.release.countDown();
            assertEquivalent(second, awaitResult(secondTask));
        } finally {
            pool.release.countDown();
            if (secondTask != null) discard(secondTask);
            pool.shutdown();
            assertTrue(pool.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test public void replacingPendingValueNeverCancelsSaveAlreadyClaimedByIo() throws Exception {
        BlockedPool pool = new BlockedPool();
        install(pool);
        Object firstTask = null, secondTask = null;
        try {
            Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>();
            NBTTagCompound first = snapshot(3), second = snapshot(4);
            ChunkSaveCompressionBridge.publish(pending, "chunk", first);
            firstTask = claim(first);
            ChunkSaveCompressionBridge.publish(pending, "chunk", second);
            assertFalse((Boolean) member(firstTask, "discarded"));
            secondTask = claim(second);
            pool.release.countDown();
            assertEquivalent(first, awaitResult(firstTask));
            assertEquivalent(second, awaitResult(secondTask));
        } finally {
            pool.release.countDown();
            if (firstTask != null) discard(firstTask);
            if (secondTask != null) discard(secondTask);
            pool.shutdown();
            assertTrue(pool.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test public void fullOutputBudgetDoesNotSerializeThenThrowAwayResult() throws Exception {
        CountingCompound snapshot = new CountingCompound();
        snapshot.setInteger("value", 5);
        Method maximum = ChunkSaveCompressionBridge.class.getDeclaredMethod("maximumResultBytes");
        maximum.setAccessible(true);
        AtomicLong bytes = resultBytes();
        long occupied = (Long) maximum.invoke(null);
        Object task = null;
        bytes.set(occupied);
        try {
            Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>();
            ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot);
            task = claim(snapshot);
            assertNull(awaitResult(task));
            assertEquals("capacity refusal precedes all NBT serialization", 0, snapshot.writes.get());
            assertEquals(occupied, bytes.get());
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(
                ChunkSaveCompressionBridge.createDeflaterStream(fallback))) {
                CompressedStreamTools.write(snapshot, data);
            }
            assertTrue(snapshot.writes.get() > 0);
            assertEquivalent(snapshot, fallback.toByteArray());
        } finally {
            if (task != null) discard(task);
            bytes.addAndGet(-occupied);
        }
    }

    @Test public void failedPublicationCancelsRegisteredTaskWithoutSerialization() throws Exception {
        CountingCompound snapshot = new CountingCompound();
        Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>() {
            @Override public Object put(Object key, Object value) { throw new IllegalArgumentException("put"); }
        };
        try {
            ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot);
            fail("original failure must propagate");
        } catch (IllegalArgumentException expected) { assertEquals("put", expected.getMessage()); }
        assertNull(lookup(snapshot));
        assertEquals(0, snapshot.writes.get());
    }

    @Test public void duplicatePendingSnapshotKeepsItsOneRegisteredTask() throws Exception {
        BlockedPool pool = new BlockedPool();
        install(pool);
        Object task = null;
        try {
            Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>();
            NBTTagCompound snapshot = snapshot(6);
            ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot);
            task = lookup(snapshot);
            assertSame(snapshot, ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot));
            assertSame(task, lookup(snapshot));
            assertEquals(1, pool.getQueue().size());
            claim(snapshot);
            pool.release.countDown();
            assertEquivalent(snapshot, awaitResult(task));
        } finally {
            pool.release.countDown();
            if (task != null) discard(task);
            pool.shutdown();
            assertTrue(pool.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test public void pauseDrainsAcceptedWorkAndDisabledConsumerReleasesOwnership() throws Exception {
        Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>();
        NBTTagCompound snapshot = snapshot(7);
        ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot);
        Object task = lookup(snapshot);
        OptimizerRegistry.setRuntimePaused(true);
        ChunkSaveCompressionBridge.pause();
        assertEquivalent(snapshot, awaitResult(task));
        assertTrue(resultBytes().get() > 0L);
        try {
            // The test RegionFile has no transformed raw-write ABI. It returns
            // false after claiming, but must still release the completed output.
            assertFalse(ChunkSaveCompressionBridge.tryWrite(directory.getRoot(), new ChunkPos(0, 0), snapshot));
            assertNull(lookup(snapshot));
            assertEquals(0L, resultBytes().get());
        } finally { RegionFileCache.clearRegionFileReferences(); }
    }

    @Test public void resetBetweenPublicationAndSubmissionCompletesConsumer() throws Exception {
        final CountDownLatch visible = new CountDownLatch(1), release = new CountDownLatch(1);
        Map<Object, Object> pending = new ConcurrentHashMap<Object, Object>() {
            @Override public Object put(Object key, Object value) {
                Object previous = super.put(key, value);
                visible.countDown();
                try { assertTrue(release.await(2L, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return previous;
            }
        };
        NBTTagCompound snapshot = snapshot(8);
        FutureTask<Object> publication = new FutureTask<Object>(() -> ChunkSaveCompressionBridge.publish(pending, "chunk", snapshot));
        Thread publisher = new Thread(publication, "save reset publication test");
        publisher.setDaemon(true);
        publisher.start();
        try {
            assertTrue(visible.await(2L, TimeUnit.SECONDS));
            Object task = lookup(snapshot);
            ChunkSaveCompressionBridge.reset();
            assertNull(awaitResult(task));
            release.countDown();
            publication.get(2L, TimeUnit.SECONDS);
            assertNull(lookup(snapshot));
            assertEquals(0L, resultBytes().get());
        } finally { release.countDown(); publisher.join(2000L); }
    }

    private static NBTTagCompound snapshot(int value) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("value", value);
        result.setByteArray("payload", new byte[8192]);
        return result;
    }
    private static final class CountingCompound extends NBTTagCompound {
        private final AtomicInteger writes = new AtomicInteger();
        @Override public byte getId() { writes.incrementAndGet(); return super.getId(); }
    }
    private static Object lookup(NBTTagCompound snapshot) throws Exception {
        synchronized (field("LOCK").get(null)) {
            return ((Map<?, ?>) field("TASKS").get(null)).get(snapshot);
        }
    }
    private static Object claim(NBTTagCompound snapshot) throws Exception {
        synchronized (field("LOCK").get(null)) {
            return ((Map<?, ?>) field("TASKS").get(null)).remove(snapshot);
        }
    }
    private static byte[] awaitResult(Object task) throws Exception {
        assertNotNull(task);
        assertTrue("save consumer must not be stranded", ((CountDownLatch) member(task, "completed")).await(2L, TimeUnit.SECONDS));
        Object result = member(task, "result");
        return result == null ? null : Arrays.copyOf((byte[]) member(result, "bytes"), (Integer) member(result, "length"));
    }
    private static boolean done(Object task) throws Exception { return ((CountDownLatch) member(task, "completed")).getCount() == 0L; }
    private static void discard(Object task) throws Exception {
        Method method = task.getClass().getDeclaredMethod("discard"); method.setAccessible(true); method.invoke(task);
    }
    private static Object member(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Field field(String name) throws Exception {
        Field field = ChunkSaveCompressionBridge.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static AtomicLong resultBytes() throws Exception { return (AtomicLong) field("RESULT_BYTES").get(null); }
    private static void assertEquivalent(NBTTagCompound source, byte[] bytes) throws Exception {
        assertNotNull(bytes);
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            assertEquals(source, CompressedStreamTools.read(input));
        }
    }
    private static void install(BlockedPool pool) throws Exception {
        field("executor").set(null, pool);
        pool.execute(() -> { });
        assertTrue(pool.blocked.await(2L, TimeUnit.SECONDS));
    }
    private static final class BlockedPool extends ThreadPoolExecutor {
        private final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private BlockedPool() {
            super(1, 4, 1L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "save publication worker test"); thread.setDaemon(true); return thread;
            });
        }
        @Override protected void beforeExecute(Thread thread, Runnable task) {
            if (first.compareAndSet(true, false)) {
                blocked.countDown();
                try { assertTrue(release.await(2L, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
}

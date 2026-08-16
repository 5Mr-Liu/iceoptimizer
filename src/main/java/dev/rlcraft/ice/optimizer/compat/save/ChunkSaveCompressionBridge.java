package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;

/**
 * Bounded post-snapshot chunk compression pipeline. NBT construction and all
 * RegionFile writes remain on vanilla's original threads and in original map
 * order; only pure serialization/Deflate work runs concurrently.
 */
public final class ChunkSaveCompressionBridge {
    private static final int MODULE = OptimizationModule.VANILLA_CHUNK_COMPRESSION.ordinal();
    private static final int COMPRESSION_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_COMPRESSED_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TRACKED_TASKS = 128;
    private static final int QUEUE_CAPACITY = 64;
    private static final long ORPHAN_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<NBTTagCompound, CompressionTask> TASKS =
        new IdentityHashMap<NBTTagCompound, CompressionTask>();
    private static final AtomicLong RESULT_BYTES = new AtomicLong();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static final ThreadLocal<DeflaterHolder> DEFLATERS =
        new ThreadLocal<DeflaterHolder>() {
            @Override protected DeflaterHolder initialValue() {
                return new DeflaterHolder();
            }
        };
    private static volatile ThreadPoolExecutor executor;
    private static long generation = 1L;

    private ChunkSaveCompressionBridge() {
    }

    /** Called only after AnvilChunkLoader has accepted the immutable NBT snapshot. */
    public static void schedule(NBTTagCompound snapshot) {
        if (snapshot == null || !OptimizerBridge.isEnabled(MODULE)) return;
        CompressionTask task;
        ThreadPoolExecutor pool;
        synchronized (LOCK) {
            cleanupLocked(System.nanoTime());
            if (TASKS.containsKey(snapshot) || TASKS.size() >= MAX_TRACKED_TASKS) return;
            pool = ensureExecutorLocked();
            task = new CompressionTask(snapshot, generation);
            TASKS.put(snapshot, task);
        }
        try {
            pool.execute(task);
        } catch (RejectedExecutionException saturated) {
            synchronized (LOCK) {
                if (TASKS.get(snapshot) == task) TASKS.remove(snapshot);
            }
            task.discard();
        } catch (Throwable error) {
            synchronized (LOCK) {
                if (TASKS.get(snapshot) == task) TASKS.remove(snapshot);
            }
            task.discard();
            OptimizerBridge.failure(MODULE, error);
        }
    }

    /**
     * Runs on vanilla's FILE_IO thread. Waiting here never stalls a game main
     * thread, and writes still occur in AnvilChunkLoader's original sequence.
     */
    public static boolean tryWrite(java.io.File saveDirectory, ChunkPos position,
                                   NBTTagCompound snapshot) {
        if (saveDirectory == null || position == null || snapshot == null
            || !OptimizerBridge.isEnabled(MODULE)) return false;
        CompressionTask task;
        synchronized (LOCK) {
            task = TASKS.remove(snapshot);
        }
        if (task == null) return false;
        try {
            task.await();
            CompressedResult result = task.result();
            if (result == null) return false;
            RegionFile region = RegionFileCache.createOrLoadRegionFile(
                saveDirectory, position.x, position.z);
            if (!(region instanceof RegionFileRawWriteAccessor)) return false;
            ((RegionFileRawWriteAccessor) region).ice$writeCompressed(
                position.x & 31, position.z & 31, result.bytes, result.length);
            if (ACTIVATED.compareAndSet(false, true)) {
                OptimizerBridge.activate(MODULE,
                    "区块 NBT 已在有界专用 Worker 并行压缩，并由原 FILE_IO 顺序写盘");
            }
            OptimizerBridge.success(MODULE);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException error) {
            OptimizerBridge.failure(MODULE, error);
            throw error;
        } catch (Error error) {
            OptimizerBridge.failure(MODULE, error);
            throw error;
        } finally {
            task.discard();
        }
    }

    /** RegionFile fallback path: same zlib format, reusable native Deflater. */
    public static OutputStream createDeflaterStream(OutputStream output) {
        if (output == null) throw new NullPointerException("output");
        if (!OptimizerBridge.isEnabled(MODULE)) return new DeflaterOutputStream(output);
        try {
            return pooledDeflaterStream(output);
        } catch (Throwable error) {
            OptimizerBridge.failure(MODULE, error);
            return new DeflaterOutputStream(output);
        }
    }

    /** Invalidates optional work without changing vanilla's pending-save map. */
    public static void reset() {
        CompressionTask[] discarded;
        synchronized (LOCK) {
            generation++;
            discarded = TASKS.values().toArray(new CompressionTask[TASKS.size()]);
            TASKS.clear();
        }
        for (CompressionTask task : discarded) task.discard();
    }

    public static void shutdown() {
        reset();
        ThreadPoolExecutor pool;
        synchronized (LOCK) {
            pool = executor;
            executor = null;
        }
        if (pool != null) pool.shutdownNow();
    }

    static int workerCountForTest(int processors, long maximumHeapBytes) {
        int cpuBound = Math.max(1, Math.min(4, processors - 2));
        long gib = 1024L * 1024L * 1024L;
        int heapBound = maximumHeapBytes < 1536L * 1024L * 1024L ? 1
            : maximumHeapBytes < 3L * gib ? 2 : 4;
        return Math.max(1, Math.min(cpuBound, heapBound));
    }

    static byte[] compressForTest(NBTTagCompound value) throws IOException {
        CompressedResult result = compress(value);
        byte[] copy = new byte[result.length];
        System.arraycopy(result.bytes, 0, copy, 0, result.length);
        return copy;
    }

    static boolean discardedTaskCompletesForTest() throws InterruptedException {
        CompressionTask task = new CompressionTask(new NBTTagCompound(), generation);
        task.discard();
        return task.completed.await(0L, TimeUnit.NANOSECONDS);
    }

    private static ThreadPoolExecutor ensureExecutorLocked() {
        ThreadPoolExecutor known = executor;
        if (known != null && !known.isShutdown()) return known;
        int workers = workerCountForTest(Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory());
        known = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY), new CompressionThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
        executor = known;
        return known;
    }

    private static void cleanupLocked(long now) {
        if (TASKS.size() < MAX_TRACKED_TASKS / 2) return;
        Iterator<Map.Entry<NBTTagCompound, CompressionTask>> iterator =
            TASKS.entrySet().iterator();
        while (iterator.hasNext()) {
            CompressionTask task = iterator.next().getValue();
            if (task.isDone() && now - task.createdNanos >= ORPHAN_NANOS) {
                iterator.remove();
                task.discard();
            }
        }
    }

    private static CompressedResult compress(NBTTagCompound snapshot) throws IOException {
        BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
            COMPRESSION_BUFFER_BYTES, MAX_COMPRESSED_CHUNK_BYTES);
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(
            pooledDeflaterStream(bytes), COMPRESSION_BUFFER_BYTES));
        try {
            CompressedStreamTools.write(snapshot, data);
        } finally {
            data.close();
        }
        return new CompressedResult(bytes.buffer(), bytes.length());
    }

    private static OutputStream pooledDeflaterStream(OutputStream output) {
        DeflaterHolder holder = DEFLATERS.get();
        DeflaterLease lease;
        if (!holder.inUse) {
            holder.inUse = true;
            lease = new DeflaterLease(holder, holder.deflater, false);
        } else {
            lease = new DeflaterLease(null, new Deflater(), true);
        }
        try {
            return new PooledDeflaterOutputStream(output, lease);
        } catch (Throwable error) {
            lease.release();
            throw error;
        }
    }

    private static boolean reserveResultBytes(int bytes) {
        long maximum = Math.max(32L * 1024L * 1024L,
            Math.min(128L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 32L));
        while (true) {
            long current = RESULT_BYTES.get();
            if (bytes < 0 || current + bytes > maximum) return false;
            if (RESULT_BYTES.compareAndSet(current, current + bytes)) return true;
        }
    }

    private static final class CompressionTask implements Runnable {
        private volatile NBTTagCompound snapshot;
        private final long taskGeneration;
        private final long createdNanos = System.nanoTime();
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile boolean discarded;
        private volatile CompressedResult result;

        private CompressionTask(NBTTagCompound snapshot, long taskGeneration) {
            this.snapshot = snapshot;
            this.taskGeneration = taskGeneration;
        }

        @Override public void run() {
            try {
                NBTTagCompound value = snapshot;
                if (!discarded && value != null) {
                    CompressedResult compressed = compress(value);
                    boolean current;
                    synchronized (LOCK) {
                        current = !discarded && taskGeneration == generation;
                    }
                    publish(compressed, current);
                }
            } catch (OutputLimitException expectedLargeChunk) {
                // Extended/oversized chunks retain vanilla's original stream.
            } catch (Throwable error) {
                if (!discarded) OptimizerBridge.failure(MODULE, error);
            } finally {
                snapshot = null;
                completed.countDown();
            }
        }

        private void await() throws InterruptedException {
            completed.await();
        }

        private synchronized void publish(CompressedResult compressed, boolean current) {
            if (!current || discarded || !reserveResultBytes(compressed.reservedBytes)) return;
            result = compressed;
        }

        private synchronized CompressedResult result() {
            return result;
        }

        private boolean isDone() {
            return completed.getCount() == 0L;
        }

        private synchronized void discard() {
            discarded = true;
            snapshot = null;
            CompressedResult value = result;
            if (value != null) {
                RESULT_BYTES.addAndGet(-value.reservedBytes);
                result = null;
            }
            // reset()/shutdown() can remove a queued Runnable before run() gets
            // its finally block. Never strand vanilla's FILE_IO fallback.
            completed.countDown();
        }
    }

    private static final class CompressionThreadFactory implements ThreadFactory {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                "ICE Chunk Compressor " + (++sequence));
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }

    private static final class DeflaterHolder {
        private Deflater deflater = new Deflater();
        private boolean inUse;
    }

    private static final class DeflaterLease {
        private final DeflaterHolder holder;
        private Deflater deflater;
        private final boolean disposable;
        private boolean released;

        private DeflaterLease(DeflaterHolder holder, Deflater deflater, boolean disposable) {
            this.holder = holder;
            this.deflater = deflater;
            this.disposable = disposable;
        }

        private void release() {
            if (released) return;
            released = true;
            Deflater value = deflater;
            deflater = null;
            if (value == null) return;
            if (disposable) {
                value.end();
                return;
            }
            try {
                value.reset();
            } catch (Throwable broken) {
                try { value.end(); } catch (Throwable ignored) { }
                holder.deflater = new Deflater();
            } finally {
                holder.inUse = false;
            }
        }
    }

    private static final class PooledDeflaterOutputStream extends DeflaterOutputStream {
        private final DeflaterLease lease;
        private boolean closed;

        private PooledDeflaterOutputStream(OutputStream output, DeflaterLease lease) {
            super(output, lease.deflater, COMPRESSION_BUFFER_BYTES);
            this.lease = lease;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                super.close();
            } finally {
                lease.release();
            }
        }
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maximum;

        private BoundedByteArrayOutputStream(int initial, int maximum) {
            super(initial);
            this.maximum = maximum;
        }

        @Override public void write(int value) {
            ensureCapacityBounded(1);
            buf[count++] = (byte) value;
        }

        @Override public void write(byte[] source, int offset, int length) {
            if (source == null) throw new NullPointerException("source");
            if (offset < 0 || length < 0 || offset > source.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacityBounded(length);
            System.arraycopy(source, offset, buf, count, length);
            count += length;
        }

        private void ensureCapacityBounded(int additional) {
            if (additional < 0 || count > maximum - additional) {
                throw new OutputLimitException();
            }
            int required = count + additional;
            if (required <= buf.length) return;
            int doubled = buf.length <= maximum / 2 ? buf.length << 1 : maximum;
            int target = Math.min(maximum, Math.max(required, doubled));
            buf = Arrays.copyOf(buf, target);
        }

        private byte[] buffer() {
            return buf;
        }

        private int length() {
            return count;
        }
    }

    private static final class CompressedResult {
        private final byte[] bytes;
        private final int length;
        private final int reservedBytes;
        private CompressedResult(byte[] bytes, int length) {
            this.bytes = bytes;
            this.length = length;
            this.reservedBytes = bytes.length;
        }
    }

    private static final class OutputLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

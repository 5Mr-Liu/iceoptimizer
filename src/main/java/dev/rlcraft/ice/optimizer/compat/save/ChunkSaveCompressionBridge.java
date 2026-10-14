package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.IdentityHashMap;
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
    private static final int INITIAL_COMPRESSION_WORKERS = 1;
    private static final int MAX_COMPRESSION_WORKERS = 4;
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<NBTTagCompound, CompressionTask> TASKS =
        new IdentityHashMap<NBTTagCompound, CompressionTask>();
    private static final AtomicLong RESULT_BYTES = new AtomicLong();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static final AdaptiveWorkerPolicy WORKER_POLICY =
        new AdaptiveWorkerPolicy();
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

    /** Registers ownership before vanilla makes the snapshot visible to FILE_IO. */
    public static Object publish(Map<Object, Object> pending, Object position, Object value) {
        NBTTagCompound snapshot = (NBTTagCompound) value;
        CompressionTask task = register(snapshot);
        Object previous;
        try {
            previous = pending.put(position, snapshot);
        } catch (Throwable error) {
            cancel(snapshot, task);
            throw error;
        }
        if (previous != snapshot && previous instanceof NBTTagCompound) {
            cancel((NBTTagCompound) previous, null);
        }
        submit(task);
        return previous;
    }

    /** Also used by isolated pipeline tests; production registers through publish. */
    public static void schedule(NBTTagCompound snapshot) {
        submit(register(snapshot));
    }

    private static CompressionTask register(NBTTagCompound snapshot) {
        if (snapshot == null || !OptimizerBridge.isEnabled(MODULE)) return null;
        synchronized (LOCK) {
            if (TASKS.containsKey(snapshot) || TASKS.size() >= MAX_TRACKED_TASKS) return null;
            CompressionTask task = new CompressionTask(snapshot, generation, 0);
            TASKS.put(snapshot, task);
            return task;
        }
    }

    private static void submit(CompressionTask task) {
        if (task == null || task.discarded) return;
        try {
            ThreadPoolExecutor pool;
            synchronized (LOCK) { pool = ensureExecutorLocked(); }
            pool.execute(task);
            observeExecutor(false, false);
        } catch (RejectedExecutionException saturated) {
            cancel(task.snapshot, task);
        } catch (Throwable error) {
            cancel(task.snapshot, task);
            FatalErrors.rethrowIfFatal(error);
            OptimizerBridge.failure(MODULE, error);
        }
    }

    private static void cancel(NBTTagCompound snapshot, CompressionTask expected) {
        CompressionTask task;
        synchronized (LOCK) {
            task = TASKS.get(snapshot);
            if (task != null && (expected == null || expected == task)) TASKS.remove(snapshot);
            else task = expected;
            ThreadPoolExecutor pool = executor;
            if (task != null && pool != null) pool.remove(task);
        }
        if (task != null) task.discard();
    }

    /**
     * Runs on vanilla's save consumer, including explicit synchronous flush.
     * Writes retain AnvilChunkLoader's original order.
     */
    public static boolean tryWrite(java.io.File saveDirectory, ChunkPos position,
                                   NBTTagCompound snapshot) {
        if (saveDirectory == null || position == null || snapshot == null) return false;
        CompressionTask task;
        synchronized (LOCK) {
            task = TASKS.remove(snapshot);
        }
        if (task == null) return false;
        try {
            boolean waitedForCompression = !task.isDone();
            task.await();
            observeExecutor(waitedForCompression, false);
            CompressedResult result = task.result();
            if (result == null) return false;
            RegionFile region = RegionFileCache.createOrLoadRegionFile(
                saveDirectory, position.x, position.z);
            if (!(region instanceof RegionFileRawWriteAccessor)) return false;
            ((RegionFileRawWriteAccessor) region).ice$writeCompressed(
                position.x & 31, position.z & 31, result.bytes, result.length);
            if (!ACTIVATED.get() && ACTIVATED.compareAndSet(false, true)) {
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
            WORKER_POLICY.reset();
            ThreadPoolExecutor pool = executor;
            if (pool != null && !pool.isShutdown()) {
                resizeExecutorLocked(pool, INITIAL_COMPRESSION_WORKERS);
            }
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
        if (pool != null) {
            for (Runnable queued : pool.shutdownNow()) {
                // FILE_IO may have claimed this task from TASKS before it
                // left the executor queue, so reset() could not discard it.
                // shutdownNow() prevents run() from releasing its waiter.
                if (queued instanceof CompressionTask) ((CompressionTask) queued).discard();
            }
        }
    }

    /** F6 stops accepting new work; vanilla still consumes already accepted saves. */
    public static void pause() {
        ThreadPoolExecutor pool;
        synchronized (LOCK) {
            pool = executor;
            executor = null;
        }
        if (pool != null) pool.shutdown();
    }

    static int initialWorkerCountForTest() {
        return INITIAL_COMPRESSION_WORKERS;
    }

    static byte[] compressForTest(NBTTagCompound value) throws IOException {
        CompressedResult result = compress(value);
        byte[] copy = new byte[result.length];
        System.arraycopy(result.bytes, 0, copy, 0, result.length);
        return copy;
    }

    static boolean discardedTaskCompletesForTest() throws InterruptedException {
        CompressionTask task = new CompressionTask(new NBTTagCompound(), generation, 0);
        task.discard();
        return task.completed.await(0L, TimeUnit.NANOSECONDS);
    }

    private static ThreadPoolExecutor ensureExecutorLocked() {
        ThreadPoolExecutor known = executor;
        if (known != null && !known.isShutdown()) return known;
        WORKER_POLICY.reset();
        known = new ThreadPoolExecutor(INITIAL_COMPRESSION_WORKERS,
            MAX_COMPRESSION_WORKERS, 15L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY), new CompressionThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
        known.allowCoreThreadTimeOut(true);
        executor = known;
        return known;
    }

    /**
     * Adjusts only from observed work pressure.  Hardware identity, processor
     * count and maximum heap never select a worker tier.
     */
    private static void observeExecutor(boolean fileIoWaited,
                                        boolean rejectedByResultPressure) {
        synchronized (LOCK) {
            ThreadPoolExecutor pool = executor;
            if (pool == null || pool.isShutdown()) return;
            long maximum = maximumResultBytes();
            long retained = RESULT_BYTES.get();
            boolean resultPressure = rejectedByResultPressure
                || retained >= maximum - maximum / 4L;
            int target = WORKER_POLICY.observe(pool.getQueue().size(),
                pool.getActiveCount(), fileIoWaited, resultPressure);
            resizeExecutorLocked(pool, target);
        }
    }

    private static void resizeExecutorLocked(ThreadPoolExecutor pool,
                                             int target) {
        int bounded = Math.max(INITIAL_COMPRESSION_WORKERS,
            Math.min(MAX_COMPRESSION_WORKERS, target));
        if (pool.getCorePoolSize() == bounded) return;
        try {
            pool.setCorePoolSize(bounded);
        } catch (Throwable resizeFailure) {
            FatalErrors.rethrowIfFatal(resizeFailure);
            WORKER_POLICY.forceTarget(pool.getCorePoolSize());
            OptimizerBridge.failure(MODULE, resizeFailure);
        }
    }

    private static CompressedResult compress(NBTTagCompound snapshot) throws IOException {
        BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(
            COMPRESSION_BUFFER_BYTES, MAX_COMPRESSED_CHUNK_BYTES);
        PooledDeflaterOutputStream compressed = pooledDeflaterStream(bytes);
        try (DataOutputStream data = new DataOutputStream(
            new BorrowedBufferedOutputStream(compressed, compressed.inputBuffer()))) {
            CompressedStreamTools.write(snapshot, data);
        }
        return new CompressedResult(bytes.buffer(), bytes.length());
    }

    private static PooledDeflaterOutputStream pooledDeflaterStream(OutputStream output) {
        DeflaterHolder holder = DEFLATERS.get();
        if (holder.deflater == null) holder.deflater = new Deflater();
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
            Throwable failure = error;
            try { lease.release(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            rethrowUnchecked(failure);
            return null;
        }
    }

    static OutputStream pooledStreamForTest(OutputStream output,
                                            Deflater deflater) {
        return new PooledDeflaterOutputStream(output,
            new DeflaterLease(null, deflater, true));
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrowUnchecked(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("chunk compression cleanup failed",
            failure);
    }

    private static void throwCloseFailure(Throwable failure)
        throws IOException {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IOException("chunk compression stream close failed",
            failure);
    }

    private static boolean reserveResultBytes(int bytes) {
        long maximum = maximumResultBytes();
        while (true) {
            long current = RESULT_BYTES.get();
            if (bytes < 0 || current > maximum - bytes) return false;
            if (RESULT_BYTES.compareAndSet(current, current + bytes)) return true;
        }
    }

    private static long maximumResultBytes() {
        return Math.max(32L * 1024L * 1024L,
            Math.min(128L * 1024L * 1024L,
                Runtime.getRuntime().maxMemory() / 32L));
    }

    private static final class CompressionTask implements Runnable {
        private volatile NBTTagCompound snapshot;
        private final long taskGeneration;
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile boolean discarded;
        private volatile CompressedResult result;
        private boolean running;
        private int reservedBytes;

        private CompressionTask(NBTTagCompound snapshot, long taskGeneration, int reservedBytes) {
            this.snapshot = snapshot;
            this.taskGeneration = taskGeneration;
            this.reservedBytes = reservedBytes;
        }

        @Override public void run() {
            synchronized (this) {
                if (discarded || running || completed.getCount() == 0L) return;
                // Reserve on dequeue, not publication: queued saves do not occupy
                // 16 MiB each. Completed saves retain only their actual buffer.
                // Refusing before serialization cannot cause duplicate compression.
                if (!reserveResultBytes(MAX_COMPRESSED_CHUNK_BYTES)) {
                    snapshot = null;
                    completed.countDown();
                    return;
                }
                reservedBytes = MAX_COMPRESSED_CHUNK_BYTES;
                running = true;
            }
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
                FatalErrors.rethrowIfFatal(error);
                if (!discarded) OptimizerBridge.failure(MODULE, error);
            } finally {
                synchronized (this) {
                    snapshot = null;
                    running = false;
                    if (discarded || result == null) releaseReservation();
                    completed.countDown();
                }
                observeExecutor(false, false);
            }
        }

        private void await() throws InterruptedException {
            completed.await();
        }

        private synchronized void publish(CompressedResult compressed, boolean current) {
            if (!current || discarded) return;
            RESULT_BYTES.addAndGet(compressed.reservedBytes - reservedBytes);
            reservedBytes = compressed.reservedBytes;
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
            result = null;
            if (!running) releaseReservation();
            // reset()/shutdown() can remove a queued Runnable before run() gets
            // its finally block. Never strand vanilla's FILE_IO fallback.
            completed.countDown();
        }

        private void releaseReservation() {
            RESULT_BYTES.addAndGet(-reservedBytes);
            reservedBytes = 0;
        }
    }

    private static final class CompressionThreadFactory implements ThreadFactory {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(new CompressionWorker(runnable),
                "ICE Chunk Compressor " + (++sequence));
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }

    /** Ensures a timed-out/replaced pool thread does not defer native zlib cleanup to GC. */
    private static final class CompressionWorker implements Runnable {
        private final Runnable delegate;

        private CompressionWorker(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override public void run() {
            Throwable failure = null;
            try {
                delegate.run();
            } catch (Throwable error) {
                failure = error;
            }
            try {
                releaseThreadDeflater();
            } catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            if (failure != null) rethrowUnchecked(failure);
        }
    }

    private static void releaseThreadDeflater() {
        DeflaterHolder holder = DEFLATERS.get();
        Deflater value = holder.deflater;
        holder.deflater = null;
        holder.inUse = false;
        DEFLATERS.remove();
        if (value != null) value.end();
    }

    /** Hysteretic, bounded queue/consumer-pressure controller. */
    static final class AdaptiveWorkerPolicy {
        private static final int SCALE_UP_SCORE = 8;
        private static final int SCALE_DOWN_SCORE = 24;
        private int targetWorkers = INITIAL_COMPRESSION_WORKERS;
        private int pressureScore;
        private int reliefScore;

        int observe(int queuedTasks, int activeWorkers,
                    boolean fileIoWaited, boolean resultPressure) {
            int queued = Math.max(0, queuedTasks);
            int active = Math.max(0, activeWorkers);
            boolean saturated = queued >= targetWorkers
                && active >= targetWorkers;
            if (resultPressure) {
                pressureScore = 0;
                reliefScore = saturatingAdd(reliefScore, 4,
                    SCALE_DOWN_SCORE);
            } else if (fileIoWaited || saturated) {
                pressureScore = saturatingAdd(pressureScore,
                    fileIoWaited ? 2 : 1, SCALE_UP_SCORE);
                reliefScore = 0;
            } else if (queued == 0) {
                pressureScore = Math.max(0, pressureScore - 1);
                reliefScore = saturatingAdd(reliefScore, 1,
                    SCALE_DOWN_SCORE);
            } else {
                pressureScore = Math.max(0, pressureScore - 1);
                reliefScore = Math.max(0, reliefScore - 1);
            }

            if (pressureScore >= SCALE_UP_SCORE
                && targetWorkers < MAX_COMPRESSION_WORKERS) {
                targetWorkers++;
                pressureScore = 0;
                reliefScore = 0;
            } else if (reliefScore >= SCALE_DOWN_SCORE
                && targetWorkers > INITIAL_COMPRESSION_WORKERS) {
                targetWorkers--;
                pressureScore = 0;
                reliefScore = 0;
            }
            return targetWorkers;
        }

        int targetWorkers() {
            return targetWorkers;
        }

        void reset() {
            targetWorkers = INITIAL_COMPRESSION_WORKERS;
            pressureScore = 0;
            reliefScore = 0;
        }

        void forceTarget(int workers) {
            targetWorkers = Math.max(INITIAL_COMPRESSION_WORKERS,
                Math.min(MAX_COMPRESSION_WORKERS, workers));
            pressureScore = 0;
            reliefScore = 0;
        }

        private static int saturatingAdd(int current, int increment,
                                         int maximum) {
            return current >= maximum - increment
                ? maximum : current + increment;
        }
    }

    private static final class DeflaterHolder {
        private Deflater deflater;
        private boolean inUse;
        private final byte[] outputBuffer = new byte[COMPRESSION_BUFFER_BYTES];
        private final byte[] inputBuffer = new byte[COMPRESSION_BUFFER_BYTES];
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
                FatalErrors.rethrowIfFatal(broken);
                try { value.end(); }
                catch (Throwable ignored) {
                    FatalErrors.rethrowIfFatal(ignored);
                }
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
            super(output, lease.deflater, 1);
            this.lease = lease;
            this.buf = lease.holder == null ? new byte[COMPRESSION_BUFFER_BYTES]
                : lease.holder.outputBuffer;
        }

        private byte[] inputBuffer() {
            return lease.holder == null ? new byte[COMPRESSION_BUFFER_BYTES]
                : lease.holder.inputBuffer;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            try {
                super.close();
            }
            catch (Throwable closeFailure) { failure = closeFailure; }
            try { lease.release(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            if (failure != null) throwCloseFailure(failure);
        }
    }

    private static final class BorrowedBufferedOutputStream extends BufferedOutputStream {
        private BorrowedBufferedOutputStream(OutputStream output, byte[] buffer) {
            super(output, 1);
            this.buf = buffer;
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

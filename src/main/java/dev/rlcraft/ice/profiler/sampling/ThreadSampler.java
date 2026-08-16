package dev.rlcraft.ice.profiler.sampling;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.core.FixedRingBuffer;
import dev.rlcraft.ice.profiler.core.ProfilerLimits;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ThreadSampler {
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final ThreadRegistry registry = new ThreadRegistry(threadBean);
    private final StackTraceRepository stacks;
    private final FixedRingBuffer<StackSample> rollingSamples;
    private final SampleListener listener;
    private final SamplingMode mode;
    private final LongCounterTable previousCpu = new LongCounterTable();
    private final LongCounterTable previousAllocation = new LongCounterTable();
    private final com.sun.management.ThreadMXBean hotspotBean;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long lastDiscoveryNanos;
    private int deepBatchCursor;
    private int consecutiveFailures;

    public ThreadSampler(SampleListener listener, SamplingMode mode) {
        this.listener = listener;
        this.mode = mode;
        this.stacks = new StackTraceRepository(ProfilerLimits.stackDictionaryEntries(), IceConfig.sampling.maxStackDepth);
        this.rollingSamples = new FixedRingBuffer<StackSample>(ProfilerLimits.rollingSamples());
        this.hotspotBean = findHotspotBean();
        enableCpuTime();
        enableAllocationTracking();
    }

    public synchronized void start() {
        if (running) return;
        if (executor != null) executor.shutdownNow();
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ICE Profiler Sampler");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            }
        });
        executor.execute(new Runnable() {
            @Override public void run() { sampleAndSchedule(); }
        });
    }

    public synchronized void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    public ThreadRegistry getRegistry() { return registry; }
    public StackTraceRepository getStacks() { return stacks; }

    public List<StackSample> recentSince(long timestampNanos) {
        List<StackSample> all = rollingSamples.snapshot();
        List<StackSample> result = new ArrayList<StackSample>();
        for (StackSample sample : all) {
            if (sample.getTimestampNanos() >= timestampNanos) result.add(sample);
        }
        return result;
    }

    public List<StackSample> rollingSnapshot() { return rollingSamples.snapshot(); }

    private void sampleAndSchedule() {
        if (!running) return;
        try {
            sampleOnce();
            consecutiveFailures = 0;
        } catch (Throwable error) {
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures == 3) {
                IceProfilerMod.LOGGER.warn("ICE 线程采样器连续失败 {} 次", consecutiveFailures, error);
            }
            if (IceConfig.compatibility.failOpen && consecutiveFailures >= 3) {
                running = false;
                ScheduledExecutorService failedExecutor = executor;
                if (failedExecutor != null) failedExecutor.shutdown();
                IceProfilerMod.LOGGER.error("ICE 线程采样器已自动停用，游戏逻辑不受影响");
            }
        }
        ScheduledExecutorService current = executor;
        if (running && current != null) {
            int interval = mode.isDeepSampling() ? IceConfig.sampling.deepIntervalMs : IceConfig.sampling.passiveIntervalMs;
            current.schedule(new Runnable() {
                @Override public void run() { sampleAndSchedule(); }
            }, Math.max(5, interval), TimeUnit.MILLISECONDS);
        }
    }

    private void sampleOnce() {
        long now = System.nanoTime();
        long discoveryInterval = TimeUnit.SECONDS.toNanos(IceConfig.sampling.workerDiscoverySeconds);
        if (lastDiscoveryNanos == 0L || now - lastDiscoveryNanos >= discoveryInterval) {
            registry.discoverWorkers(IceConfig.sampling.sampleWorkers);
            lastDiscoveryNanos = now;
        }
        ThreadSamplingPlan plan = registry.samplingPlan();
        ThreadSamplingPlan.Batch batch = mode.isDeepSampling()
            ? plan.deepBatch(deepBatchCursor++) : plan.fullBatch();
        if (batch.size() == 0) return;
        ThreadInfo[] infos = threadBean.getThreadInfo(
            batch.ids(), IceConfig.sampling.maxStackDepth);
        long[] cpuTimes = readCpu(batch.ids());
        long[] allocations = readAllocation(batch.ids());
        ThreadDescriptor[] descriptors = batch.descriptors();
        for (int index = 0; index < infos.length; index++) {
            ThreadInfo info = infos[index];
            if (info == null) continue;
            ThreadDescriptor descriptor = descriptors[index];
            if (descriptor.getId() != info.getThreadId()) continue;
            long cpu = cpuTimes == null || index >= cpuTimes.length
                ? -1L : cpuTimes[index];
            long allocation = allocations == null || index >= allocations.length
                ? -1L : allocations[index];
            long cpuDelta = previousCpu.updateAndDelta(info.getThreadId(), cpu);
            long allocationDelta = previousAllocation.updateAndDelta(
                info.getThreadId(), allocation);
            int stackId = stacks.intern(info.getStackTrace());
            StackSample sample = new StackSample(
                now,
                info.getThreadId(),
                descriptor.getName(),
                descriptor.getRole(),
                stackId,
                cpuDelta,
                allocationDelta,
                info.getThreadState()
            );
            rollingSamples.add(sample);
            listener.onSample(sample);
        }
    }

    private long[] readCpu(long[] ids) {
        if (!IceConfig.sampling.cpuTime || !threadBean.isThreadCpuTimeSupported()) return null;
        if (hotspotBean != null) {
            try {
                return hotspotBean.getThreadCpuTime(ids);
            } catch (RuntimeException ignored) {
                // Fall through to the standard scalar API on non-HotSpot or
                // partially implemented management beans.
            }
        }
        long[] values = new long[ids.length];
        for (int index = 0; index < ids.length; index++) {
            values[index] = threadBean.getThreadCpuTime(ids[index]);
        }
        return values;
    }

    private long[] readAllocation(long[] ids) {
        if (!IceConfig.sampling.allocatedBytes || hotspotBean == null
            || !hotspotBean.isThreadAllocatedMemorySupported()) return null;
        try {
            return hotspotBean.getThreadAllocatedBytes(ids);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void enableCpuTime() {
        try {
            if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
        } catch (SecurityException ignored) {
            IceProfilerMod.LOGGER.debug("无法启用线程 CPU 时间采集");
        }
    }

    private com.sun.management.ThreadMXBean findHotspotBean() {
        try {
            if (threadBean instanceof com.sun.management.ThreadMXBean) {
                return (com.sun.management.ThreadMXBean) threadBean;
            }
        } catch (RuntimeException ignored) {
            // Optional HotSpot extension; sampling continues without it.
        }
        return null;
    }

    private void enableAllocationTracking() {
        if (hotspotBean == null || !hotspotBean.isThreadAllocatedMemorySupported()) return;
        try {
            if (!hotspotBean.isThreadAllocatedMemoryEnabled()) {
                hotspotBean.setThreadAllocatedMemoryEnabled(true);
            }
        } catch (RuntimeException ignored) {
            IceProfilerMod.LOGGER.debug("无法启用线程分配字节采集");
        }
    }

    /** Primitive, reusable thread-id counter table used by the 10 ms hot path. */
    static final class LongCounterTable {
        private long[] keys = new long[32];
        private long[] values = new long[32];
        private int size;

        long updateAndDelta(long id, long current) {
            if (id <= 0L) return 0L;
            if ((size + 1) * 10 >= keys.length * 6) grow();
            int mask = keys.length - 1;
            int index = mix(id) & mask;
            while (true) {
                long key = keys[index];
                if (key == 0L) {
                    keys[index] = id;
                    values[index] = current;
                    size++;
                    return 0L;
                }
                if (key == id) {
                    long previous = values[index];
                    values[index] = current;
                    return current < 0L || previous < 0L
                        ? 0L : Math.max(0L, current - previous);
                }
                index = (index + 1) & mask;
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            long[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new long[oldValues.length << 1];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                long key = oldKeys[i];
                if (key != 0L) putExisting(key, oldValues[i]);
            }
        }

        private void putExisting(long key, long value) {
            int mask = keys.length - 1;
            int index = mix(key) & mask;
            while (keys[index] != 0L) index = (index + 1) & mask;
            keys[index] = key;
            values[index] = value;
            size++;
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}

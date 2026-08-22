package dev.rlcraft.ice.profiler.jvm;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.profiler.FatalErrors;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects MXBean data away from Minecraft's client/server threads. Timeline
 * snapshots only read one immutable cached value and consume cumulative GC
 * counters, so two pulse sources cannot race to lose a pause delta.
 */
public final class JvmMonitor {
    private static final long DEFAULT_INTERVAL_MILLIS = 500L;
    private final Source source;
    private final long intervalMillis;
    private final AtomicReference<Reading> latest = new AtomicReference<Reading>();
    private final AtomicLong failures = new AtomicLong();
    private ScheduledExecutorService executor;
    private long previousGcCount;
    private long previousGcMillis;
    private boolean consumptionInitialized;

    public JvmMonitor() {
        this(new ManagementSource(), DEFAULT_INTERVAL_MILLIS);
    }

    JvmMonitor(Source source, long intervalMillis) {
        this.source = source;
        this.intervalMillis = Math.max(100L, intervalMillis);
    }

    public synchronized void start() {
        if (executor != null) return;
        consumptionInitialized = false;
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ICE JVM Metrics");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }
        });
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { collectSafely(); }
        }, 0L, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    public synchronized JvmSnapshot snapshot() {
        Reading reading = latest.get();
        if (reading == null) return new JvmSnapshot(0L, 0L, 0L, 0L, 0L, -1.0D);
        long countDelta = consumptionInitialized ? Math.max(0L, reading.gcCount - previousGcCount) : 0L;
        long millisDelta = consumptionInitialized ? Math.max(0L, reading.gcMillis - previousGcMillis) : 0L;
        consumptionInitialized = true;
        previousGcCount = reading.gcCount;
        previousGcMillis = reading.gcMillis;
        return new JvmSnapshot(reading.heapUsed, reading.heapCommitted, reading.heapMax,
            countDelta, millisDelta, reading.processCpuLoad);
    }

    void collectNow() {
        latest.set(source.read());
    }

    private void collectSafely() {
        try {
            collectNow();
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            long count = failures.incrementAndGet();
            if (count == 1L || (count & (count - 1L)) == 0L) {
                IceProfilerMod.LOGGER.debug("ICE 后台 JVM 指标采集失败（累计 {} 次）", count, error);
            }
        }
    }

    interface Source {
        Reading read();
    }

    static final class Reading {
        private final long heapUsed;
        private final long heapCommitted;
        private final long heapMax;
        private final long gcCount;
        private final long gcMillis;
        private final double processCpuLoad;

        Reading(long heapUsed, long heapCommitted, long heapMax, long gcCount,
                long gcMillis, double processCpuLoad) {
            this.heapUsed = heapUsed;
            this.heapCommitted = heapCommitted;
            this.heapMax = heapMax;
            this.gcCount = gcCount;
            this.gcMillis = gcMillis;
            this.processCpuLoad = processCpuLoad;
        }
    }

    private static final class ManagementSource implements Source {
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
        private final Object operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
        private final Method processCpuLoadMethod = findProcessCpuLoadMethod(operatingSystemBean);
        private volatile boolean cpuLoadUnavailable;

        @Override
        public Reading read() {
            long gcCount = 0L;
            long gcMillis = 0L;
            for (GarbageCollectorMXBean bean : garbageCollectors) {
                long count = bean.getCollectionCount();
                long millis = bean.getCollectionTime();
                if (count >= 0L) gcCount += count;
                if (millis >= 0L) gcMillis += millis;
            }
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            return new Reading(heap.getUsed(), heap.getCommitted(), heap.getMax(), gcCount, gcMillis,
                readProcessCpuLoad());
        }

        private double readProcessCpuLoad() {
            if (cpuLoadUnavailable || processCpuLoadMethod == null) return -1.0D;
            try {
                Object value = processCpuLoadMethod.invoke(operatingSystemBean);
                return value instanceof Number ? ((Number) value).doubleValue() : -1.0D;
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                cpuLoadUnavailable = true;
                return -1.0D;
            }
        }

        private static Method findProcessCpuLoadMethod(Object bean) {
            if (bean == null) return null;
            try {
                Method method = bean.getClass().getMethod("getProcessCpuLoad");
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                try {
                    Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
                    return type.getMethod("getProcessCpuLoad");
                } catch (Throwable unavailable) {
                    FatalErrors.rethrowIfFatal(unavailable);
                    return null;
                }
            }
        }
    }
}

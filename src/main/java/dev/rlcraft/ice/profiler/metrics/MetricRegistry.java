package dev.rlcraft.ice.profiler.metrics;

import dev.rlcraft.ice.profiler.jvm.JvmMonitor;
import dev.rlcraft.ice.profiler.stats.TimingAccumulator;
import dev.rlcraft.ice.profiler.probe.ProbeBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MetricRegistry {
    private final TimingAccumulator clientFrames = new TimingAccumulator(4096);
    private final TimingAccumulator clientTicks = new TimingAccumulator(512);
    private final TimingAccumulator serverTicks = new TimingAccumulator(512);
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicInteger renderQueueSize = new AtomicInteger(-1);
    private final AtomicInteger chunkUploadQueueSize = new AtomicInteger(-1);
    private final AtomicReference<Double> gpuFrameMillis = new AtomicReference<Double>(Double.valueOf(-1.0D));
    private final AtomicLong chunkLoads = new AtomicLong();
    private final AtomicLong chunkUnloads = new AtomicLong();
    private final AtomicLong chunkDataLoads = new AtomicLong();
    private final AtomicLong chunkDataSaves = new AtomicLong();
    private final AtomicLong inboundPackets = new AtomicLong();
    private final AtomicLong outboundPackets = new AtomicLong();
    private final AtomicLong inboundBytes = new AtomicLong();
    private final AtomicLong outboundBytes = new AtomicLong();
    private final Map<Integer, WorldGauge> worlds = new ConcurrentHashMap<Integer, WorldGauge>();
    private final JvmMonitor jvmMonitor = new JvmMonitor();
    private final ChunkChurnTracker chunkChurn = new ChunkChurnTracker();

    public void start() { chunkChurn.clear(); jvmMonitor.start(); }
    public void stop() { jvmMonitor.stop(); chunkChurn.clear(); }

    public void recordClientFrame(long nanos) { clientFrames.record(nanos); frames.incrementAndGet(); }
    public void recordClientTick(long nanos) { clientTicks.record(nanos); }
    public void recordServerTick(long nanos) { serverTicks.record(nanos); }
    public void setRenderQueues(int rebuildQueue, int uploadQueue) {
        renderQueueSize.set(rebuildQueue);
        chunkUploadQueueSize.set(uploadQueue);
    }
    public void setGpuFrameMillis(double millis) { gpuFrameMillis.set(Double.valueOf(millis)); }
    public void chunkLoaded() { chunkLoads.incrementAndGet(); }
    public void chunkUnloaded() { chunkUnloads.incrementAndGet(); }
    public void chunkDataLoaded() { chunkDataLoads.incrementAndGet(); }
    public void chunkDataSaved() { chunkDataSaves.incrementAndGet(); }
    public void serverChunkLoaded(int dimension, int chunkX, int chunkZ, Object chunkIdentity) {
        chunkLoads.incrementAndGet();
        chunkChurn.loaded(dimension, chunkX, chunkZ, chunkIdentity);
    }
    public void serverChunkUnloaded(int dimension, int chunkX, int chunkZ) {
        chunkUnloads.incrementAndGet();
        chunkChurn.unloaded(dimension, chunkX, chunkZ);
    }
    public void serverChunkDataLoaded(int dimension, int chunkX, int chunkZ,
                                      Object chunkIdentity) {
        chunkDataLoads.incrementAndGet();
        chunkChurn.dataLoaded(dimension, chunkX, chunkZ, chunkIdentity);
    }

    public void recordPacket(boolean inbound) {
        if (inbound) {
            inboundPackets.incrementAndGet();
        } else {
            outboundPackets.incrementAndGet();
        }
    }

    public void recordNetworkBytes(boolean inbound, long bytes) {
        if (inbound) {
            inboundBytes.addAndGet(Math.max(0L, bytes));
        } else {
            outboundBytes.addAndGet(Math.max(0L, bytes));
        }
    }

    public void updateWorld(int dimension, int chunks, int entities, int tileEntities) {
        worlds.put(Integer.valueOf(dimension), new WorldGauge(dimension, chunks, entities, tileEntities));
    }

    public void removeWorld(int dimension) {
        worlds.remove(Integer.valueOf(dimension));
    }

    public void removeServerWorld(int dimension) {
        worlds.remove(Integer.valueOf(dimension));
        chunkChurn.removeDimension(dimension);
    }

    public TimelinePoint snapshot(long sessionStartedMillis) {
        List<WorldGauge> worldSnapshot = new ArrayList<WorldGauge>(worlds.values());
        Collections.sort(worldSnapshot, new Comparator<WorldGauge>() {
            @Override
            public int compare(WorldGauge left, WorldGauge right) {
                return Integer.compare(left.getDimension(), right.getDimension());
            }
        });
        long now = System.currentTimeMillis();
        return new TimelinePoint(
            now,
            Math.max(0L, now - sessionStartedMillis),
            clientFrames.drain(),
            clientTicks.drain(),
            serverTicks.drain(),
            frames.getAndSet(0),
            renderQueueSize.get(),
            chunkUploadQueueSize.get(),
            gpuFrameMillis.get().doubleValue(),
            chunkLoads.getAndSet(0L),
            chunkUnloads.getAndSet(0L),
            chunkDataLoads.getAndSet(0L),
            chunkDataSaves.getAndSet(0L),
            inboundPackets.getAndSet(0L),
            outboundPackets.getAndSet(0L),
            inboundBytes.getAndSet(0L),
            outboundBytes.getAndSet(0L),
            worldSnapshot,
            jvmMonitor.snapshot(),
            ProbeBridge.drain(),
            chunkChurn.drain()
        );
    }
}

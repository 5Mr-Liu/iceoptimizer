package dev.rlcraft.ice.profiler.metrics;

import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import dev.rlcraft.ice.profiler.probe.ProbeMetric;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TimelinePoint {
    private final long epochMillis;
    private final long elapsedMillis;
    private final DistributionSnapshot clientFrames;
    private final DistributionSnapshot clientTicks;
    private final DistributionSnapshot serverTicks;
    private final int framesPerSecond;
    private final int renderQueueSize;
    private final int chunkUploadQueueSize;
    private final double gpuFrameMillis;
    private final long chunkLoads;
    private final long chunkUnloads;
    private final long chunkDataLoads;
    private final long chunkDataSaves;
    private final long inboundPackets;
    private final long outboundPackets;
    private final long inboundBytes;
    private final long outboundBytes;
    private final List<WorldGauge> worlds;
    private final JvmSnapshot jvm;
    private final List<ProbeMetric> probes;

    public TimelinePoint(
        long epochMillis,
        long elapsedMillis,
        DistributionSnapshot clientFrames,
        DistributionSnapshot clientTicks,
        DistributionSnapshot serverTicks,
        int framesPerSecond,
        int renderQueueSize,
        int chunkUploadQueueSize,
        double gpuFrameMillis,
        long chunkLoads,
        long chunkUnloads,
        long chunkDataLoads,
        long chunkDataSaves,
        long inboundPackets,
        long outboundPackets,
        long inboundBytes,
        long outboundBytes,
        List<WorldGauge> worlds,
        JvmSnapshot jvm,
        List<ProbeMetric> probes
    ) {
        this.epochMillis = epochMillis;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.clientFrames = clientFrames;
        this.clientTicks = clientTicks;
        this.serverTicks = serverTicks;
        this.framesPerSecond = Math.max(0, framesPerSecond);
        this.renderQueueSize = renderQueueSize;
        this.chunkUploadQueueSize = chunkUploadQueueSize;
        this.gpuFrameMillis = gpuFrameMillis;
        this.chunkLoads = Math.max(0L, chunkLoads);
        this.chunkUnloads = Math.max(0L, chunkUnloads);
        this.chunkDataLoads = Math.max(0L, chunkDataLoads);
        this.chunkDataSaves = Math.max(0L, chunkDataSaves);
        this.inboundPackets = Math.max(0L, inboundPackets);
        this.outboundPackets = Math.max(0L, outboundPackets);
        this.inboundBytes = Math.max(0L, inboundBytes);
        this.outboundBytes = Math.max(0L, outboundBytes);
        this.worlds = Collections.unmodifiableList(new ArrayList<WorldGauge>(worlds));
        this.jvm = jvm;
        this.probes = Collections.unmodifiableList(new ArrayList<ProbeMetric>(probes));
    }

    public long getEpochMillis() { return epochMillis; }
    public long getElapsedMillis() { return elapsedMillis; }
    public DistributionSnapshot getClientFrames() { return clientFrames; }
    public DistributionSnapshot getClientTicks() { return clientTicks; }
    public DistributionSnapshot getServerTicks() { return serverTicks; }
    public int getFramesPerSecond() { return framesPerSecond; }
    public int getRenderQueueSize() { return renderQueueSize; }
    public int getChunkUploadQueueSize() { return chunkUploadQueueSize; }
    public double getGpuFrameMillis() { return gpuFrameMillis; }
    public long getChunkLoads() { return chunkLoads; }
    public long getChunkUnloads() { return chunkUnloads; }
    public long getChunkDataLoads() { return chunkDataLoads; }
    public long getChunkDataSaves() { return chunkDataSaves; }
    public long getInboundPackets() { return inboundPackets; }
    public long getOutboundPackets() { return outboundPackets; }
    public long getInboundBytes() { return inboundBytes; }
    public long getOutboundBytes() { return outboundBytes; }
    public List<WorldGauge> getWorlds() { return worlds; }
    public JvmSnapshot getJvm() { return jvm; }
    public List<ProbeMetric> getProbes() { return probes; }

    public TimelinePoint rebase(long sessionStartedMillis) {
        return new TimelinePoint(
            epochMillis,
            Math.max(0L, epochMillis - sessionStartedMillis),
            clientFrames,
            clientTicks,
            serverTicks,
            framesPerSecond,
            renderQueueSize,
            chunkUploadQueueSize,
            gpuFrameMillis,
            chunkLoads,
            chunkUnloads,
            chunkDataLoads,
            chunkDataSaves,
            inboundPackets,
            outboundPackets,
            inboundBytes,
            outboundBytes,
            worlds,
            jvm,
            probes
        );
    }

    public int getLoadedChunks() {
        int total = 0;
        for (WorldGauge world : worlds) total += world.getLoadedChunks();
        return total;
    }

    public int getEntities() {
        int total = 0;
        for (WorldGauge world : worlds) total += world.getEntities();
        return total;
    }

    public int getTileEntities() {
        int total = 0;
        for (WorldGauge world : worlds) total += world.getTileEntities();
        return total;
    }
}

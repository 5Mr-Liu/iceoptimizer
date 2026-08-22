package dev.rlcraft.ice.optimizer.render.telemetry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class PassProfile {
    private final long cpuInclusiveNanos;
    private final long cpuExclusiveNanos;
    private final long gpuNanos;
    private final Map<CpuWorkKind, Long> cpuKinds;
    private final Map<RenderCounter, Long> counters;

    PassProfile(long cpuInclusiveNanos, long cpuExclusiveNanos, long gpuNanos,
                Map<CpuWorkKind, Long> cpuKinds,
                Map<RenderCounter, Long> counters) {
        this.cpuInclusiveNanos = cpuInclusiveNanos;
        this.cpuExclusiveNanos = cpuExclusiveNanos;
        this.gpuNanos = gpuNanos;
        this.cpuKinds = Collections.unmodifiableMap(new EnumMap<CpuWorkKind, Long>(cpuKinds));
        this.counters = Collections.unmodifiableMap(new EnumMap<RenderCounter, Long>(counters));
    }

    public long getCpuInclusiveNanos() { return cpuInclusiveNanos; }
    public long getCpuExclusiveNanos() { return cpuExclusiveNanos; }
    public long getGpuNanos() { return gpuNanos; }
    public Map<CpuWorkKind, Long> getCpuKinds() { return cpuKinds; }
    public Map<RenderCounter, Long> getCounters() { return counters; }
}

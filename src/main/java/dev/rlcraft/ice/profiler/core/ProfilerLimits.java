package dev.rlcraft.ice.profiler.core;

import dev.rlcraft.ice.config.IceConfig;

public final class ProfilerLimits {
    private ProfilerLimits() {
    }

    public static int stackDictionaryEntries() {
        int budgetBound = Math.max(1024, IceConfig.general.maxProfilerMemoryMiB * 256);
        return Math.min(IceConfig.sampling.maxUniqueStacks, budgetBound);
    }

    public static int rollingSamples() {
        int budgetBound = Math.max(2000, IceConfig.general.maxProfilerMemoryMiB * 400);
        return Math.min(IceConfig.sampling.ringSamples, budgetBound);
    }

    public static int detailedSamples() {
        int budgetBound = Math.max(5000, IceConfig.general.maxProfilerMemoryMiB * 800);
        return Math.min(IceConfig.capture.maxDetailedSamples, budgetBound);
    }

    public static int samplesPerCapture() {
        return Math.max(1000, Math.min(12000, detailedSamples() / Math.max(1, IceConfig.capture.representativesPerCluster)));
    }
}

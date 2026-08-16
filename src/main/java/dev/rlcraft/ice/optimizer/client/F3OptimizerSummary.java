package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.ModuleStatus;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkRenderStatus;
import dev.rlcraft.ice.optimizer.runtime.RenderQueueStatus;
import dev.rlcraft.ice.optimizer.runtime.WorkerStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure formatter for the optimizer's intentionally small F3-only surface. */
public final class F3OptimizerSummary {
    private F3OptimizerSummary() {
    }

    public static boolean shouldRender(boolean configured, boolean debugScreenOpen) {
        return configured && debugScreenOpen;
    }

    public static List<String> format(ClientOptimizerStatus status) {
        if (status == null) return Collections.emptyList();
        int patched = 0;
        int hit = 0;
        int missed = 0;
        long errors = 0L;
        for (ModuleStatus module : status.getModules()) {
            OptimizationModule id = module.getModule();
            if (id == OptimizationModule.CORE_RUNTIME || id == OptimizationModule.RENDER_SUBMISSION) continue;
            if (module.getPatchedTargets() > 0) patched++;
            if (module.getObservedTargets() > module.getPatchedTargets()) missed++;
            if (module.getState() == dev.rlcraft.ice.optimizer.ModuleState.ACTIVE
                || module.getState() == dev.rlcraft.ice.optimizer.ModuleState.DEGRADED) hit++;
            errors += module.getFailures();
            errors += module.getRejected();
        }
        String core = status.isCoreModPresent() ? "OK" : "MISSING";
        List<String> lines = new ArrayList<String>(3);
        lines.add(String.format(Locale.ROOT,
            "ICE Opt: CORE %s | HIT %d | PATCH %d | MISS %d | ERR %d",
            core, hit, patched, missed, errors));

        ChunkRenderStatus chunk = status.getChunkRender();
        if (chunk != null) {
            lines.add(String.format(Locale.ROOT,
                "ICE Chunk: W %d>%d B%d | Sort %d | GPU %s %d/%d",
                chunk.getVanillaWorkers(), chunk.getEffectiveWorkers(), chunk.getRenderBuilders(),
                chunk.getSortedQuads(), chunk.getGpuBackend(), chunk.getGpuUploads(),
                chunk.getUploadFallbacks()));
        }

        WorkerStatus workers = status.getWorkers();
        RenderQueueStatus render = status.getRenderQueue();
        String cpuQueue = workers == null ? "OFF" : workers.getQueued() + "/" + workers.getQueueCapacity();
        String renderQueue = render == null ? "OFF" : render.getSize() + "/" + render.getCapacity();
        lines.add("ICE Q: CPU " + cpuQueue + " | Render " + renderQueue);
        return Collections.unmodifiableList(lines);
    }
}

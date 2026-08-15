package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.ModuleStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.lock.PackLockState;
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
        int operational = 0;
        for (ModuleStatus module : status.getModules()) {
            if (module.isOperational()) operational++;
        }
        PackLockStatus lock = status.getPackLock();
        String lockState = lock == null ? "UNKNOWN"
            : lock.getState() == PackLockState.CAPABILITY ? "STRUCTURAL" : lock.getState().name();
        List<String> lines = new ArrayList<String>(2);
        lines.add(String.format(Locale.ROOT, "ICE Opt: %s | ACTIVE %d/%d",
            lockState, operational, status.getModules().size()));

        WorkerStatus workers = status.getWorkers();
        RenderQueueStatus render = status.getRenderQueue();
        String cpuQueue = workers == null ? "OFF" : workers.getQueued() + "/" + workers.getQueueCapacity();
        String renderQueue = render == null ? "OFF" : render.getSize() + "/" + render.getCapacity();
        lines.add("ICE Q: CPU " + cpuQueue + " | Render " + renderQueue);
        return Collections.unmodifiableList(lines);
    }
}

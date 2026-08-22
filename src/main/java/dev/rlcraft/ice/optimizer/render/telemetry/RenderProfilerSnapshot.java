package dev.rlcraft.ice.optimizer.render.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RenderProfilerSnapshot {
    private final Map<RenderProfileKey, PassProfile> profiles;
    private final long droppedFrames;
    private final long droppedGpuQueries;
    private final long scopeErrors;

    RenderProfilerSnapshot(Map<RenderProfileKey, PassProfile> profiles,
                           long droppedFrames, long droppedGpuQueries,
                           long scopeErrors) {
        this.profiles = Collections.unmodifiableMap(
            new LinkedHashMap<RenderProfileKey, PassProfile>(profiles));
        this.droppedFrames = droppedFrames;
        this.droppedGpuQueries = droppedGpuQueries;
        this.scopeErrors = scopeErrors;
    }

    public Map<RenderProfileKey, PassProfile> getProfiles() { return profiles; }
    public long getDroppedFrames() { return droppedFrames; }
    public long getDroppedGpuQueries() { return droppedGpuQueries; }
    public long getScopeErrors() { return scopeErrors; }
}

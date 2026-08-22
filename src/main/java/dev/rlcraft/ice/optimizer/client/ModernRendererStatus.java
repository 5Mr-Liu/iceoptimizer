package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.render.backend.BackendStatus;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinatorStatus;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedgerStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class ModernRendererStatus {
    private final boolean initialized;
    private final String detail;
    private final CapabilityReport capabilities;
    private final FrameCoordinatorStatus coordinator;
    private final ResourceLedgerStatus resources;
    private final Map<OptimizationModule, BackendStatus> backends;

    ModernRendererStatus(boolean initialized, String detail,
                         CapabilityReport capabilities,
                         FrameCoordinatorStatus coordinator,
                         ResourceLedgerStatus resources,
                         Map<OptimizationModule, BackendStatus> backends) {
        this.initialized = initialized;
        this.detail = detail;
        this.capabilities = capabilities;
        this.coordinator = coordinator;
        this.resources = resources;
        this.backends = Collections.unmodifiableMap(
            new EnumMap<OptimizationModule, BackendStatus>(backends));
    }

    public boolean isInitialized() { return initialized; }
    public String getDetail() { return detail; }
    public CapabilityReport getCapabilities() { return capabilities; }
    public FrameCoordinatorStatus getCoordinator() { return coordinator; }
    public ResourceLedgerStatus getResources() { return resources; }
    public Map<OptimizationModule, BackendStatus> getBackends() { return backends; }
}

package dev.rlcraft.ice.optimizer;

public enum ModuleState {
    DISABLED,
    WAITING_FOR_TARGET,
    VERIFIED,
    ACTIVE,
    DEGRADED,
    INCOMPATIBLE,
    TRIPPED
}

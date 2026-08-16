package dev.rlcraft.ice.hooks;

/** Expected compatibility delegation; later independent adapters must still run. */
final class OptimizerAdapterSkippedException extends RuntimeException {
    OptimizerAdapterSkippedException(String message) {
        super(message);
    }
}

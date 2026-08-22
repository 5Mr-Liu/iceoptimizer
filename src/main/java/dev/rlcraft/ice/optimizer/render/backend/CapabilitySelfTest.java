package dev.rlcraft.ice.optimizer.render.backend;

/** Must execute on the render thread against a live compatibility context. */
public interface CapabilitySelfTest {
    CapabilityReport execute();
}

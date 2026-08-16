package dev.rlcraft.ice.hooks;

/** Core-JAR-local copy of the stable bytecode probe ABI. */
final class ProbeProtocol {
    static final String BRIDGE_INTERNAL_NAME = "dev/rlcraft/ice/profiler/probe/ProbeBridge";
    static final int ENTITY_TICK = 1;
    static final int TILE_ENTITY_TICK = 2;
    static final int EVENT_HANDLER = 3;
    static final int CHUNK_GENERATION = 4;
    static final int CHUNK_SAVE = 6;
    static final int CHUNK_RENDER = 9;

    private ProbeProtocol() {
    }
}

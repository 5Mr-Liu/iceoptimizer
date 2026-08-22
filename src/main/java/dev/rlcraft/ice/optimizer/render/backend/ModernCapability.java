package dev.rlcraft.ice.optimizer.render.backend;

public enum ModernCapability {
    OFFSCREEN_FRAMEBUFFER,
    BUFFER_OBJECT,
    BUFFER_STORAGE,
    PERSISTENT_MAPPING,
    COHERENT_MAPPING,
    MULTI_DRAW,
    MULTI_DRAW_INDIRECT,
    BASE_INSTANCE,
    SHADER_STORAGE_BUFFER,
    SYNC_FENCE,
    TIMER_QUERY,
    /** A bounded compatibility GLSL program compiled and linked successfully. */
    SHADER_PROGRAM,
    PIXEL_UNPACK_BUFFER,
    CONSERVATIVE_HZB,
    /** Exact fixed-function model vertex format rendered from a VBO into an FBO. */
    MODEL_MESH_VBO,
    /** Compatibility vertex shader plus executable instanced billboard output. */
    PARTICLE_INSTANCING,
    /** Exact 1.12.2 BLOCK client arrays submitted from a VBO into an FBO. */
    FBP_PACKET_VBO
}

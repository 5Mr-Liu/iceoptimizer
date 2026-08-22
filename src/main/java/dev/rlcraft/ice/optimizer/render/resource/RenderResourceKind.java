package dev.rlcraft.ice.optimizer.render.resource;

public enum RenderResourceKind {
    BUFFER,
    TEXTURE,
    VERTEX_ARRAY,
    PROGRAM,
    QUERY,
    FRAMEBUFFER,
    RENDERBUFFER,
    /** Append-only: short-lived compiled shader stage objects. */
    SHADER
}

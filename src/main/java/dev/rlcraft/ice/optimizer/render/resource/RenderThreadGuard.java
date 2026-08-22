package dev.rlcraft.ice.optimizer.render.resource;

/** Explicit ownership guard for every modern OpenGL lifecycle operation. */
public final class RenderThreadGuard {
    private final Thread owner;

    private RenderThreadGuard(Thread owner) {
        if (owner == null) throw new IllegalArgumentException("owner");
        this.owner = owner;
    }

    public static RenderThreadGuard captureCurrent() {
        return new RenderThreadGuard(Thread.currentThread());
    }

    public boolean isRenderThread() {
        return Thread.currentThread() == owner;
    }

    public void check() {
        if (!isRenderThread()) {
            throw new IllegalStateException("OpenGL lifecycle operation attempted from "
                + Thread.currentThread().getName() + "; render owner is " + owner.getName());
        }
    }

    public Thread getOwner() {
        return owner;
    }
}

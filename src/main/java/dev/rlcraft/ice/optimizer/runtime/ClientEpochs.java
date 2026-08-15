package dev.rlcraft.ice.optimizer.runtime;

import java.util.concurrent.atomic.AtomicLong;

/** Generation counters prevent late async results from entering a new world or GL/resource context. */
public final class ClientEpochs {
    private final AtomicLong frameId = new AtomicLong();
    private final AtomicLong clientTickId = new AtomicLong();
    private final AtomicLong worldGeneration = new AtomicLong(1L);
    private final AtomicLong resourceGeneration = new AtomicLong(1L);
    private final AtomicLong glContextGeneration = new AtomicLong(1L);

    public long nextFrame() { return frameId.incrementAndGet(); }
    public long nextClientTick() { return clientTickId.incrementAndGet(); }
    public long invalidateWorld() { return worldGeneration.incrementAndGet(); }
    public long invalidateResources() { return resourceGeneration.incrementAndGet(); }
    public long invalidateGlContext() { return glContextGeneration.incrementAndGet(); }

    public long currentFrameId() { return frameId.get(); }
    public long currentClientTickId() { return clientTickId.get(); }
    public long currentWorldGeneration() { return worldGeneration.get(); }
    public long currentResourceGeneration() { return resourceGeneration.get(); }
    public long currentGlContextGeneration() { return glContextGeneration.get(); }

    public EpochToken snapshot() {
        return new EpochToken(frameId.get(), clientTickId.get(), worldGeneration.get(), resourceGeneration.get(), glContextGeneration.get());
    }

    public boolean isCurrent(EpochToken token, int mask) {
        if (token == null) return mask == EpochMask.NONE;
        if ((mask & EpochMask.FRAME) != 0 && token.getFrameId() != frameId.get()) return false;
        if ((mask & EpochMask.CLIENT_TICK) != 0 && token.getClientTickId() != clientTickId.get()) return false;
        if ((mask & EpochMask.WORLD) != 0 && token.getWorldGeneration() != worldGeneration.get()) return false;
        if ((mask & EpochMask.RESOURCE) != 0 && token.getResourceGeneration() != resourceGeneration.get()) return false;
        return (mask & EpochMask.GL_CONTEXT) == 0 || token.getGlContextGeneration() == glContextGeneration.get();
    }
}

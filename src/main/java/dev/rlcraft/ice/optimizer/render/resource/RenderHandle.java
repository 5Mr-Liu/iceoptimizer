package dev.rlcraft.ice.optimizer.render.resource;

/**
 * Generation-qualified resource handle. A raw GL name is never sufficient to
 * identify an ICE resource because drivers may reuse names after deletion.
 */
public final class RenderHandle {
    private final long logicalId;
    private final long serial;
    private final int nativeId;
    private final RenderResourceKind kind;
    private final long bytes;
    private final long resourceGeneration;
    private final long contextGeneration;

    RenderHandle(long logicalId, long serial, int nativeId, RenderResourceKind kind,
                 long bytes, long resourceGeneration, long contextGeneration) {
        this.logicalId = logicalId;
        this.serial = serial;
        this.nativeId = nativeId;
        this.kind = kind;
        this.bytes = bytes;
        this.resourceGeneration = resourceGeneration;
        this.contextGeneration = contextGeneration;
    }

    public long getLogicalId() { return logicalId; }
    public long getSerial() { return serial; }
    public int getNativeId() { return nativeId; }
    public RenderResourceKind getKind() { return kind; }
    public long getBytes() { return bytes; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getContextGeneration() { return contextGeneration; }

    public boolean belongsTo(long resources, long context) {
        return resourceGeneration == resources && contextGeneration == context;
    }

    @Override
    public int hashCode() {
        long mixed = logicalId ^ (logicalId >>> 32) ^ serial ^ (serial >>> 32);
        return (int) mixed;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof RenderHandle)) return false;
        RenderHandle other = (RenderHandle) value;
        return logicalId == other.logicalId && serial == other.serial
            && contextGeneration == other.contextGeneration;
    }
}

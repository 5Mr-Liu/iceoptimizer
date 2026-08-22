package dev.rlcraft.ice.optimizer.render.resource;

public final class ResourceLedgerStatus {
    private final int live;
    private final int retired;
    private final long liveBytes;
    private final long created;
    private final long destroyed;
    private final long abandoned;
    private final long rejected;
    private final long timedOut;

    ResourceLedgerStatus(int live, int retired, long liveBytes, long created,
                         long destroyed, long abandoned, long rejected,
                         long timedOut) {
        this.live = live;
        this.retired = retired;
        this.liveBytes = liveBytes;
        this.created = created;
        this.destroyed = destroyed;
        this.abandoned = abandoned;
        this.rejected = rejected;
        this.timedOut = timedOut;
    }

    public int getLive() { return live; }
    public int getRetired() { return retired; }
    public long getLiveBytes() { return liveBytes; }
    public long getCreated() { return created; }
    public long getDestroyed() { return destroyed; }
    public long getAbandoned() { return abandoned; }
    public long getRejected() { return rejected; }
    public long getTimedOut() { return timedOut; }
}

package dev.rlcraft.ice.optimizer.render.telemetry;

/** Render-thread timestamp-query API; availability checks must never wait. */
public interface GpuTimestampDriver {
    int createQuery();
    void timestamp(int queryId);
    boolean isAvailable(int queryId);
    long resultNanos(int queryId);
    void deleteQuery(int queryId);
}

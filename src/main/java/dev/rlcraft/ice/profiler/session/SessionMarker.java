package dev.rlcraft.ice.profiler.session;

public final class SessionMarker {
    private final long epochMillis;
    private final long elapsedMillis;
    private final String text;

    public SessionMarker(long epochMillis, long elapsedMillis, String text) {
        this.epochMillis = epochMillis;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.text = text == null || text.trim().isEmpty() ? "用户标记" : text.trim();
    }

    public long getEpochMillis() { return epochMillis; }
    public long getElapsedMillis() { return elapsedMillis; }
    public String getText() { return text; }
}

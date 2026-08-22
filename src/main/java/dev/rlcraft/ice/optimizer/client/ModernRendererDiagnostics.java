package dev.rlcraft.ice.optimizer.client;

/**
 * Cross-thread, dependency-free publication surface for profiler reports.
 * The render thread builds immutable strings; report writers only read the
 * latest volatile references and never inspect live GL/backend objects.
 */
public final class ModernRendererDiagnostics {
    private static volatile String report = "";
    private static volatile String summary = "";

    private ModernRendererDiagnostics() {
    }

    static void publish(String nextReport, String nextSummary) {
        report = bounded(nextReport, 65536);
        summary = bounded(nextSummary, 512);
    }

    public static String report() { return report; }
    public static String summary() { return summary; }

    static void resetForTest() {
        report = "";
        summary = "";
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

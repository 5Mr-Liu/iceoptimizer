package dev.rlcraft.ice.optimizer.render.optifine;

public final class ShaderCompilationResult {
    private static final int MAX_LOG_CHARS = 16384;
    private final boolean linked;
    private final String log;

    public ShaderCompilationResult(boolean linked, String log) {
        this.linked = linked;
        String value = log == null ? "" : log;
        this.log = value.length() <= MAX_LOG_CHARS ? value
            : value.substring(0, MAX_LOG_CHARS);
    }

    public boolean isLinked() { return linked; }
    public String getLog() { return log; }
}

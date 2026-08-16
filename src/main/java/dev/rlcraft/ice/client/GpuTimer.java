package dev.rlcraft.ice.client;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import java.lang.reflect.Method;

final class GpuTimer {
    private static final int GL_TIME_ELAPSED = 0x88BF;
    private static final int GL_QUERY_RESULT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;
    private final int[] queryIds = new int[3];
    private final boolean[] pending = new boolean[3];
    private Method beginQuery;
    private Method endQuery;
    private Method getAvailable;
    private Method getResult;
    private int cursor;
    private boolean initialized;
    private boolean disabled;
    private boolean active;
    private double latestMillis = -1.0D;

    void begin() {
        if (!IceConfig.client.gpuTimerQueries || disabled) return;
        try {
            initialize();
            if (disabled) return;
            if (pending[cursor]) {
                int available = ((Number) getAvailable.invoke(null, Integer.valueOf(queryIds[cursor]), Integer.valueOf(GL_QUERY_RESULT_AVAILABLE))).intValue();
                if (available == 0) return;
                long nanos = ((Number) getResult.invoke(null, Integer.valueOf(queryIds[cursor]), Integer.valueOf(GL_QUERY_RESULT))).longValue();
                latestMillis = nanos / 1_000_000.0D;
                pending[cursor] = false;
            }
            beginQuery.invoke(null, Integer.valueOf(GL_TIME_ELAPSED), Integer.valueOf(queryIds[cursor]));
            active = true;
        } catch (Throwable error) {
            disable(error);
        }
    }

    void end() {
        if (!active || disabled) return;
        try {
            endQuery.invoke(null, Integer.valueOf(GL_TIME_ELAPSED));
            pending[cursor] = true;
            cursor = (cursor + 1) % queryIds.length;
        } catch (Throwable error) {
            disable(error);
        } finally {
            active = false;
        }
    }

    double getLatestMillis() { return latestMillis; }

    private void initialize() throws Exception {
        if (initialized) return;
        initialized = true;
        Class<?> gl15 = Class.forName("org.lwjgl.opengl.GL15");
        Class<?> arb = Class.forName("org.lwjgl.opengl.ARBTimerQuery");
        Method generate = gl15.getMethod("glGenQueries");
        beginQuery = gl15.getMethod("glBeginQuery", int.class, int.class);
        endQuery = gl15.getMethod("glEndQuery", int.class);
        getAvailable = gl15.getMethod("glGetQueryObjecti", int.class, int.class);
        getResult = arb.getMethod("glGetQueryObjecti64", int.class, int.class);
        for (int i = 0; i < queryIds.length; i++) queryIds[i] = ((Number) generate.invoke(null)).intValue();
    }

    private void disable(Throwable error) {
        disabled = true;
        active = false;
        IceProfilerMod.LOGGER.debug("GPU timer query 不可用，已自动关闭该采集器", error);
    }
}

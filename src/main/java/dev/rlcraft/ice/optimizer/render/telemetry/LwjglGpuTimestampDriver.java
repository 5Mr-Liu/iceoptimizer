package dev.rlcraft.ice.optimizer.render.telemetry;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL15;

/** LWJGL2 ARB/core timestamp implementation. */
public final class LwjglGpuTimestampDriver implements GpuTimestampDriver {
    private static final int GL_TIMESTAMP = 0x8E28;
    private static final int GL_QUERY_RESULT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;

    @Override public int createQuery() { return GL15.glGenQueries(); }

    @Override public void timestamp(int queryId) {
        ARBTimerQuery.glQueryCounter(queryId, GL_TIMESTAMP);
    }

    @Override public boolean isAvailable(int queryId) {
        return GL15.glGetQueryObjecti(queryId, GL_QUERY_RESULT_AVAILABLE) != 0;
    }

    @Override public long resultNanos(int queryId) {
        return ARBTimerQuery.glGetQueryObjecti64(queryId, GL_QUERY_RESULT);
    }

    @Override public void deleteQuery(int queryId) { GL15.glDeleteQueries(queryId); }
}

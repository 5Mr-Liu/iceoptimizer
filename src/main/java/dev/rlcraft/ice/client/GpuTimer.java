package dev.rlcraft.ice.client;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Three-slot non-blocking GPU timer with Context-qualified Query ownership. */
final class GpuTimer {
    private static final int GL_TIME_ELAPSED = 0x88BF;
    private static final int GL_QUERY_RESULT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;
    private final int[] queryIds = new int[3];
    private final boolean[] pending = new boolean[3];
    private final QueryDriver driver;
    private Object contextIdentity;
    private int cursor;
    private boolean initialized;
    private boolean disabled;
    private boolean active;
    private double latestMillis = -1.0D;

    GpuTimer() {
        this(new ReflectiveQueryDriver());
    }

    GpuTimer(QueryDriver driver) {
        if (driver == null) throw new IllegalArgumentException("query driver");
        this.driver = driver;
    }

    void begin() {
        if (!IceConfig.client.gpuTimerQueries) {
            if (initialized || ownsAnyQuery()) close();
            return;
        }
        try {
            Object currentContext = driver.currentContext();
            if (currentContext == null) {
                throw new IllegalStateException("OpenGL Context unavailable");
            }
            if (contextIdentity != currentContext) {
                // The old Context is already gone. Its Query names must never
                // be deleted through, or reused in, the newly current Context.
                abandonQueries();
                contextIdentity = currentContext;
                disabled = false;
            }
            if (disabled) return;
            initialize();
            if (pending[cursor]) {
                if (!driver.isAvailable(queryIds[cursor])) return;
                latestMillis = driver.resultNanos(queryIds[cursor])
                    / 1_000_000.0D;
                pending[cursor] = false;
            }
            driver.begin(queryIds[cursor]);
            active = true;
        } catch (Throwable error) {
            disable(error);
        }
    }

    void end() {
        if (!active || disabled) return;
        try {
            driver.end();
            pending[cursor] = true;
            cursor = (cursor + 1) % queryIds.length;
        } catch (Throwable error) {
            disable(error);
        } finally {
            active = false;
        }
    }

    double getLatestMillis() { return latestMillis; }

    /** Deletes known Query names only while their exact Context is current. */
    void close() {
        if (!initialized && !ownsAnyQuery()) {
            active = false;
            return;
        }
        Throwable failure = null;
        boolean deleteSafe = false;
        try {
            deleteSafe = contextIdentity != null
                && driver.currentContext() == contextIdentity;
        } catch (Throwable error) {
            failure = append(failure, error);
        }
        if (active) {
            active = false;
            if (deleteSafe) try { driver.end(); }
            catch (Throwable error) {
                failure = append(failure, error);
                deleteSafe = false;
            }
        }
        for (int index = 0; index < queryIds.length; index++) {
            int queryId = queryIds[index];
            queryIds[index] = 0;
            pending[index] = false;
            if (queryId == 0 || !deleteSafe) continue;
            try { driver.delete(queryId); }
            catch (Throwable error) {
                failure = append(failure, error);
                if (FatalErrors.findFatal(error) != null) {
                    // Clear all remaining Java ownership before fatal escape,
                    // without issuing more driver work while the JVM is sick.
                    Arrays.fill(queryIds, 0);
                    break;
                }
            }
        }
        Arrays.fill(pending, false);
        initialized = false;
        cursor = 0;
        latestMillis = -1.0D;
        if (failure != null) {
            disable(failure);
        } else {
            disabled = false;
        }
    }

    private void initialize() throws Exception {
        if (initialized) return;
        initialized = true;
        for (int index = 0; index < queryIds.length; index++) {
            int queryId = driver.create();
            if (queryId <= 0) {
                throw new IllegalStateException("invalid GPU timer Query id");
            }
            queryIds[index] = queryId;
        }
    }

    private void abandonQueries() {
        Arrays.fill(queryIds, 0);
        Arrays.fill(pending, false);
        initialized = false;
        active = false;
        cursor = 0;
        latestMillis = -1.0D;
    }

    private boolean ownsAnyQuery() {
        for (int queryId : queryIds) if (queryId != 0) return true;
        return false;
    }

    private void disable(Throwable error) {
        disabled = true;
        active = false;
        FatalErrors.rethrowIfFatal(error);
        IceProfilerMod.LOGGER.debug(
            "GPU timer query 不可用，已自动关闭该采集器", error);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    interface QueryDriver {
        Object currentContext() throws Exception;
        int create() throws Exception;
        void begin(int queryId) throws Exception;
        void end() throws Exception;
        boolean isAvailable(int queryId) throws Exception;
        long resultNanos(int queryId) throws Exception;
        void delete(int queryId) throws Exception;
    }

    private static final class ReflectiveQueryDriver implements QueryDriver {
        private Method currentContext;
        private Method generate;
        private Method begin;
        private Method end;
        private Method available;
        private Method result;
        private Method delete;

        @Override public Object currentContext() throws Exception {
            resolve();
            return currentContext.invoke(null);
        }

        @Override public int create() throws Exception {
            resolve();
            return ((Number) generate.invoke(null)).intValue();
        }

        @Override public void begin(int queryId) throws Exception {
            resolve();
            begin.invoke(null, Integer.valueOf(GL_TIME_ELAPSED),
                Integer.valueOf(queryId));
        }

        @Override public void end() throws Exception {
            resolve();
            end.invoke(null, Integer.valueOf(GL_TIME_ELAPSED));
        }

        @Override public boolean isAvailable(int queryId) throws Exception {
            resolve();
            return ((Number) available.invoke(null, Integer.valueOf(queryId),
                Integer.valueOf(GL_QUERY_RESULT_AVAILABLE))).intValue() != 0;
        }

        @Override public long resultNanos(int queryId) throws Exception {
            resolve();
            return ((Number) result.invoke(null, Integer.valueOf(queryId),
                Integer.valueOf(GL_QUERY_RESULT))).longValue();
        }

        @Override public void delete(int queryId) throws Exception {
            resolve();
            delete.invoke(null, Integer.valueOf(queryId));
        }

        private void resolve() throws Exception {
            if (generate != null) return;
            Class<?> glContext = Class.forName("org.lwjgl.opengl.GLContext");
            Class<?> gl15 = Class.forName("org.lwjgl.opengl.GL15");
            Class<?> arb = Class.forName("org.lwjgl.opengl.ARBTimerQuery");
            currentContext = glContext.getMethod("getCapabilities");
            generate = gl15.getMethod("glGenQueries");
            begin = gl15.getMethod("glBeginQuery", int.class, int.class);
            end = gl15.getMethod("glEndQuery", int.class);
            available = gl15.getMethod("glGetQueryObjecti", int.class,
                int.class);
            result = arb.getMethod("glGetQueryObjecti64", int.class,
                int.class);
            delete = gl15.getMethod("glDeleteQueries", int.class);
        }
    }
}

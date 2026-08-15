package dev.rlcraft.ice.optimizer.compat.xaero;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/**
 * Replaces Xaero's thousands of startup glFinish benchmarks with timestamp
 * queries whose results are consumed only after the driver reports readiness.
 */
public final class XaeroGpuTimerBridge {
    private static final String UPLOAD_MODULE = "xaero-texture-upload";
    private static final String FENCE_MODULE = "xaero-gpu-fence";
    private static final int TYPE_COUNT = 7;
    private static final int[] SAMPLE_TARGETS = { 512, 512, 512, 256, 256, 256, 256 };
    private static final long[] DEFAULT_ESTIMATES = {
        1_000_000L, 1_000_000L, 1_000_000L, 3_000_000L, 4_000_000L, 1_000_000L, 1_000_000L
    };
    private static final int QUERY_PAIR_COUNT = 32;
    private static final int GL_TIMESTAMP = 36392;
    private static final long MAX_VALID_SAMPLE_NANOS = 1_000_000_000L;
    private static final QueryPair[] QUERIES = new QueryPair[QUERY_PAIR_COUNT];
    private static final long[] accumulatedNanos = new long[TYPE_COUNT];
    private static final int[] completedSamples = new int[TYPE_COUNT];

    private static Object knownBenchmark;
    private static ContextCapabilities knownCapabilities;
    private static long knownContextGeneration = Long.MIN_VALUE;
    private static int queryCursor;
    private static QueryPair currentQuery;
    private static boolean activated;

    private static Class<?> fallbackClass;
    private static Method fallbackIsFinished;
    private static Method fallbackGetAverage;
    private static Method fallbackPre;
    private static Method fallbackPost;

    private XaeroGpuTimerBridge() {
    }

    public static boolean isFinished(Object benchmark, int type) {
        if (!validType(type) || !enabled()) return originalIsFinished(benchmark, type);
        try {
            if (!prepare(benchmark)) return originalIsFinished(benchmark, type);
            pollQueries(8);
            return completedSamples[type] >= SAMPLE_TARGETS[type];
        } catch (Throwable error) {
            fail(error);
            return originalIsFinished(benchmark, type);
        }
    }

    public static long getAverage(Object benchmark, int type) {
        if (!validType(type) || !enabled()) return originalGetAverage(benchmark, type);
        try {
            if (!prepare(benchmark)) return originalGetAverage(benchmark, type);
            pollQueries(8);
            int count = completedSamples[type];
            return count == 0 ? DEFAULT_ESTIMATES[type] : accumulatedNanos[type] / count;
        } catch (Throwable error) {
            fail(error);
            return originalGetAverage(benchmark, type);
        }
    }

    public static void beforeBatch() {
        Object benchmark = knownBenchmark;
        if (!enabled() || benchmark == null) {
            GL11.glFinish();
            return;
        }
        try {
            if (!prepare(benchmark)) {
                GL11.glFinish();
                return;
            }
            pollQueries(QUERY_PAIR_COUNT);
        } catch (Throwable error) {
            fail(error);
            GL11.glFinish();
        }
    }

    public static void begin(Object benchmark, int type) {
        if (!validType(type) || !enabled()) {
            originalPre(benchmark);
            return;
        }
        try {
            if (!prepare(benchmark)) {
                originalPre(benchmark);
                return;
            }
            pollQueries(4);
            abandonUnclosedQuery();
            QueryPair query = acquireQuery();
            if (query == null) return;
            issueTimestamp(query.startId);
            query.cpuStartedNanos = System.nanoTime();
            currentQuery = query;
        } catch (Throwable error) {
            currentQuery = null;
            fail(error);
            originalPre(benchmark);
        }
    }

    public static void end(Object benchmark, int type) {
        if (!validType(type) || !enabled()) {
            originalPost(benchmark, type);
            return;
        }
        QueryPair endingQuery = null;
        try {
            if (!prepare(benchmark)) {
                originalPost(benchmark, type);
                return;
            }
            QueryPair query = currentQuery;
            currentQuery = null;
            if (query == null) return;
            endingQuery = query;
            query.cpuNanos = Math.max(1L, System.nanoTime() - query.cpuStartedNanos);
            issueTimestamp(query.endId);
            query.type = type;
            query.inFlight = true;
            pollQueries(4);
            if (!activated) {
                activated = true;
                OptimizerBridge.activate(UPLOAD_MODULE, "Xaero 异步纹理上传计时已启用");
                OptimizerBridge.activate(FENCE_MODULE, "Xaero glFinish 基准已替换为非阻塞 GPU Timestamp");
            }
        } catch (Throwable error) {
            currentQuery = null;
            if (endingQuery != null) endingQuery.usable = false;
            fail(error);
        }
    }

    private static boolean enabled() {
        return OptimizerBridge.isEnabled(UPLOAD_MODULE) && OptimizerBridge.isEnabled(FENCE_MODULE);
    }

    private static boolean prepare(Object benchmark) {
        if (benchmark == null) return false;
        ContextCapabilities capabilities = GLContext.getCapabilities();
        long generation = OptimizerBridge.currentGlContextGeneration();
        if (generation != knownContextGeneration || capabilities != knownCapabilities) {
            resetContext(generation, capabilities);
        }
        if (!(capabilities.OpenGL33 || capabilities.GL_ARB_timer_query)) return false;
        if (knownBenchmark != benchmark) resetBenchmark(benchmark);
        return true;
    }

    private static void resetContext(long generation, ContextCapabilities capabilities) {
        knownContextGeneration = generation;
        knownCapabilities = capabilities;
        currentQuery = null;
        queryCursor = 0;
        for (int i = 0; i < QUERIES.length; i++) {
            QueryPair query = QUERIES[i];
            if (query != null) query.releaseBudget();
            QUERIES[i] = null;
        }
    }

    private static void resetBenchmark(Object benchmark) {
        knownBenchmark = benchmark;
        Arrays.fill(accumulatedNanos, 0L);
        Arrays.fill(completedSamples, 0);
        if (currentQuery != null) currentQuery.usable = false;
        currentQuery = null;
        for (QueryPair query : QUERIES) {
            if (query != null && query.inFlight) query.type = -1;
        }
    }

    private static QueryPair acquireQuery() {
        for (int checked = 0; checked < QUERY_PAIR_COUNT; checked++) {
            int index = (queryCursor + checked) % QUERY_PAIR_COUNT;
            QueryPair query = QUERIES[index];
            if (query == null) {
                query = QueryPair.create();
                if (query == null) continue;
                QUERIES[index] = query;
            }
            if (query.usable && !query.inFlight && query != currentQuery) {
                queryCursor = (index + 1) % QUERY_PAIR_COUNT;
                return query;
            }
        }
        return null;
    }

    private static void abandonUnclosedQuery() {
        QueryPair query = currentQuery;
        currentQuery = null;
        if (query == null) return;
        try {
            issueTimestamp(query.endId);
            query.type = -1;
            query.cpuNanos = 0L;
            query.inFlight = true;
        } catch (Throwable error) {
            query.usable = false;
            throw error;
        }
    }

    private static void pollQueries(int maximumChecks) {
        int checked = 0;
        for (int offset = 0; offset < QUERY_PAIR_COUNT && checked < maximumChecks; offset++) {
            QueryPair query = QUERIES[(queryCursor + offset) % QUERY_PAIR_COUNT];
            if (query == null || !query.inFlight) continue;
            checked++;
            if (GL15.glGetQueryObjecti(query.endId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long started = queryResult(query.startId);
            long ended = queryResult(query.endId);
            query.inFlight = false;
            int type = query.type;
            query.type = -1;
            if (!validType(type) || ended <= started) continue;
            long gpuNanos = ended - started;
            long sample = Math.max(gpuNanos, query.cpuNanos);
            if (sample <= 0L || sample > MAX_VALID_SAMPLE_NANOS) continue;
            accumulatedNanos[type] += sample;
            completedSamples[type]++;
        }
    }

    private static void issueTimestamp(int queryId) {
        if (knownCapabilities.OpenGL33) GL33.glQueryCounter(queryId, GL_TIMESTAMP);
        else ARBTimerQuery.glQueryCounter(queryId, GL_TIMESTAMP);
    }

    private static long queryResult(int queryId) {
        if (knownCapabilities.OpenGL33) return GL33.glGetQueryObjectui64(queryId, GL15.GL_QUERY_RESULT);
        return ARBTimerQuery.glGetQueryObjectui64(queryId, GL15.GL_QUERY_RESULT);
    }

    private static boolean validType(int type) {
        return type >= 0 && type < TYPE_COUNT;
    }

    private static void fail(Throwable error) {
        OptimizerBridge.failure(UPLOAD_MODULE, error);
        OptimizerBridge.failure(FENCE_MODULE, error);
    }

    private static boolean originalIsFinished(Object benchmark, int type) {
        try {
            resolveFallback(benchmark);
            return ((Boolean) fallbackIsFinished.invoke(benchmark, type)).booleanValue();
        } catch (Throwable error) {
            throw propagate(error);
        }
    }

    private static long originalGetAverage(Object benchmark, int type) {
        try {
            resolveFallback(benchmark);
            return ((Long) fallbackGetAverage.invoke(benchmark, type)).longValue();
        } catch (Throwable error) {
            throw propagate(error);
        }
    }

    private static void originalPre(Object benchmark) {
        try {
            resolveFallback(benchmark);
            fallbackPre.invoke(benchmark);
        } catch (Throwable error) {
            throw propagate(error);
        }
    }

    private static void originalPost(Object benchmark, int type) {
        try {
            resolveFallback(benchmark);
            fallbackPost.invoke(benchmark, type);
        } catch (Throwable error) {
            throw propagate(error);
        }
    }

    private static synchronized void resolveFallback(Object benchmark) throws Exception {
        if (benchmark == null) throw new IllegalArgumentException("Xaero benchmark 为空");
        Class<?> type = benchmark.getClass();
        if (fallbackClass == type) return;
        Method isFinished = type.getMethod("isFinished", int.class);
        Method getAverage = type.getMethod("getAverage", int.class);
        Method pre = type.getMethod("pre");
        Method post = type.getMethod("post", int.class);
        fallbackIsFinished = isFinished;
        fallbackGetAverage = getAverage;
        fallbackPre = pre;
        fallbackPost = post;
        fallbackClass = type;
    }

    private static RuntimeException propagate(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new IllegalStateException(cause);
    }

    private static final class QueryPair {
        private final int startId;
        private final int endId;
        private final CacheBudget.Reservation reservation;
        private boolean inFlight;
        private boolean usable = true;
        private int type = -1;
        private long cpuStartedNanos;
        private long cpuNanos;

        private QueryPair(int startId, int endId, CacheBudget.Reservation reservation) {
            this.startId = startId;
            this.endId = endId;
            this.reservation = reservation;
        }

        private static QueryPair create() {
            CacheBudget.Reservation reservation = ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU, 128L);
            if (reservation == null) return null;
            int start = 0;
            try {
                start = GL15.glGenQueries();
                int end = GL15.glGenQueries();
                return new QueryPair(start, end, reservation);
            } catch (Throwable error) {
                if (start != 0) {
                    try { GL15.glDeleteQueries(start); } catch (Throwable ignored) { }
                }
                reservation.close();
                throw error;
            }
        }

        private void releaseBudget() {
            reservation.close();
        }
    }
}

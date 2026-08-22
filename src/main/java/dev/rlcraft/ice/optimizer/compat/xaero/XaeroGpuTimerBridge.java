package dev.rlcraft.ice.optimizer.compat.xaero;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
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
    static final long QUERY_PAIR_GPU_BYTES = Math.multiplyExact(2L,
        ResourceLedger.nativeObjectCharge(RenderResourceKind.QUERY));
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
    private static Object fallbackTimingBenchmark;
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
            beginOriginal(benchmark);
            return;
        }
        QueryPair query = null;
        try {
            if (!prepare(benchmark)) {
                beginOriginal(benchmark);
                return;
            }
            pollQueries(4);
            abandonUnclosedQuery();
            query = acquireQuery();
            if (query == null) {
                if (!hasPendingQuery()) {
                    throw new IllegalStateException(
                        "Xaero timestamp query capacity unavailable");
                }
                return;
            }
            query.issueStart();
            query.cpuStartedNanos = System.nanoTime();
            currentQuery = query;
        } catch (Throwable error) {
            if (query != null) query.quarantine();
            currentQuery = null;
            fail(error);
            beginOriginal(benchmark);
        }
    }

    public static void end(Object benchmark, int type) {
        if (fallbackTimingBenchmark == benchmark) {
            fallbackTimingBenchmark = null;
            originalPost(benchmark, type);
            return;
        }
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
            query.issueEnd();
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
            if (endingQuery != null) endingQuery.quarantine();
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
            if (query != null) query.abandon();
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

    /** Abandons old-context Query ownership immediately at Context loss. */
    public static void contextLost(long lostGeneration) {
        if (knownContextGeneration != lostGeneration) return;
        resetContext(Long.MIN_VALUE, null);
        resetBenchmark(null);
        fallbackTimingBenchmark = null;
        activated = false;
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
            query.issueEnd();
            query.type = -1;
            query.cpuNanos = 0L;
            query.inFlight = true;
        } catch (Throwable error) {
            query.quarantine();
            throw error;
        }
    }

    private static boolean hasPendingQuery() {
        if (currentQuery != null) return true;
        for (QueryPair query : QUERIES) {
            if (query != null && query.inFlight) return true;
        }
        return false;
    }

    private static void pollQueries(int maximumChecks) {
        int checked = 0;
        for (int offset = 0; offset < QUERY_PAIR_COUNT && checked < maximumChecks; offset++) {
            QueryPair query = QUERIES[(queryCursor + offset) % QUERY_PAIR_COUNT];
            if (query == null || !query.inFlight) continue;
            checked++;
            try {
                if (!query.resultAvailable()) continue;
                long started = query.startResult();
                long ended = query.endResult();
                query.inFlight = false;
                int type = query.type;
                query.type = -1;
                if (!validType(type) || ended <= started) continue;
                long gpuNanos = ended - started;
                long sample = Math.max(gpuNanos, query.cpuNanos);
                if (sample <= 0L || sample > MAX_VALID_SAMPLE_NANOS) continue;
                accumulatedNanos[type] += sample;
                completedSamples[type]++;
            } catch (Throwable error) {
                query.quarantine();
                rethrow(error);
                return;
            }
        }
    }

    private static boolean validType(int type) {
        return type >= 0 && type < TYPE_COUNT;
    }

    private static void fail(Throwable error) {
        OptimizerBridge.failure(UPLOAD_MODULE, error);
        OptimizerBridge.failure(FENCE_MODULE, error);
    }

    private static void beginOriginal(Object benchmark) {
        originalPre(benchmark);
        fallbackTimingBenchmark = benchmark;
    }

    /** Releases Query ownership at the client render-thread shutdown boundary. */
    public static void shutdown() {
        boolean contextValid = false;
        try {
            contextValid = knownCapabilities != null
                && GLContext.getCapabilities() == knownCapabilities
                && OptimizerBridge.currentGlContextGeneration()
                    == knownContextGeneration;
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            contextValid = false;
        }
        Throwable failure = null;
        currentQuery = null;
        for (int index = 0; index < QUERIES.length; index++) {
            QueryPair query = QUERIES[index];
            if (query == null) continue;
            try {
                if (contextValid) query.destroy();
                else query.abandon();
                QUERIES[index] = null;
            } catch (Throwable error) {
                // A throwing GL deletion has an uncertain commit outcome and
                // must never be retried in this context.  Retain the pair so a
                // later context-loss reset can at least abandon its accounting
                // instead of silently losing the last ownership witness.
                failure = appendFailure(failure, error);
            }
        }
        Arrays.fill(accumulatedNanos, 0L);
        Arrays.fill(completedSamples, 0);
        knownBenchmark = null;
        knownCapabilities = null;
        knownContextGeneration = Long.MIN_VALUE;
        queryCursor = 0;
        fallbackTimingBenchmark = null;
        activated = false;
        fallbackClass = null;
        fallbackIsFinished = null;
        fallbackGetAverage = null;
        fallbackPre = null;
        fallbackPost = null;
        if (failure != null) fail(failure);
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
        FatalErrors.rethrowIfFatal(error);
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new IllegalStateException(cause);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("Xaero GPU timer cleanup failed", failure);
    }

    interface QueryDriver {
        int create();
        void delete(int queryId);
        void issue(int queryId);
        boolean available(int queryId);
        long result(int queryId);
    }

    private static final QueryDriver LWJGL_QUERY_DRIVER = new QueryDriver() {
        @Override public int create() { return GL15.glGenQueries(); }
        @Override public void delete(int queryId) {
            GL15.glDeleteQueries(queryId);
        }
        @Override public void issue(int queryId) {
            if (knownCapabilities.OpenGL33) {
                GL33.glQueryCounter(queryId, GL_TIMESTAMP);
            } else {
                ARBTimerQuery.glQueryCounter(queryId, GL_TIMESTAMP);
            }
        }
        @Override public boolean available(int queryId) {
            return GL15.glGetQueryObjecti(queryId,
                GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
        }
        @Override public long result(int queryId) {
            if (knownCapabilities.OpenGL33) {
                return GL33.glGetQueryObjectui64(queryId,
                    GL15.GL_QUERY_RESULT);
            }
            return ARBTimerQuery.glGetQueryObjectui64(queryId,
                GL15.GL_QUERY_RESULT);
        }
    };

    static final class QueryPair {
        private final int startId;
        private final int endId;
        private final CacheBudget.Reservation reservation;
        private final QueryDriver driver;
        private boolean inFlight;
        private boolean usable = true;
        private int type = -1;
        private long cpuStartedNanos;
        private long cpuNanos;
        private boolean cleanupAttempted;

        private QueryPair(int startId, int endId,
                          CacheBudget.Reservation reservation,
                          QueryDriver driver) {
            this.startId = startId;
            this.endId = endId;
            this.reservation = reservation;
            this.driver = driver;
        }

        private static QueryPair create() {
            CacheBudget.Reservation reservation =
                ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU,
                    QUERY_PAIR_GPU_BYTES);
            return create(reservation, LWJGL_QUERY_DRIVER);
        }

        static QueryPair create(CacheBudget budget, QueryDriver driver) {
            if (budget == null || driver == null) {
                throw new IllegalArgumentException("Xaero QueryPair dependencies");
            }
            CacheBudget.Reservation reservation = budget.tryReserve(
                BudgetKind.GPU, QUERY_PAIR_GPU_BYTES);
            return create(reservation, driver);
        }

        private static QueryPair create(CacheBudget.Reservation reservation,
                                        QueryDriver driver) {
            if (reservation == null) return null;
            int start = 0;
            int end = 0;
            boolean startAttempted = false;
            boolean startReturned = false;
            boolean endAttempted = false;
            boolean endReturned = false;
            try {
                startAttempted = true;
                start = driver.create();
                startReturned = true;
                if (start <= 0) throw new IllegalStateException(
                    "Xaero start timestamp query creation failed");
                endAttempted = true;
                end = driver.create();
                endReturned = true;
                if (end <= 0) throw new IllegalStateException(
                    "Xaero end timestamp query creation failed");
                QueryPair result = new QueryPair(start, end, reservation,
                    driver);
                start = 0;
                end = 0;
                return result;
            } catch (Throwable error) {
                boolean endSafe = !endAttempted || endReturned && end <= 0;
                if (end > 0) {
                    try {
                        driver.delete(end);
                        endSafe = true;
                    }
                    catch (Throwable cleanupFailure) {
                        error = appendFailure(error, cleanupFailure);
                    }
                }
                boolean startSafe = !startAttempted
                    || startReturned && start <= 0;
                if (start > 0) {
                    try {
                        driver.delete(start);
                        startSafe = true;
                    }
                    catch (Throwable cleanupFailure) {
                        error = appendFailure(error, cleanupFailure);
                    }
                }
                if (startSafe && endSafe) {
                    try { reservation.close(); }
                    catch (Throwable cleanupFailure) {
                        error = appendFailure(error, cleanupFailure);
                    }
                }
                rethrow(error);
                return null;
            }
        }

        private void issueStart() { driver.issue(startId); }
        private void issueEnd() { driver.issue(endId); }
        private boolean resultAvailable() { return driver.available(endId); }
        private long startResult() { return driver.result(startId); }
        private long endResult() { return driver.result(endId); }

        private void quarantine() {
            usable = false;
            inFlight = false;
            type = -1;
        }

        void destroy() {
            if (cleanupAttempted) return;
            cleanupAttempted = true;
            Throwable failure = null;
            boolean endSafe = false;
            boolean startSafe = false;
            try { driver.delete(endId); endSafe = true; }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
            try { driver.delete(startId); startSafe = true; }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
            if (startSafe && endSafe) reservation.close();
            if (failure != null) rethrow(failure);
        }

        void abandon() {
            cleanupAttempted = true;
            reservation.close();
        }

    }
}

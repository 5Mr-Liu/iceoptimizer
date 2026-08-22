package dev.rlcraft.ice.optimizer.compat.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-isolated observer for OptiFine's authoritative VboRegion multi-draw.
 * It never changes Render Regions configuration and never replaces the draw.
 */
public final class OptifineRegionBridge {
    private static final String MODULE = "optifine-region-backend";
    private static final int MAX_DEPTH = 8;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private static volatile boolean coreBridgeInstalled;

    private OptifineRegionBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = OptifineRegionBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.OptifineRegionBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, OptifineRegionBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core OptiFine Region bridge mismatch"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            FatalErrors.rethrowIfFatal(cause);
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    public static long begin(Object region) {
        long token = nextToken();
        if (token == 0L) return 0L;
        State state = STATE.get();
        if (state.depth >= MAX_DEPTH) {
            state.overflow++;
            return -token;
        }
        Scope scope = state.scopes[state.depth++];
        scope.reset(token);
        try {
            if (!(region instanceof OptifineVboRegionAccess)) {
                throw new IllegalStateException("VboRegion access ABI unavailable");
            }
            OptifineVboRegionAccess access = (OptifineVboRegionAccess) region;
            scope.runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            scope.sample = scope.runtime == null ? null
                : scope.runtime.beginOptifineRegionDraw(access.ice$layer(),
                    access.ice$indexPosition(), access.ice$countPosition(),
                    access.ice$commandCapacity(), access.ice$drawMode(),
                    access.ice$bufferId(), access.ice$positionTop(),
                    access.ice$sizeUsed());
        } catch (Throwable bridgeError) {
            rethrowBeginFatal(state, scope, bridgeError);
            fail(scope, bridgeError);
        }
        return token;
    }

    public static void end(long token, Object region) {
        finish(token, region, null);
    }

    public static void abort(long token, Object region, Throwable originalError) {
        finish(token, region, originalError == null
            ? new IllegalStateException("OptiFine VboRegion draw aborted")
            : originalError);
    }

    private static void finish(long token, Object region, Throwable originalError) {
        if (token == 0L) return;
        State state = STATE.get();
        if (token < 0L) {
            if (state.overflow > 0) state.overflow--;
            if (state.depth == 0 && state.overflow == 0) STATE.remove();
            return;
        }
        Scope scope = state.current();
        if (scope == null || scope.token != token) {
            drain(state, new IllegalStateException(
                "OptiFine VboRegion scope token mismatch"));
            STATE.remove();
            return;
        }
        try {
            int index = -1;
            int count = -1;
            int capacity = -1;
            if (region instanceof OptifineVboRegionAccess) {
                OptifineVboRegionAccess access = (OptifineVboRegionAccess) region;
                index = access.ice$indexPosition();
                count = access.ice$countPosition();
                capacity = access.ice$commandCapacity();
            }
            if (scope.runtime != null) {
                ModernRendererRuntime.OptifineRegionDrawSample sample = scope.sample;
                scope.sample = null;
                scope.runtime.endOptifineRegionDraw(sample, index, count,
                    capacity, originalError);
            }
        } catch (Throwable bridgeError) {
            fail(scope, bridgeError);
        } finally {
            scope.clear();
            state.depth--;
            if (state.depth == 0 && state.overflow == 0) STATE.remove();
        }
    }

    private static void drain(State state, Throwable mismatch) {
        Throwable fatal = null;
        while (state.depth > 0) {
            Scope scope = state.current();
            try {
                fail(scope, mismatch);
            } catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            } finally {
                scope.clear();
                state.depth--;
            }
        }
        state.overflow = 0;
        STATE.remove();
        FatalErrors.rethrowIfFatal(fatal);
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

    private static void fail(Scope scope, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (scope == null) return;
        try {
            if (scope.runtime != null && scope.sample != null) {
                scope.runtime.endOptifineRegionDraw(scope.sample, -1, -1, -1,
                    error);
                scope.sample = null;
            } else {
                OptimizerBridge.failure(MODULE, error);
            }
        } catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
            try { OptimizerBridge.failure(MODULE, error); }
            catch (Throwable fallbackFailure) {
                FatalErrors.rethrowIfFatal(fallbackFailure);
            }
        }
    }

    private static long nextToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "OptiFine region bridge token");
    }

    private static void rethrowBeginFatal(State state, Scope scope,
                                          Throwable failure) {
        if (FatalErrors.findFatal(failure) == null) return;
        scope.clear();
        state.depth--;
        if (state.depth == 0 && state.overflow == 0) STATE.remove();
        FatalErrors.rethrowIfFatal(failure);
    }

    static int depthForTest() { return STATE.get().depth; }
    static void unwindBeginFailureForTest(Throwable failure) {
        State state = STATE.get();
        int previousDepth = state.depth;
        if (previousDepth >= MAX_DEPTH) throw new IllegalStateException(
            "test region scope capacity exhausted");
        Scope scope = state.scopes[state.depth++];
        scope.reset(1L);
        try {
            rethrowBeginFatal(state, scope, failure);
        } finally {
            if (state.depth > previousDepth && state.current() == scope) {
                scope.clear();
                state.depth--;
                if (state.depth == 0 && state.overflow == 0) STATE.remove();
            }
        }
    }
    static void resetForTest() {
        STATE.remove();
        coreBridgeInstalled = false;
    }

    private static final class State {
        private final Scope[] scopes = new Scope[MAX_DEPTH];
        private int depth;
        private int overflow;

        private State() {
            for (int i = 0; i < scopes.length; i++) scopes[i] = new Scope();
        }

        private Scope current() { return depth == 0 ? null : scopes[depth - 1]; }
    }

    private static final class Scope {
        private long token;
        private ModernRendererRuntime runtime;
        private ModernRendererRuntime.OptifineRegionDrawSample sample;

        private void reset(long value) {
            token = value;
            runtime = null;
            sample = null;
        }

        private void clear() {
            runtime = null;
            sample = null;
        }
    }
}

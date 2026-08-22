package dev.rlcraft.ice.optimizer.compat.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramIntrospector;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;

/** Main-JAR observer for the exact OptiFine Program/FBO/drawbuffer lifecycle. */
public final class OptifineShaderLifecycleBridge {
    private static final String MODULE = "optifine-shader-bridge";
    private static final int MAX_DEPTH = 16;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final OptifineProgramIntrospector INTROSPECTOR =
        new OptifineProgramIntrospector();
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private static volatile boolean coreBridgeInstalled;

    private OptifineShaderLifecycleBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = OptifineShaderLifecycleBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.OptifineShaderBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, OptifineShaderLifecycleBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core OptiFine shader bridge mismatch"));
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

    public static long begin(Object requestedProgram) {
        long token = nextToken();
        if (token == 0L) return 0L;
        State state = STATE.get();
        if (state.depth >= MAX_DEPTH) {
            Scope current = state.current();
            if (current != null && current.runtime != null) {
                IllegalStateException overflow = new IllegalStateException(
                    "OptiFine shader scope depth exceeded " + MAX_DEPTH);
                try {
                    current.runtime.beforeOptifineProgramSwitch(
                        requestedProgram, true);
                } catch (Throwable cleanupFailure) {
                    FatalErrors.rethrowIfFatal(cleanupFailure);
                    overflow.addSuppressed(cleanupFailure);
                }
                safeReport(current.runtime, overflow);
            }
            state.overflow++;
            return -token;
        }
        Scope scope = state.scopes[state.depth++];
        scope.reset(token);
        try {
            scope.runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            scope.changed = INTROSPECTOR.willChange(requestedProgram);
            if (scope.runtime != null) {
                scope.runtime.beforeOptifineProgramSwitch(requestedProgram,
                    scope.changed);
            }
        } catch (Throwable error) {
            rethrowBeginFatal(state, scope, error);
            if (scope.runtime != null) {
                try {
                    scope.runtime.beforeOptifineProgramSwitch(requestedProgram,
                        true);
                } catch (Throwable cleanupFailure) {
                    addSuppressed(error, cleanupFailure);
                    Throwable fatal = FatalErrors.findFatal(cleanupFailure);
                    if (fatal != null) {
                        addSuppressed(fatal, error);
                        rethrowBeginFatal(state, scope, cleanupFailure);
                    }
                }
                try {
                    safeReport(scope.runtime, error);
                } catch (Throwable reportingFailure) {
                    addSuppressed(reportingFailure, error);
                    rethrowBeginFatal(state, scope, reportingFailure);
                }
            }
            scope.changed = true;
        }
        return token;
    }

    public static void end(long token, Object requestedProgram) {
        finish(token, requestedProgram, null);
    }

    public static void abort(long token, Object requestedProgram,
                             Throwable originalError) {
        finish(token, requestedProgram, originalError == null
            ? new IllegalStateException("OptiFine useProgram aborted")
            : originalError);
    }

    private static void finish(long token, Object requestedProgram,
                               Throwable originalError) {
        if (token == 0L) return;
        State state = STATE.get();
        if (token < 0L) {
            if (state.overflow > 0) state.overflow--;
            if (state.depth == 0 && state.overflow == 0) STATE.remove();
            return;
        }
        Scope scope = state.current();
        if (scope == null || scope.token != token) {
            drain(state);
            return;
        }
        try {
            if (scope.runtime != null) {
                if (originalError == null) {
                    OptifineProgramState captured = INTROSPECTOR.capture(requestedProgram);
                    scope.runtime.observeOptifineProgram(requestedProgram,
                        captured);
                } else if (originalError != null) {
                    scope.runtime.abortOptifineProgramSwitch();
                }
            }
        } catch (Throwable bridgeError) {
            FatalErrors.rethrowIfFatal(bridgeError);
            if (scope.runtime != null) {
                try { scope.runtime.abortOptifineProgramSwitch(); }
                catch (Throwable abortFailure) {
                    addSuppressed(bridgeError, abortFailure);
                    FatalErrors.rethrowIfFatal(abortFailure);
                }
                safeReport(scope.runtime, bridgeError);
            }
            if (originalError != null) addSuppressed(originalError, bridgeError);
        } finally {
            scope.clear();
            state.depth--;
            if (state.depth == 0 && state.overflow == 0) STATE.remove();
        }
    }

    private static void drain(State state) {
        IllegalStateException mismatch = new IllegalStateException(
            "OptiFine shader scope token mismatch");
        Throwable fatal = null;
        while (state.depth > 0) {
            Scope scope = state.current();
            if (scope.runtime != null) {
                try { scope.runtime.abortOptifineProgramSwitch(); }
                catch (Throwable abortFailure) {
                    if (FatalErrors.findFatal(abortFailure) != null) {
                        fatal = appendFailure(fatal, abortFailure);
                    } else {
                        addSuppressed(mismatch, abortFailure);
                    }
                }
                try {
                    safeReport(scope.runtime, mismatch);
                } catch (Throwable reportingFailure) {
                    fatal = appendFailure(fatal, reportingFailure);
                }
            }
            scope.clear();
            state.depth--;
        }
        state.overflow = 0;
        STATE.remove();
        FatalErrors.rethrowIfFatal(fatal);
    }

    private static void rethrowBeginFatal(State state, Scope scope,
                                          Throwable failure) {
        if (FatalErrors.findFatal(failure) == null) return;
        scope.clear();
        state.depth--;
        if (state.depth == 0 && state.overflow == 0) STATE.remove();
        FatalErrors.rethrowIfFatal(failure);
    }

    private static void safeReport(ModernRendererRuntime runtime,
                                   Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (runtime == null) return;
        try { runtime.shaderBridgeFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            addSuppressed(nextFatal, first);
            return nextFatal;
        }
        if (next != null && first != next) first.addSuppressed(next);
        return first;
    }

    private static void addSuppressed(Throwable first, Throwable next) {
        if (first == null || next == null || first == next) return;
        first.addSuppressed(next);
    }

    static int depthForTest() { return STATE.get().depth; }
    static int overflowForTest() { return STATE.get().overflow; }
    static void unwindBeginFailureForTest(Throwable failure) {
        State state = STATE.get();
        int previousDepth = state.depth;
        if (previousDepth >= MAX_DEPTH) throw new IllegalStateException(
            "test shader scope capacity exhausted");
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
    static void resetForTest() { STATE.remove(); }

    private static long nextToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "OptiFine shader bridge token");
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
        private boolean changed;
        private void reset(long value) {
            token = value;
            runtime = null;
            changed = false;
        }
        private void clear() { runtime = null; }
    }
}

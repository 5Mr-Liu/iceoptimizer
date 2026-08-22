package dev.rlcraft.ice.optimizer.compat.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact, bounded observer for OptiFine's shadow and post-processing stages.
 * It never substitutes an OptiFine draw or program.  The shadow flag only
 * lets the existing terrain/entity emitters assign their work to the correct
 * semantic pass; deferred/composite/final are hard Legacy boundaries.
 */
public final class OptifinePassLifecycleBridge {
    private static final String MODULE = "optifine-shader-bridge";
    private static final int MAX_DEPTH = 8;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };
    private static volatile boolean coreBridgeInstalled;

    private OptifinePassLifecycleBridge() {
    }

    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = OptifinePassLifecycleBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.OptifinePassBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, OptifinePassLifecycleBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core OptiFine pass bridge mismatch"));
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

    public static long beginShadow() {
        State state = STATE.get();
        long token = nextToken();
        if (token == 0L) return 0L;
        if (state.shadowDepth >= MAX_DEPTH) {
            state.shadowOverflow++;
            safeBarrier("OptiFine shadow overflow");
            return -token;
        }
        state.shadowTokens[state.shadowDepth++] = token;
        safeBarrier("OptiFine shadow enter");
        return token;
    }

    public static void endShadow(long token) { finishShadow(token); }

    public static void abortShadow(long token, Throwable originalError) {
        finishShadow(token);
    }

    /** True only while the reviewed ShadersRender shadow entry is on-stack. */
    public static boolean isShadowPass() {
        State state = STATE.get();
        return state.shadowDepth > 0 || state.shadowOverflow > 0;
    }

    public static long beginDeferred() {
        return beginObserved(RenderPass.DEFERRED);
    }

    public static void endDeferred(long token) { endObserved(token); }

    public static void abortDeferred(long token, Throwable originalError) {
        endObserved(token);
    }

    public static long beginComposite() {
        State state = STATE.get();
        long token = nextToken();
        if (token == 0L) return 0L;
        if (state.compositeDepth >= MAX_DEPTH) {
            state.compositeOverflow++;
            safeBarrier("OptiFine composite overflow");
            return -token;
        }
        CompositeScope scope = state.composites[state.compositeDepth++];
        scope.token = token;
        scope.passToken = beginObserved(RenderPass.COMPOSITE);
        scope.finalStage = false;
        return token;
    }

    /** Called at the exact private renderFinal invocation inside composites. */
    public static void transitionFinal() {
        State state = STATE.get();
        if (state.compositeOverflow != 0 || state.compositeDepth == 0) {
            safeBarrier("OptiFine final without composite scope");
            return;
        }
        CompositeScope scope = state.composites[state.compositeDepth - 1];
        if (scope.finalStage) {
            safeBarrier("OptiFine duplicate final transition");
            return;
        }
        endObserved(scope.passToken);
        scope.passToken = beginObserved(RenderPass.FINAL);
        scope.finalStage = true;
    }

    public static void endComposite(long token) { finishComposite(token); }

    public static void abortComposite(long token, Throwable originalError) {
        finishComposite(token);
    }

    private static void finishShadow(long token) {
        if (token == 0L) return;
        State state = STATE.get();
        try {
            if (token < 0L) {
                if (state.shadowOverflow > 0) state.shadowOverflow--;
                else drainShadow(state);
                return;
            }
            if (state.shadowDepth == 0
                || state.shadowTokens[state.shadowDepth - 1] != token) {
                drainShadow(state);
            } else {
                state.shadowTokens[--state.shadowDepth] = 0L;
                safeBarrier("OptiFine shadow exit");
            }
        } finally {
            cleanupThreadLocal(state);
        }
    }

    private static void finishComposite(long token) {
        if (token == 0L) return;
        State state = STATE.get();
        try {
            if (token < 0L) {
                if (state.compositeOverflow > 0) state.compositeOverflow--;
                else drainComposite(state);
                return;
            }
            if (state.compositeDepth == 0
                || state.composites[state.compositeDepth - 1].token != token) {
                drainComposite(state);
            } else {
                CompositeScope scope = state.composites[--state.compositeDepth];
                try { endObserved(scope.passToken); }
                finally { scope.clear(); }
            }
        } finally {
            cleanupThreadLocal(state);
        }
    }

    private static void drainShadow(State state) {
        while (state.shadowDepth > 0) {
            state.shadowTokens[--state.shadowDepth] = 0L;
        }
        state.shadowOverflow = 0;
        safeBarrier("OptiFine shadow token mismatch");
    }

    private static void drainComposite(State state) {
        Throwable fatal = null;
        while (state.compositeDepth > 0) {
            CompositeScope scope = state.composites[--state.compositeDepth];
            try { endObserved(scope.passToken); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            } finally { scope.clear(); }
        }
        state.compositeOverflow = 0;
        try { safeBarrier("OptiFine composite token mismatch"); }
        catch (Throwable failure) {
            fatal = appendFailure(fatal, failure);
        }
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

    private static long beginObserved(RenderPass pass) {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            return runtime == null ? 0L : runtime.beginObservedPass(pass,
                RenderBackendId.LEGACY);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return 0L;
        }
    }

    private static void endObserved(long token) {
        if (token == 0L) return;
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.endObservedPass(token);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void safeBarrier(String reason) {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.observableBarrier(reason);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void cleanupThreadLocal(State state) {
        if (state.shadowDepth == 0 && state.shadowOverflow == 0
            && state.compositeDepth == 0 && state.compositeOverflow == 0) {
            STATE.remove();
        }
    }

    private static long nextToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "OptiFine pass bridge token");
    }

    static int shadowDepthForTest() { return STATE.get().shadowDepth; }
    static int compositeDepthForTest() { return STATE.get().compositeDepth; }
    static void resetForTest() { STATE.remove(); }

    private static final class State {
        private final long[] shadowTokens = new long[MAX_DEPTH];
        private final CompositeScope[] composites = new CompositeScope[MAX_DEPTH];
        private int shadowDepth;
        private int shadowOverflow;
        private int compositeDepth;
        private int compositeOverflow;

        private State() {
            for (int i = 0; i < composites.length; i++) {
                composites[i] = new CompositeScope();
            }
        }
    }

    private static final class CompositeScope {
        private long token;
        private long passToken;
        private boolean finalStage;
        private void clear() {
            token = 0L;
            passToken = 0L;
            finalStage = false;
        }
    }
}

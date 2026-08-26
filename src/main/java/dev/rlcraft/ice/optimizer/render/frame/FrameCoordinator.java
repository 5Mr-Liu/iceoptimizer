package dev.rlcraft.ice.optimizer.render.frame;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.runtime.EpochToken;
import java.util.EnumSet;

/**
 * Sole owner of modern frame/view/pass transitions. It validates ordering but
 * never reorders game calls; an invalid transition fails open through reset.
 */
public final class FrameCoordinator {
    public interface BatchBarrier {
        void flush(String reason);
    }

    private static final int MAX_VIEW_DEPTH = 8;
    private static final int MAX_OBSERVED_PASS_DEPTH = 16;

    private final RenderThreadGuard threadGuard;
    private final PassGraph graph;
    private final GlStateMirror stateMirror;
    private final BatchBarrier batchBarrier;
    private final ViewState[] views = new ViewState[MAX_VIEW_DEPTH];
    private int viewDepth;
    private boolean frameActive;
    private long frameId;
    private long nextViewId;
    private EpochToken frameEpochs;
    private long barriers;
    private long failures;
    private long observedDeviations;
    private long nextObservedPassToken;

    public FrameCoordinator(RenderThreadGuard threadGuard, PassGraph graph,
                            GlStateMirror stateMirror, BatchBarrier batchBarrier) {
        if (threadGuard == null || graph == null || stateMirror == null
            || batchBarrier == null) throw new IllegalArgumentException("coordinator dependencies");
        this.threadGuard = threadGuard;
        this.graph = graph;
        this.stateMirror = stateMirror;
        this.batchBarrier = batchBarrier;
        for (int index = 0; index < views.length; index++) {
            views[index] = new ViewState();
        }
    }

    public void beginFrame(long value, EpochToken epochs) {
        threadGuard.check();
        if (frameActive || value <= 0L || epochs == null) {
            fail("invalid beginFrame");
        }
        frameActive = true;
        frameId = value;
        frameEpochs = epochs;
        clearViews();
    }

    public FrameStamp beginPrimaryView() {
        threadGuard.check();
        require(frameActive && viewDepth == 0, "primary view lifecycle");
        ViewState view = views[viewDepth++];
        view.reset(newStamp());
        return view.stamp;
    }

    /** Starts an independently ordered recursive portal view at a hard barrier. */
    public FrameStamp beginPortalView() {
        threadGuard.check();
        require(frameActive && viewDepth > 0 && viewDepth < MAX_VIEW_DEPTH,
            "portal view lifecycle");
        hardBarrier("portal-enter", true);
        ViewState nested = views[viewDepth++];
        nested.reset(newStamp());
        return nested.stamp;
    }

    public void endPortalView() {
        threadGuard.check();
        require(viewDepth > 1, "portal view underflow");
        ViewState current = views[viewDepth - 1];
        require(current.activePass == null, "portal pass still active");
        hardBarrier("portal-exit", true);
        current.clear();
        viewDepth--;
    }

    public void beginPass(RenderPass pass, RenderBackendId backend) {
        threadGuard.check();
        ViewState view = currentView();
        require(pass != null && pass != RenderPass.PORTAL_RECURSIVE && backend != null,
            "invalid pass");
        require(view.activePass == null, "nested pass");
        require(graph.canBegin(pass, view.completed, view.lastCompletedIndex),
            "pass order " + pass);
        if (pass.isHardBoundary()) hardBarrier("pass-enter:" + pass.name(), false);
        view.activePass = pass;
        view.backend = backend;
    }

    public void endPass(RenderPass pass) {
        threadGuard.check();
        ViewState view = currentView();
        require(pass != null && view.activePass == pass, "pass end mismatch");
        if (pass.isHardBoundary()) hardBarrier("pass-exit:" + pass.name(), false);
        view.completed.add(pass);
        view.lastCompletedIndex = graph.indexOf(pass);
        view.activePass = null;
        view.backend = RenderBackendId.LEGACY;
    }

    /**
     * Records a pass discovered at an exact production emitter.  Unlike the
     * strict begin/end API used by owned render graphs, this method must not
     * reject a legal mod recursion or an optional/repeated Forge pass.  Such a
     * deviation becomes an invalidating hard barrier and the observed scope
     * remains Legacy-compatible; game calls are never reordered.
     */
    public long beginObservedPass(RenderPass pass, RenderBackendId backend) {
        threadGuard.check();
        if (!frameActive || viewDepth == 0 || pass == null
            || pass == RenderPass.PORTAL_RECURSIVE || backend == null) return 0L;
        ViewState view = views[viewDepth - 1];
        long token = nextObservedToken();
        if (view.observedDepth >= MAX_OBSERVED_PASS_DEPTH) {
            view.observedOverflow++;
            observedDeviations++;
            hardBarrier("observed-pass-overflow", true);
            return -token;
        }
        boolean nested = view.activePass != null;
        boolean ordered = !nested && graph.canObserve(pass, view.completed,
            view.lastCompletedIndex);
        if (nested || !ordered) {
            observedDeviations++;
            hardBarrier("observed-pass-deviation:" + pass.name(), true);
            // A deviating scope may still use a modern emitter that has its
            // own output gate, but it cannot claim coordinator ownership.
            backend = RenderBackendId.LEGACY;
        } else if (pass.isHardBoundary()) {
            hardBarrier("pass-enter:" + pass.name(), false);
        }
        ObservedPass scope = view.observed[view.observedDepth++];
        scope.capture(token, pass, backend, ordered, view.activePass,
            view.backend);
        view.activePass = pass;
        view.backend = backend;
        return token;
    }

    /** Ends a production observation without replacing an original exception. */
    public boolean endObservedPass(long token) {
        threadGuard.check();
        if (token == 0L) return true;
        if (!frameActive || viewDepth == 0) return false;
        ViewState view = views[viewDepth - 1];
        if (token < 0L) {
            if (view.observedOverflow > 0) {
                view.observedOverflow--;
                return true;
            }
            recoverObservedMismatch(view, "observed overflow token mismatch");
            return false;
        }
        if (view.observedDepth == 0
            || view.observed[view.observedDepth - 1].token != token) {
            recoverObservedMismatch(view, "observed pass token mismatch");
            return false;
        }
        ObservedPass scope = view.observed[--view.observedDepth];
        if (scope.pass.isHardBoundary()) {
            hardBarrier("pass-exit:" + scope.pass.name(), !scope.ordered);
        }
        if (scope.ordered) {
            view.completed.add(scope.pass);
            view.lastCompletedIndex = graph.indexOf(scope.pass);
        }
        view.activePass = scope.previousPass;
        view.backend = scope.previousBackend;
        scope.clear();
        return true;
    }

    /** Explicitly records an absent optional pass without inventing callbacks. */
    public void skipPass(RenderPass pass) {
        threadGuard.check();
        ViewState view = currentView();
        require(view.activePass == null && pass != null
            && pass != RenderPass.PORTAL_RECURSIVE, "invalid skipped pass");
        int index = graph.indexOf(pass);
        require(index >= view.lastCompletedIndex && !view.completed.contains(pass),
            "skip order " + pass);
        view.completed.add(pass);
        view.lastCompletedIndex = index;
    }

    public void observableBarrier(String reason) {
        threadGuard.check();
        require(frameActive, "barrier outside frame");
        hardBarrier(reason == null ? "observable" : reason, true);
    }

    public void endPrimaryView() {
        threadGuard.check();
        require(viewDepth == 1 && views[0].activePass == null,
            "primary view lifecycle");
        hardBarrier("view-end", false);
        views[0].clear();
        viewDepth = 0;
    }

    public void endFrame() {
        threadGuard.check();
        require(frameActive && viewDepth == 0, "frame lifecycle");
        batchBarrier.flush("frame-end");
        frameActive = false;
        frameId = 0L;
        frameEpochs = null;
    }

    /** Fail-open recovery used by bridges after an unexpected mod transition. */
    public void resetAfterFailure(Throwable error) {
        threadGuard.check();
        failures++;
        Throwable cleanupFailure = null;
        try { batchBarrier.flush("coordinator-failure"); }
        catch (Throwable flushFailure) {
            cleanupFailure = appendFailure(cleanupFailure, flushFailure);
        }
        try { stateMirror.invalidateAll(); }
        catch (Throwable failure) {
            cleanupFailure = appendFailure(cleanupFailure, failure);
        }
        try { clearViews(); }
        catch (Throwable failure) {
            cleanupFailure = appendFailure(cleanupFailure, failure);
        }
        frameActive = false;
        frameId = 0L;
        frameEpochs = null;
        if (cleanupFailure != null) {
            if (error == null) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                rethrow(cleanupFailure);
            }
            Throwable combined = appendFailure(error, cleanupFailure);
            FatalErrors.rethrowIfFatal(combined);
        }
    }

    public FrameStamp currentStamp() {
        ViewState view = viewDepth == 0 ? null : views[viewDepth - 1];
        return view == null ? null : view.stamp;
    }

    /** Allocation-free lifecycle probes used by the per-frame fast path. */
    public boolean isFrameActive() { return frameActive; }
    public int viewDepth() { return viewDepth; }

    public FrameCoordinatorStatus snapshot() {
        ViewState view = viewDepth == 0 ? null : views[viewDepth - 1];
        return new FrameCoordinatorStatus(frameActive, frameId,
            view == null ? 0L : view.stamp.getViewId(),
            view == null ? null : view.activePass,
            view == null ? RenderBackendId.LEGACY : view.backend,
            viewDepth, barriers, failures, observedDeviations);
    }

    private long nextObservedToken() {
        if (nextObservedPassToken == Long.MAX_VALUE) {
            throw new IllegalStateException("observed pass token exhausted");
        }
        return ++nextObservedPassToken;
    }

    private void recoverObservedMismatch(ViewState view, String reason) {
        failures++;
        observedDeviations++;
        Throwable failure = null;
        try { batchBarrier.flush(reason); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { stateMirror.invalidateAll(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try {
            while (view.observedDepth > 0) {
                view.observed[--view.observedDepth].clear();
            }
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            view.observedOverflow = 0;
            view.activePass = null;
            view.backend = RenderBackendId.LEGACY;
        }
        if (failure != null) rethrow(failure);
    }

    private FrameStamp newStamp() {
        if (nextViewId == Long.MAX_VALUE) throw new IllegalStateException("view id exhausted");
        return new FrameStamp(frameId, ++nextViewId, frameEpochs);
    }

    private ViewState currentView() {
        require(frameActive && viewDepth > 0, "view required");
        return views[viewDepth - 1];
    }

    private void clearViews() {
        while (viewDepth > 0) views[--viewDepth].clear();
    }

    private void hardBarrier(String reason, boolean invalidate) {
        batchBarrier.flush(reason == null ? "barrier" : reason);
        barriers++;
        if (invalidate) stateMirror.invalidateAll();
    }

    private void require(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private void fail(String message) {
        throw new IllegalStateException("FrameCoordinator: " + message);
    }

    private static void rethrow(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException("FrameCoordinator barrier failed", error);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable nextFatal = FatalErrors.findFatal(next);
        if (nextFatal != null && FatalErrors.findFatal(first) == null) {
            if (nextFatal != first) nextFatal.addSuppressed(first);
            return nextFatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static final class ViewState {
        private FrameStamp stamp;
        private final EnumSet<RenderPass> completed = EnumSet.noneOf(RenderPass.class);
        private int lastCompletedIndex = -1;
        private RenderPass activePass;
        private RenderBackendId backend = RenderBackendId.LEGACY;
        private final ObservedPass[] observed =
            new ObservedPass[MAX_OBSERVED_PASS_DEPTH];
        private int observedDepth;
        private int observedOverflow;

        private ViewState() {
            for (int index = 0; index < observed.length; index++) {
                observed[index] = new ObservedPass();
            }
        }

        private void reset(FrameStamp value) {
            stamp = value;
            completed.clear();
            lastCompletedIndex = -1;
            activePass = null;
            backend = RenderBackendId.LEGACY;
            observedDepth = 0;
            observedOverflow = 0;
        }

        private void clear() {
            while (observedDepth > 0) observed[--observedDepth].clear();
            stamp = null;
            completed.clear();
            lastCompletedIndex = -1;
            activePass = null;
            backend = RenderBackendId.LEGACY;
            observedOverflow = 0;
        }
    }

    private static final class ObservedPass {
        private long token;
        private RenderPass pass;
        private RenderBackendId backend;
        private boolean ordered;
        private RenderPass previousPass;
        private RenderBackendId previousBackend;

        private void capture(long token, RenderPass pass,
                             RenderBackendId backend, boolean ordered,
                             RenderPass previousPass,
                             RenderBackendId previousBackend) {
            this.token = token;
            this.pass = pass;
            this.backend = backend;
            this.ordered = ordered;
            this.previousPass = previousPass;
            this.previousBackend = previousBackend;
        }

        private void clear() {
            token = 0L;
            pass = null;
            backend = null;
            ordered = false;
            previousPass = null;
            previousBackend = null;
        }
    }
}

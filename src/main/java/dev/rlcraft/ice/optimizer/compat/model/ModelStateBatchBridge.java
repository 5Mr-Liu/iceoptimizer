package dev.rlcraft.ice.optimizer.compat.model;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelQuadruped;

/** Holds client arrays through one preflighted tree of reviewed model renderers. */
public final class ModelStateBatchBridge {
    private static final int MAX_PARTS = 128;
    private static final int MAX_DEPTH = 16;
    private static volatile boolean hasActiveBatch;
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private ModelStateBatchBridge() { }

    /** Returns true for the root responsible for closing an active or declined tree scope. */
    public static boolean begin(ModelRenderer root) {
        if (root == null || !(root instanceof ModelStateBatchAccess)
            || (root.getClass() != ModelRenderer.class
                && !(root instanceof ModelStateBatchSubclassAccess)) || root.isHidden
            || !root.showModel) return false;
        List<ModelRenderer> children = root.childModels;
        // A leaf cannot save any state calls; avoid runtime gates and tree scans.
        if (children == null || children.getClass() != ArrayList.class
            || children.isEmpty()) return false;
        State state = STATES.get();
        if (state.scopeOpened) return false;
        ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (runtime == null) return false;
        LwjglModelMeshCache cache = runtime.modelStateBatchCandidate();
        if (cache == null) return false;
        state.cache = cache;
        state.stamp = runtime.modelStateBatchStamp();
        state.runtime = runtime;
        state.scopeOpened = true;
        try {
            if (!collect(root, state, 0) || state.count < 2) {
                cache.rejectStateBatch();
                // Keep this root scope until finally so descendants do not
                // rescan the same rejected graph, producing quadratic work.
                return true;
            }
            if (!cache.beginStateBatch(state.entries, state.count)) {
                cache.rejectStateBatch();
                return true;
            }
            state.active = true;
            hasActiveBatch = true;
            return true;
        } catch (Throwable failure) {
            reportFailure(runtime, failure);
            state.clear();
            rethrow(failure);
            return false;
        }
    }

    /**
     * Entered by the reviewed common-model render after animation/entity callbacks.
     * The caller supplies its constant kind; subclasses retain their original
     * animation and virtual render behavior before entering this base-method tail.
     */
    public static int beginRoots(Object owner, int kind) {
        if (!(kind == 1 && owner instanceof ModelBiped)
            && !(kind == 2 && owner instanceof ModelQuadruped)) return 0;
        State state = STATES.get();
        if (state.scopeOpened) return 0;
        ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (runtime == null) return 0;
        LwjglModelMeshCache cache = runtime.modelStateBatchCandidate();
        if (cache == null) return 0;
        state.cache = cache;
        state.stamp = runtime.modelStateBatchStamp();
        state.runtime = runtime;
        state.scopeOpened = true;
        try {
            beginRootSegment(owner, kind, 0, state);
            // The complete sequence keeps the old single begin/end hot path.
            // Only a split sequence needs the per-root boundary hook.
            return state.segmentEnd == (kind == 1 ? 7 : 6) ? 1 : 2;
        } catch (Throwable failure) {
            reportFailure(runtime, failure);
            state.clear();
            rethrow(failure);
            return 0;
        }
    }

    /** Close before an unreviewed root; plan the following segment only after it runs. */
    public static void beforeRoot(Object owner, int kind, int index) {
        State state = STATES.get();
        if (!state.scopeOpened || index < state.segmentEnd) return;
        finishBatch(state);
        if (index == state.blockedRoot) return;
        // The intervening root may change GL state, visibility or mesh lifetime.
        // Reuse the ordinary scope-entry synchronization at that real boundary.
        LwjglModelMeshCache cache = state.runtime.modelStateBatchCandidate();
        if (cache == null) return;
        state.cache = cache;
        state.stamp = state.runtime.modelStateBatchStamp();
        try { beginRootSegment(owner, kind, index, state); }
        catch (Throwable failure) {
            reportFailure(state.runtime, failure);
            rethrow(failure);
        }
    }

    private static void beginRootSegment(Object owner, int kind, int first, State state) {
        int roots = kind == 1 ? 7 : 6;
        state.segmentEnd = roots;
        state.blockedRoot = -1;
        for (int index = first; index < roots; index++) {
            int before = state.count;
            if (!collect(rootAt(owner, kind, index), state, 0)) {
                Arrays.fill(state.entries, before, state.count, null);
                state.count = before;
                state.segmentEnd = state.blockedRoot = index;
                state.cache.rejectStateBatch();
                break;
            }
        }
        if (state.count < 2) return;
        if (!state.cache.beginStateBatch(state.entries, state.count)) {
            state.cache.rejectStateBatch();
            return;
        }
        state.active = true;
        hasActiveBatch = true;
    }

    /** The two reviewed branches render these roots in the same order. */
    static ModelRenderer rootAt(Object owner, int kind, int index) {
        if (kind == 1) {
            ModelBiped model = (ModelBiped) owner;
            switch (index) {
                case 0: return model.bipedHead;
                case 1: return model.bipedBody;
                case 2: return model.bipedRightArm;
                case 3: return model.bipedLeftArm;
                case 4: return model.bipedRightLeg;
                case 5: return model.bipedLeftLeg;
                case 6: return model.bipedHeadwear;
                default: return null;
            }
        }
        ModelQuadruped model = (ModelQuadruped) owner;
        switch (index) {
            case 0: return model.head;
            case 1: return model.body;
            case 2: return model.leg1;
            case 3: return model.leg2;
            case 4: return model.leg3;
            case 5: return model.leg4;
            default: return null;
        }
    }

    private static boolean collect(ModelRenderer model, State state, int depth) {
        // A hidden subclass can override render and ignore vanilla visibility flags.
        if (model == null || !(model instanceof ModelStateBatchAccess)) return false;
        if (model.getClass() != ModelRenderer.class
            && (!(model instanceof ModelStateBatchSubclassAccess)
                || !((ModelStateBatchSubclassAccess) model).ice$stateBatchRenderClassReady())) return false;
        if (model.isHidden || !model.showModel) return true;
        if (depth >= MAX_DEPTH || state.count >= MAX_PARTS) return false;
        ModelStateBatchAccess access = (ModelStateBatchAccess) model;
        if (!access.ice$stateBatchNodeReady()) return false;
        int displayList = access.ice$stateBatchDisplayList();
        if (displayList <= 0 || ModelMeshCaptureBridge.isPending(displayList)) return false;
        LwjglModelMeshCache.Entry entry = state.cache.findStateBatchEntry(displayList, state.stamp);
        if (entry == null) return false;
        state.displayLists[state.count] = displayList;
        state.entries[state.count++] = entry;
        List<ModelRenderer> children = model.childModels;
        if (children == null) return true;
        // List subclasses may run arbitrary callbacks in size/get.
        if (children.getClass() != ArrayList.class) return false;
        for (int index = 0, count = children.size(); index < count; index++) {
            if (!collect(children.get(index), state, depth + 1)) return false;
        }
        return true;
    }

    /** Reviewed transforms may reuse the parent chain prepared by this thread's preflight. */
    public static boolean isActive() {
        return hasActiveBatch && STATES.get().active;
    }

    /** Called before the ordinary call-list decision, at the same draw instruction. */
    public static boolean draw(int displayList) {
        if (!hasActiveBatch) return false;
        State state = STATES.get();
        if (!state.active) return false;
        if (state.next >= state.count || state.displayLists[state.next] != displayList) {
            state.cache.rejectStateBatch();
            end();
            return false;
        }
        try {
            if (!state.cache.drawInStateBatch(state.entries[state.next])) {
                state.cache.rejectStateBatch();
                end();
                return false;
            }
            state.next++;
            return true;
        } catch (Throwable failure) {
            reportFailure(state.runtime, failure);
            // Do not return false after any native submission: replay may draw twice.
            rethrow(failure);
            return false;
        }
    }

    public static void end() {
        State state = STATES.get();
        if (!state.scopeOpened) return;
        try { finishBatch(state); }
        finally { state.clear(); }
    }

    private static void finishBatch(State state) {
        try {
            Throwable failure = null;
            try {
                if (state.active) state.cache.endStateBatch();
            } catch (Throwable cleanupFailure) {
                failure = cleanupFailure;
            }
            // Include successfully submitted parts even when restoration fails.
            // A rejected current part is counted by its ordinary fallback instead.
            try {
                if (state.next > 0) state.runtime.recordModelStateBatchDraws(state.next);
            } catch (Throwable recordingFailure) {
                FatalErrors.rethrowIfFatal(recordingFailure);
                if (failure == null) failure = recordingFailure;
                else if (recordingFailure != failure) failure.addSuppressed(recordingFailure);
            }
            if (failure != null) {
                reportFailure(state.runtime, failure);
                rethrow(failure);
            }
        } finally {
            hasActiveBatch = false;
            state.clearParts();
        }
    }

    public static void endAfterFailure(Throwable failure) {
        try { end(); }
        catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            FatalErrors.rethrowIfFatal(failure);
            FatalErrors.rethrowIfFatal(cleanupFailure);
        }
    }

    /** G5 sprite compilation contains extra commands, so admit only its empty default. */
    public static boolean isEmptyCanonicalList(Object value) {
        return value != null && value.getClass() == ArrayList.class
            && ((ArrayList<?>) value).isEmpty();
    }

    private static void reportFailure(ModernRendererRuntime runtime, Throwable failure) {
        if (runtime == null) return;
        try { runtime.modelTraversalFailure(RenderLibRenderBridge.currentModule(), failure); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
            if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("model state batch failed", failure);
    }

    private static final class State {
        private final int[] displayLists = new int[MAX_PARTS];
        private final LwjglModelMeshCache.Entry[] entries =
            new LwjglModelMeshCache.Entry[MAX_PARTS];
        private int count;
        private int next;
        private boolean active;
        private boolean scopeOpened;
        private int segmentEnd;
        private int blockedRoot;
        private LwjglModelMeshCache cache;
        private FrameStamp stamp;
        private ModernRendererRuntime runtime;

        private void clearParts() {
            Arrays.fill(entries, 0, count, null);
            count = 0;
            next = 0;
            active = false;
        }

        private void clear() {
            clearParts();
            scopeOpened = false;
            segmentEnd = 0;
            blockedRoot = -1;
            cache = null;
            stamp = null;
            runtime = null;
        }
    }
}

package dev.rlcraft.ice.optimizer.compat.renderlib;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.MinecraftForgeClient;

/**
 * Scope adapter for RenderLib's sole entity/TESR traversal endpoints.  The
 * original virtual calls and exception behavior remain intact; the scope only
 * gives certified inner ModelRenderer sites a sequence/event ABI.
 */
public final class RenderLibRenderBridge {
    private static final int MAX_TRAVERSAL_DEPTH = 8;
    private static final int MAX_OBJECT_DEPTH = 16;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final AtomicLong NEXT_EVENT_SCOPE = new AtomicLong(1L);
    private static final ThreadLocal<TraversalStack> TRAVERSALS =
        new ThreadLocal<TraversalStack>() {
            @Override protected TraversalStack initialValue() {
                return new TraversalStack();
            }
        };

    private RenderLibRenderBridge() {
    }

    public static long beginEntityTraversal(Object owner) {
        try {
            return begin(owner, OptimizationModule.MODERN_ENTITY_BACKEND,
                entityPass(false, false));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return beginWithoutRuntime(owner, OptimizationModule.MODERN_ENTITY_BACKEND);
        }
    }

    public static long beginTesrTraversal(Object owner) {
        try {
            return begin(owner, OptimizationModule.MODERN_TESR_BACKEND, tesrPass());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return beginWithoutRuntime(owner, OptimizationModule.MODERN_TESR_BACKEND);
        }
    }

    public static void endTraversal(long token) {
        finish(token, null);
    }

    public static void abortTraversal(long token, Throwable error) {
        finish(token, error == null ? new IllegalStateException("render traversal aborted") : error);
    }

    public static void renderEntity(RenderManager manager, Entity entity,
                                    float partialTicks, boolean debugBoundingBox) {
        beginObject(entity, entityPass(false, false), true);
        Throwable failure = null;
        try {
            manager.renderEntityStatic(entity, partialTicks, debugBoundingBox);
        } catch (Throwable error) {
            failCurrent(error);
            failure = error;
        }
        finishObject(failure);
    }

    public static void renderMultipass(RenderManager manager, Entity entity,
                                       float partialTicks) {
        beginObject(entity, entityPass(true, false), true);
        Throwable failure = null;
        try {
            manager.renderMultipass(entity, partialTicks);
        } catch (Throwable error) {
            failCurrent(error);
            failure = error;
        }
        finishObject(failure);
    }

    public static void renderOutline(RenderManager manager, Entity entity,
                                     float partialTicks, boolean debugBoundingBox) {
        beginObject(entity, entityPass(false, true), false);
        Throwable failure = null;
        try {
            manager.renderEntityStatic(entity, partialTicks, debugBoundingBox);
        } catch (Throwable error) {
            failCurrent(error);
            failure = error;
        }
        finishObject(failure);
    }

    public static void renderTileEntity(TileEntityRendererDispatcher dispatcher,
                                        TileEntity tileEntity, float partialTicks,
                                        int destroyStage) {
        beginObject(tileEntity, tesrPass(), true);
        Throwable failure = null;
        try {
            dispatcher.render(tileEntity, partialTicks, destroyStage);
        } catch (Throwable error) {
            failCurrent(error);
            failure = error;
        }
        finishObject(failure);
    }

    public static OptimizationModule currentModule() {
        Traversal traversal = current();
        return traversal == null || !traversal.objectActive
            || traversal.objectOverflowDepth != 0 ? null : traversal.module;
    }

    public static RenderPass currentPass() {
        Traversal traversal = current();
        return traversal == null || !traversal.objectActive
            || traversal.objectOverflowDepth != 0 ? null : traversal.objectPass;
    }

    public static boolean candidateAllowed() {
        Traversal traversal = current();
        return traversal != null && traversal.objectActive
            && traversal.objectOverflowDepth == 0
            && traversal.objectCandidateAllowed && traversal.sample != null
            && traversal.sample.usesModernArm();
    }

    /**
     * Zero-allocation gate used at every patched ModelRenderer call-list site.
     * A terminal Legacy/quarantined traversal has no sample, so it bypasses
     * the much larger per-part cache/state/diagnostic path entirely.
     */
    public static boolean candidateInspectionEnabled() {
        Traversal traversal = current();
        return traversal != null && traversal.objectActive
            && traversal.objectOverflowDepth == 0 && traversal.sample != null;
    }

    public static long currentEventScope() {
        Traversal traversal = current();
        return traversal == null || !traversal.objectActive
            || traversal.objectOverflowDepth != 0 ? -1L : traversal.eventScope;
    }

    public static long nextSequence() {
        Traversal traversal = current();
        if (traversal == null || !traversal.objectActive
            || traversal.objectOverflowDepth != 0
            || traversal.sequence == Long.MAX_VALUE) return -1L;
        return traversal.sequence++;
    }

    public static void markEligibleDraw() {
        Traversal traversal = current();
        if (traversal != null) traversal.eligibleDraws++;
    }

    public static void markModernDraw() {
        Traversal traversal = current();
        if (traversal != null) traversal.modernDraws++;
    }

    private static long begin(Object owner, OptimizationModule module, RenderPass pass) {
        long token = nextTraversalToken();
        if (token == 0L) return 0L;
        TraversalStack stack = TRAVERSALS.get();
        Traversal outer = stack.peek();
        if (outer != null) outer.nestedBarrier(false,
            new IllegalStateException("nested RenderLib traversal forced to Legacy"));
        if (!stack.hasRoom()) {
            stack.overflowDepth++;
            stack.degradeAll(new IllegalStateException(
                "RenderLib traversal depth exceeded " + MAX_TRAVERSAL_DEPTH));
            return -token;
        }
        if (outer != null) {
            stack.push(new Traversal(token, owner, module, null, null));
            return token;
        }
        ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
        ModernRendererRuntime.RenderBackendSample sample = runtime == null
            ? null : runtime.beginRenderBackendSample(module, pass);
        stack.push(new Traversal(token, owner, module, runtime, sample));
        return token;
    }

    private static void finish(long token, Throwable error) {
        if (token == 0L) return;
        TraversalStack stack = TRAVERSALS.get();
        if (token < 0L) {
            if (stack.overflowDepth > 0) stack.overflowDepth--;
            else try {
                stack.drain(new IllegalStateException(
                    "RenderLib overflow token mismatch"));
            } finally {
                TRAVERSALS.remove();
            }
            if (stack.isEmpty()) TRAVERSALS.remove();
            return;
        }
        Traversal traversal = stack.peek();
        if (traversal == null || traversal.token != token) {
            try {
                stack.drain(new IllegalStateException(
                    "RenderLib traversal token mismatch"));
            } finally {
                TRAVERSALS.remove();
            }
            return;
        }
        stack.pop();
        if (stack.isEmpty()) TRAVERSALS.remove();
        complete(traversal, error);
    }

    private static void complete(Traversal traversal, Throwable error) {
        if (error != null) traversal.failed = true;
        if (traversal.runtime != null) {
            Throwable fatal = null;
            try { traversal.flushPackets(); }
            catch (Throwable failure) { fatal = failure; }
            try {
                traversal.runtime.endRenderBackendSample(traversal.sample,
                    !traversal.failed && traversal.eligibleDraws > 0,
                    traversal.modernDraws > 0);
            } catch (Throwable cleanupFailure) {
                try { traversal.cleanupFailure(cleanupFailure); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
            try { traversal.closeObjectPasses(); }
            catch (Throwable failure) { fatal = appendFailure(fatal, failure); }
            FatalErrors.rethrowIfFatal(fatal);
        }
    }

    private static void beginObject(Object object, RenderPass pass, boolean candidate) {
        Traversal traversal = current();
        if (traversal == null) return;
        boolean nested = traversal.objectActive;
        if (traversal.objectActive) {
            traversal.flushPackets();
            if (traversal.objectDepth >= traversal.nestedObjects.length) {
                traversal.objectOverflowDepth++;
                traversal.failed = true;
                return;
            }
            traversal.nestedObjects[traversal.objectDepth++].capture(
                traversal.object, traversal.objectPass,
                traversal.objectCandidateAllowed, traversal.eventScope,
                traversal.passToken);
        }
        traversal.objectActive = true;
        traversal.object = object;
        traversal.objectPass = pass;
        traversal.objectCandidateAllowed = candidate;
        try {
            try {
                traversal.eventScope = nextPositive(NEXT_EVENT_SCOPE);
            } catch (IllegalStateException exhausted) {
                traversal.eventScope = -1L;
                traversal.cleanupFailure(exhausted);
            }
            if (nested) {
                traversal.passToken = traversal.openObservedPass(pass,
                    candidate && traversal.sample != null
                        ? traversal.sample.backendId() : RenderBackendId.LEGACY);
            } else {
                traversal.passToken = 0L;
                traversal.ensureRunPass(pass, candidate);
            }
        } catch (Throwable fatal) {
            traversal.restoreOuterObject();
            FatalErrors.rethrowIfFatal(fatal);
            if (fatal instanceof RuntimeException) {
                throw (RuntimeException) fatal;
            }
            throw new IllegalStateException("RenderLib object scope failed", fatal);
        }
    }

    private static void endObject() {
        Traversal traversal = current();
        if (traversal == null || !traversal.objectActive) return;
        if (traversal.objectOverflowDepth > 0) {
            traversal.objectOverflowDepth--;
            return;
        }
        Throwable fatal = null;
        try { traversal.flushPackets(); }
        catch (Throwable failure) { fatal = failure; }
        try { traversal.closeCurrentPass(); }
        catch (Throwable failure) { fatal = appendFailure(fatal, failure); }
        traversal.restoreOuterObject();
        FatalErrors.rethrowIfFatal(fatal);
    }

    private static void finishObject(Throwable failure) {
        try { endObject(); }
        catch (Throwable cleanupFailure) {
            failure = appendFailure(failure, cleanupFailure);
        }
        if (failure != null) rethrow(failure);
    }

    private static void failCurrent(Throwable error) {
        Traversal traversal = current();
        if (traversal != null) traversal.failed = true;
    }

    private static Traversal current() {
        return TRAVERSALS.get().peek();
    }

    private static RenderPass entityPass(boolean multipass, boolean outline) {
        if (OptifinePassLifecycleBridge.isShadowPass()) {
            return RenderPass.SHADOW_ENTITY;
        }
        if (outline) return RenderPass.ENTITY_OUTLINE;
        if (multipass) return RenderPass.ENTITY_MULTIPASS;
        return MinecraftForgeClient.getRenderPass() == 1
            ? RenderPass.ENTITY_PASS_1 : RenderPass.ENTITY_PASS_0;
    }

    private static RenderPass tesrPass() {
        if (OptifinePassLifecycleBridge.isShadowPass()) {
            return RenderPass.SHADOW_TESR;
        }
        return MinecraftForgeClient.getRenderPass() == 1
            ? RenderPass.TESR_PASS_1 : RenderPass.TESR_PASS_0;
    }

    private static long nextPositive(AtomicLong counter) {
        return MonotonicTokenCounter.next(counter,
            counter == NEXT_TOKEN ? "RenderLib traversal token"
                : "RenderLib event scope token");
    }

    private static long nextTraversalToken() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "RenderLib traversal token");
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
        throw new IllegalStateException("RenderLib render traversal failed",
            failure);
    }

    private static long beginWithoutRuntime(Object owner, OptimizationModule module) {
        long token = nextTraversalToken();
        if (token == 0L) return 0L;
        TraversalStack stack = TRAVERSALS.get();
        Traversal outer = stack.peek();
        if (outer != null) outer.nestedBarrier(false,
            new IllegalStateException("nested RenderLib traversal forced to Legacy"));
        if (!stack.hasRoom()) {
            stack.overflowDepth++;
            stack.degradeAll(new IllegalStateException(
                "RenderLib traversal depth exceeded " + MAX_TRAVERSAL_DEPTH));
            return -token;
        }
        stack.push(new Traversal(token, owner, module, null, null));
        return token;
    }

    static int traversalDepthForTest() { return TRAVERSALS.get().size; }
    static int traversalOverflowForTest() {
        return TRAVERSALS.get().overflowDepth;
    }
    static void beginObjectForTest() {
        beginObject(new Object(), RenderPass.ENTITY_PASS_0, true);
    }
    static void endObjectForTest() { endObject(); }
    static int objectDepthForTest() {
        Traversal value = current();
        return value == null ? 0 : value.objectDepth;
    }
    static int objectOverflowForTest() {
        Traversal value = current();
        return value == null ? 0 : value.objectOverflowDepth;
    }
    static void resetForTest() { TRAVERSALS.remove(); }

    private static final class TraversalStack {
        private final Traversal[] values = new Traversal[MAX_TRAVERSAL_DEPTH];
        private int size;
        private int overflowDepth;

        private boolean hasRoom() { return size < values.length; }
        private boolean isEmpty() { return size == 0 && overflowDepth == 0; }
        private Traversal peek() {
            return overflowDepth != 0 || size == 0 ? null : values[size - 1];
        }
        private void push(Traversal value) { values[size++] = value; }
        private Traversal pop() {
            Traversal value = values[--size];
            values[size] = null;
            return value;
        }
        private void degradeAll(Throwable error) {
            for (int i = size - 1; i >= 0; i--) {
                values[i].nestedBarrier(true, error);
            }
        }
        private void drain(Throwable error) {
            overflowDepth = 0;
            Throwable fatal = null;
            while (size > 0) {
                try { pop().drain(error); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
            FatalErrors.rethrowIfFatal(fatal);
        }
    }

    private static final class Traversal {
        private final long token;
        @SuppressWarnings("unused") private final Object owner;
        private final OptimizationModule module;
        private final ModernRendererRuntime runtime;
        private final ModernRendererRuntime.RenderBackendSample sample;
        private long sequence;
        private final ObjectScope[] nestedObjects =
            new ObjectScope[MAX_OBJECT_DEPTH];
        private int objectDepth;
        private int objectOverflowDepth;
        private long eventScope = -1L;
        private long passToken;
        private long runPassToken;
        private RenderPass runPass;
        private Object object;
        private RenderPass objectPass;
        private boolean objectActive;
        private boolean objectCandidateAllowed;
        private boolean failed;
        private int eligibleDraws;
        private int modernDraws;

        private Traversal(long token, Object owner, OptimizationModule module,
                          ModernRendererRuntime runtime,
                          ModernRendererRuntime.RenderBackendSample sample) {
            this.token = token;
            this.owner = owner;
            this.module = module;
            this.runtime = runtime;
            this.sample = sample;
            for (int i = 0; i < nestedObjects.length; i++) {
                nestedObjects[i] = new ObjectScope();
            }
        }

        private void nestedBarrier(boolean recordFailure, Throwable error) {
            objectCandidateAllowed = false;
            failed = true;
            if (runtime != null) {
                flushPackets();
                if (recordFailure) try {
                    runtime.modelTraversalFailure(module, error);
                } catch (Throwable reportingFailure) {
                    FatalErrors.rethrowIfFatal(reportingFailure);
                }
            }
        }

        private void drain(Throwable error) {
            failed = true;
            objectCandidateAllowed = false;
            if (runtime == null) return;
            Throwable fatal = null;
            try { flushPackets(); }
            catch (Throwable failure) { fatal = failure; }
            try {
                runtime.endRenderBackendSample(sample, false,
                    modernDraws > 0);
            } catch (Throwable cleanupFailure) {
                try { cleanupFailure(cleanupFailure); }
                catch (Throwable failure) {
                    fatal = appendFailure(fatal, failure);
                }
            }
            try { runtime.modelTraversalFailure(module, error); }
            catch (Throwable reportingFailure) {
                if (FatalErrors.findFatal(reportingFailure) != null) {
                    fatal = appendFailure(fatal, reportingFailure);
                }
            }
            try { closeObjectPasses(); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            }
            FatalErrors.rethrowIfFatal(fatal);
        }

        private void closeCurrentPass() {
            long token = passToken;
            passToken = 0L;
            if (runtime != null && token != 0L) {
                try { runtime.endObservedPass(token); }
                catch (Throwable cleanupFailure) {
                    cleanupFailure(cleanupFailure);
                }
            }
        }

        private void closeObjectPasses() {
            Throwable fatal = null;
            try { closeCurrentPass(); }
            catch (Throwable failure) { fatal = failure; }
            while (objectDepth > 0) {
                ObjectScope outer = nestedObjects[--objectDepth];
                long token = outer.passToken;
                outer.passToken = 0L;
                if (runtime != null && token != 0L) {
                    try { runtime.endObservedPass(token); }
                    catch (Throwable cleanupFailure) {
                        try { cleanupFailure(cleanupFailure); }
                        catch (Throwable failure) {
                            fatal = appendFailure(fatal, failure);
                        }
                    }
                }
            }
            try { closeRunPass(); }
            catch (Throwable failure) {
                fatal = appendFailure(fatal, failure);
            } finally {
                objectActive = false;
                objectOverflowDepth = 0;
            }
            FatalErrors.rethrowIfFatal(fatal);
        }

        private void ensureRunPass(RenderPass pass, boolean candidate) {
            if (runtime == null || pass == null || runPass == pass) return;
            closeRunPass();
            runPass = pass;
            try {
                runPassToken = runtime.beginObservedPass(pass,
                    candidate && sample != null ? sample.backendId()
                        : RenderBackendId.LEGACY);
            } catch (Throwable failure) {
                runPass = null;
                cleanupFailure(failure);
            }
        }

        private long openObservedPass(RenderPass pass,
                                      RenderBackendId backend) {
            if (runtime == null) return 0L;
            try { return runtime.beginObservedPass(pass, backend); }
            catch (Throwable failure) {
                cleanupFailure(failure);
                return 0L;
            }
        }

        private void restoreOuterObject() {
            if (objectDepth == 0) {
                objectActive = false;
                object = null;
                objectPass = null;
                objectCandidateAllowed = false;
                eventScope = -1L;
                passToken = 0L;
                return;
            }
            ObjectScope outer = nestedObjects[--objectDepth];
            object = outer.object;
            objectPass = outer.pass;
            objectCandidateAllowed = outer.candidateAllowed;
            eventScope = outer.eventScope;
            passToken = outer.passToken;
            outer.clear();
        }

        private void closeRunPass() {
            long token = runPassToken;
            runPassToken = 0L;
            runPass = null;
            if (runtime != null && token != 0L) {
                try { runtime.endObservedPass(token); }
                catch (Throwable cleanupFailure) {
                    cleanupFailure(cleanupFailure);
                }
            }
        }

        private void flushPackets() {
            if (runtime == null) return;
            try {
                runtime.flushModelPackets(module);
            } catch (Throwable error) {
                cleanupFailure(error);
            }
        }

        private void cleanupFailure(Throwable error) {
            failed = true;
            objectCandidateAllowed = false;
            FatalErrors.rethrowIfFatal(error);
            if (runtime == null || error == null) return;
            try { runtime.modelTraversalFailure(module, error); }
            catch (Throwable reportingFailure) {
                FatalErrors.rethrowIfFatal(reportingFailure);
            }
        }
    }

    private static final class ObjectScope {
        private Object object;
        private RenderPass pass;
        private boolean candidateAllowed;
        private long eventScope;
        private long passToken;

        private void capture(Object object, RenderPass pass,
                             boolean candidateAllowed, long eventScope,
                             long passToken) {
            this.object = object;
            this.pass = pass;
            this.candidateAllowed = candidateAllowed;
            this.eventScope = eventScope;
            this.passToken = passToken;
        }

        private void clear() {
            object = null;
            pass = null;
            candidateAllowed = false;
            eventScope = -1L;
            passToken = 0L;
        }
    }
}

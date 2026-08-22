package dev.rlcraft.ice.optimizer.compat.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.particle.ParticleOutputValidator;
import dev.rlcraft.ice.optimizer.render.particle.ParticleState;
import dev.rlcraft.ice.optimizer.render.particle.VanillaBillboardEmitter;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;

/** Final ParticleManager emitter bridge with exact legacy fallback. */
public final class ParticleRenderBridge {
    private static final int MAX_SCOPE_DEPTH = 8;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final Class<?>[] RENDER_PARAMETERS = {
        BufferBuilder.class, Entity.class, Float.TYPE, Float.TYPE, Float.TYPE,
        Float.TYPE, Float.TYPE, Float.TYPE
    };
    private static final ClassValue<Boolean> VANILLA_BILLBOARD =
        new ClassValue<Boolean>() {
            @Override protected Boolean computeValue(Class<?> type) {
                Class<?> current = type;
                while (current != null) {
                    Method[] methods;
                    try {
                        methods = current.getDeclaredMethods();
                    } catch (Throwable unavailable) {
                        FatalErrors.rethrowIfFatal(unavailable);
                        return Boolean.FALSE;
                    }
                    for (Method method : methods) {
                        if (method.getReturnType() == Void.TYPE
                            && sameParameters(method.getParameterTypes(), RENDER_PARAMETERS)) {
                            return Boolean.valueOf(current == Particle.class);
                        }
                    }
                    current = current.getSuperclass();
                }
                return Boolean.FALSE;
            }
        };
    private static final ThreadLocal<TraversalState> SCOPES =
        new ThreadLocal<TraversalState>() {
            @Override protected TraversalState initialValue() {
                return new TraversalState();
            }
        };

    private ParticleRenderBridge() {
    }

    public static long begin(Object manager, Entity camera, float partialTicks) {
        long token = nextPositive();
        if (token == 0L) return 0L;
        TraversalState scopes = SCOPES.get();
        Scope outer = scopes.peek();
        if (outer != null) {
            outer.nestedBarrier(new IllegalStateException(
                "nested particle traversal forced to Legacy"));
        }
        if (!scopes.hasRoom()) {
            scopes.overflowDepth++;
            scopes.degradeAll(new IllegalStateException(
                "particle traversal depth exceeded " + MAX_SCOPE_DEPTH));
            return -token;
        }
        if (outer != null) {
            scopes.push(new Scope(token, null, null, null, null, false, 0L));
            return token;
        }
        ModernRendererRuntime runtime = null;
        ModernRendererRuntime.RenderBackendSample sample = null;
        ParticleInstanceStream stream = null;
        LwjglParticleRenderer renderer = null;
        boolean modern = false;
        try {
            runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) {
                sample = runtime.beginRenderBackendSample(
                    OptimizationModule.MODERN_PARTICLE_BACKEND,
                    RenderPass.PARTICLES);
                stream = runtime.particles();
                if (stream != null) stream.discardAtBarrier();
                renderer = runtime.particleRenderer();
                if (renderer != null) {
                    renderer.discard();
                    renderer.prepare(runtime.resourceGeneration(),
                        runtime.glContextGeneration());
                }
                if (sample != null
                    && sample.lifecycleState() == BackendLifecycleState.OUTPUT_VALIDATE) {
                    ParticleOutputValidator.Result validation =
                        ParticleOutputValidator.validate();
                    runtime.recordValidation(OptimizationModule.MODERN_PARTICLE_BACKEND,
                        validation.isEquivalent(), validation.getDetail());
                }
                modern = sample != null && sample.usesModernArm()
                    && renderer != null
                    && runtime.isShaderPackSafeForNativeVertexFormats();
            }
        } catch (Throwable error) {
            safeParticleFailure(runtime, error);
            modern = false;
        }
        long passToken = 0L;
        if (runtime != null) {
            try {
                passToken = runtime.beginObservedPass(RenderPass.PARTICLES,
                    sample == null ? RenderBackendId.LEGACY : sample.backendId());
            } catch (Throwable observationFailure) {
                safeParticleFailure(runtime, observationFailure);
                modern = false;
            }
        }
        scopes.push(new Scope(token, runtime, sample, stream, renderer, modern,
            passToken));
        return token;
    }

    /** Legacy lit-particle traversal is still an independently observed pass. */
    public static long beginLit() {
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            return runtime == null ? 0L : runtime.beginObservedPass(
                RenderPass.LIT_PARTICLES, RenderBackendId.LEGACY);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return 0L;
        }
    }

    public static void endLit(long token) {
        if (token == 0L) return;
        try {
            ModernRendererRuntime runtime =
                ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.endObservedPass(token);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    public static void end(long token) {
        finish(token, null);
    }

    public static void abort(long token, Throwable error) {
        finish(token, error == null
            ? new IllegalStateException("particle traversal aborted") : error);
    }

    /** Exact ParticleManager BufferBuilder begin boundary. */
    public static void beginBuffer(BufferBuilder buffer, int mode,
                                   VertexFormat format) {
        buffer.begin(mode, format);
        Scope scope = SCOPES.get().peek();
        if (scope != null) scope.beginBuffer(buffer);
    }

    /** Flushes any preceding native run before ParticleManager's legacy draw. */
    public static void draw(Tessellator tessellator) {
        Scope scope = SCOPES.get().peek();
        if (scope != null) scope.beforeManagerDraw(tessellator);
        tessellator.draw();
    }

    public static void render(Particle particle, BufferBuilder buffer,
                              Entity camera, float partialTicks,
                              float rotationX, float rotationZ,
                              float rotationYZ, float rotationXY,
                              float rotationXZ) {
        Scope scope = SCOPES.get().peek();
        long sequence = scope == null ? -1L : scope.nextSequence++;
        ParticleRenderAccess access = particle instanceof ParticleRenderAccess
            ? (ParticleRenderAccess) particle : null;
        if (access != null) {
            // Explicit TextureAtlasSprite particles do not have to publish an
            // OptiFine BufferBuilder BitSet.  Mark them before either emitter
            // can add geometry that will sample the atlas at manager draw.
            AnimatedTextureVisibilityBridge.spriteVisible(
                access.ice$particleTexture());
        }
        if (scope == null || !scope.modern || scope.stream == null
            || scope.renderer == null
            || access == null
            || !isVanillaBillboard(particle.getClass())) {
            legacy(scope, buffer);
            particle.renderParticle(buffer, camera, partialTicks, rotationX,
                rotationZ, rotationYZ, rotationXY, rotationXZ);
            return;
        }

        if (!scope.prepareModern(buffer)) {
            legacy(scope, buffer);
            particle.renderParticle(buffer, camera, partialTicks, rotationX,
                rotationZ, rotationYZ, rotationXY, rotationXZ);
            return;
        }

        ParticleState state;
        try {
            state = scope.state();
        } catch (Throwable error) {
            safeParticleFailure(scope.runtime, error);
            legacy(scope, buffer);
            particle.renderParticle(buffer, camera, partialTicks, rotationX,
                rotationZ, rotationYZ, rotationXY, rotationXZ);
            return;
        }
        if (state == null || !scope.stream.canRecord(sequence)) {
            legacy(scope, buffer);
            particle.renderParticle(buffer, camera, partialTicks, rotationX,
                rotationZ, rotationYZ, rotationXY, rotationXZ);
            return;
        }

        scope.eligible++;
        try {
            if (VanillaBillboardEmitter.emitToRenderer(particle,
                access, scope.renderer, camera, partialTicks,
                rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ,
                scope.stream, state, sequence, scope.corners)) {
                scope.lastEmission = Emission.MODERN;
                return;
            }
        } catch (RuntimeException | Error error) {
            // Once the first BufferBuilder vertex is entered, replaying the
            // original method could duplicate transparent geometry.  Preserve
            // the original manager's crash-report path instead.
            scope.failed = true;
            safeParticleFailure(scope.runtime, error);
            throw error;
        }
        legacy(scope, buffer);
        particle.renderParticle(buffer, camera, partialTicks, rotationX,
            rotationZ, rotationYZ, rotationXY, rotationXZ);
    }

    /** Called by the reviewed FBP internal Tessellator boundaries. */
    public static void fbpBoundary() {
        Scope scope = SCOPES.get().peek();
        if (scope != null) scope.barrier();
    }

    static boolean isVanillaBillboard(Class<?> type) {
        return type != null && VANILLA_BILLBOARD.get(type).booleanValue();
    }

    private static void finish(long token, Throwable error) {
        if (token == 0L) return;
        TraversalState scopes = SCOPES.get();
        if (token < 0L) {
            if (scopes.overflowDepth > 0) {
                scopes.overflowDepth--;
                if (scopes.isEmpty()) SCOPES.remove();
            } else {
                drainAndRemove(scopes, new IllegalStateException(
                    "particle overflow token mismatch"));
            }
            return;
        }
        Scope scope = scopes.peek();
        if (scope == null || scope.token != token) {
            drainAndRemove(scopes, new IllegalStateException(
                "particle traversal token mismatch"));
            return;
        }
        scopes.pop();
        if (scopes.isEmpty()) SCOPES.remove();
        complete(scope, error);
    }

    private static void drainAndRemove(TraversalState scopes,
                                       Throwable reason) {
        try { scopes.drain(reason); }
        finally { SCOPES.remove(); }
    }

    private static void complete(Scope scope, Throwable error) {
        if (scope.runtime == null) return;
        Throwable failure = error;
        Throwable finishFailure = null;
        try {
            if (scope.renderer != null && scope.renderer.size() > 0) {
                scope.failed = true;
                finishFailure = appendFailure(finishFailure,
                    new IllegalStateException(
                        "particle stream escaped manager draw boundary"));
                try { scope.renderer.discard(); }
                catch (Throwable cleanupFailure) {
                    finishFailure = appendFailure(finishFailure,
                        cleanupFailure);
                }
            }
        } catch (Throwable cleanupFailure) {
            finishFailure = appendFailure(finishFailure, cleanupFailure);
        }
        try {
            if (scope.stream != null) scope.stream.discardAtBarrier();
        } catch (Throwable cleanupFailure) {
            finishFailure = appendFailure(finishFailure, cleanupFailure);
        }
        try {
            scope.runtime.recordParticleInstances(scope.submittedInstances,
                scope.legacyFallbacks);
        } catch (Throwable cleanupFailure) {
            finishFailure = appendFailure(finishFailure, cleanupFailure);
        }
        try {
            scope.runtime.endRenderBackendSample(scope.sample,
                error == null && !scope.failed && finishFailure == null,
                scope.submittedInstances > 0);
        } catch (Throwable cleanupFailure) {
            finishFailure = appendFailure(finishFailure, cleanupFailure);
        }
        try {
            if (finishFailure != null) {
                scope.failed = true;
                scope.modern = false;
                scope.runtime.particleBackendFailure(finishFailure);
            }
        } catch (Throwable reportingFailure) {
            finishFailure = appendFailure(finishFailure, reportingFailure);
        }
        failure = appendFailure(failure, finishFailure);
        failure = appendFailure(failure, scope.closePass());
        FatalErrors.rethrowIfFatal(failure);
    }

    private static void legacy(Scope scope, BufferBuilder buffer) {
        if (scope == null) return;
        scope.flushPending(buffer);
        if (scope.stream != null) scope.stream.barrier();
        scope.legacyFallbacks++;
        scope.lastEmission = Emission.LEGACY;
    }

    private static boolean sameParameters(Class<?>[] left, Class<?>[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) return false;
        }
        return true;
    }

    private static long nextPositive() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "particle bridge token");
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

    private static void safeParticleFailure(ModernRendererRuntime runtime,
                                            Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (runtime == null) return;
        try { runtime.particleBackendFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    static int activeDepthForTest() { return SCOPES.get().size; }
    static int overflowDepthForTest() { return SCOPES.get().overflowDepth; }
    static boolean currentModernForTest() {
        Scope scope = SCOPES.get().peek();
        return scope != null && scope.modern;
    }
    static void drainForTest(Throwable reason) {
        drainAndRemove(SCOPES.get(), reason);
    }
    static void resetForTest() { SCOPES.remove(); }

    private static final class TraversalState {
        private final Scope[] values = new Scope[MAX_SCOPE_DEPTH];
        private int size;
        private int overflowDepth;

        private boolean hasRoom() { return size < values.length; }
        private boolean isEmpty() { return size == 0 && overflowDepth == 0; }
        private Scope peek() {
            return overflowDepth != 0 || size == 0 ? null : values[size - 1];
        }
        private void push(Scope scope) { values[size++] = scope; }
        private Scope pop() {
            Scope result = values[--size];
            values[size] = null;
            return result;
        }

        private void degradeAll(Throwable reason) {
            for (int i = size - 1; i >= 0; i--) values[i].nestedBarrier(reason);
        }

        private void drain(Throwable reason) {
            overflowDepth = 0;
            Throwable failure = null;
            while (size > 0) {
                Scope scope = pop();
                try { scope.drain(reason); }
                catch (Throwable drainFailure) {
                    failure = appendFailure(failure, drainFailure);
                }
            }
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static final class Scope {
        private final long token;
        private final ModernRendererRuntime runtime;
        private final ModernRendererRuntime.RenderBackendSample sample;
        private final ParticleInstanceStream stream;
        private final LwjglParticleRenderer renderer;
        private boolean modern;
        private final double[] corners = new double[12];
        private long nextSequence;
        private long stateSerial = Long.MIN_VALUE;
        private ParticleState state;
        private int eligible;
        private int submittedInstances;
        private int legacyFallbacks;
        private boolean failed;
        private BufferBuilder buffer;
        private Emission lastEmission = Emission.NONE;
        private long passToken;

        private Scope(long token, ModernRendererRuntime runtime,
                      ModernRendererRuntime.RenderBackendSample sample,
                      ParticleInstanceStream stream,
                      LwjglParticleRenderer renderer, boolean modern,
                      long passToken) {
            this.token = token;
            this.runtime = runtime;
            this.sample = sample;
            this.stream = stream;
            this.renderer = renderer;
            this.modern = modern;
            this.passToken = passToken;
        }

        private void beginBuffer(BufferBuilder value) {
            buffer = value;
            lastEmission = Emission.NONE;
            state = null;
            stateSerial = Long.MIN_VALUE;
            if (renderer != null && renderer.size() > 0) {
                renderer.discard();
                fail(new IllegalStateException(
                    "particle run crossed BufferBuilder begin"));
            }
            if (!LwjglParticleRenderer.safeFallback(value)) modern = false;
        }

        private void beforeManagerDraw(Tessellator tessellator) {
            BufferBuilder value = tessellator == null ? null
                : tessellator.getBuffer();
            if (value != buffer) {
                fail(new IllegalStateException("ParticleManager Tessellator changed"));
                if (renderer != null) renderer.discard();
                return;
            }
            flushPending(value);
            lastEmission = Emission.NONE;
        }

        private boolean prepareModern(BufferBuilder value) {
            if (!modern || renderer == null || value == null
                || value != buffer || !LwjglParticleRenderer.safeFallback(value)) {
                return false;
            }
            if (!flushLegacyBeforeModern(value)) return false;
            if (!renderer.canRecord()) flushPending(value);
            return modern && renderer.canRecord()
                && flushLegacyBeforeModern(value);
        }

        private boolean flushLegacyBeforeModern(BufferBuilder value) {
            if (lastEmission != Emission.LEGACY || value.getVertexCount() == 0) {
                return true;
            }
            Tessellator tessellator = Tessellator.getInstance();
            if (tessellator.getBuffer() != value) return false;
            int mode = value.getDrawMode();
            VertexFormat format = value.getVertexFormat();
            tessellator.draw();
            value.begin(mode, format);
            state = null;
            stateSerial = Long.MIN_VALUE;
            lastEmission = Emission.NONE;
            return true;
        }

        private void flushPending(BufferBuilder fallback) {
            if (renderer == null || renderer.size() == 0) return;
            int pending = renderer.size();
            LwjglParticleRenderer.FlushResult result;
            try {
                result = renderer.flush(state, EarlyGlStateTracker.snapshot(), fallback);
            } catch (Throwable error) {
                fail(error);
                if (error instanceof RuntimeException) {
                    throw (RuntimeException) error;
                }
                if (error instanceof Error) throw (Error) error;
                throw new IllegalStateException(error);
            }
            if (stream != null) stream.barrier();
            if (result == LwjglParticleRenderer.FlushResult.MODERN
                || result == LwjglParticleRenderer.FlushResult.FAILED_AFTER_DRAW) {
                submittedInstances += pending;
                lastEmission = Emission.NONE;
            } else {
                legacyFallbacks += pending;
                lastEmission = Emission.LEGACY;
            }
            if (result == LwjglParticleRenderer.FlushResult.FAILED_AFTER_DRAW) {
                Throwable error = renderer.getLastError();
                fail(error == null ? new IllegalStateException(
                    "particle submission failed after draw") : error);
            } else if (result
                == LwjglParticleRenderer.FlushResult.FAILED_APPENDED) {
                Throwable error = renderer.getLastError();
                fail(error == null ? new IllegalStateException(
                    "particle submission failed before draw") : error);
            }
        }

        private void barrier() {
            // FBP replaces ParticleManager's own Tessellator.draw sites.  Any
            // earlier certified billboard run must therefore be submitted (or
            // appended to the still-active manager buffer) before that draw;
            // merely advancing the packet sequence would reverse transparent
            // particle order whenever FBP followed a native run.
            if (renderer != null && renderer.size() > 0) {
                if (buffer != null) {
                    flushPending(buffer);
                } else {
                    renderer.discard();
                    fail(new IllegalStateException(
                        "FBP boundary without ParticleManager buffer"));
                }
            }
            if (stream != null) stream.barrier();
        }

        private void nestedBarrier(Throwable reason) {
            Throwable failure = reason;
            if (renderer != null && renderer.size() > 0) {
                if (buffer != null) {
                    try { flushPending(buffer); }
                    catch (Throwable error) {
                        failure = appendFailure(failure, error);
                        try { renderer.discard(); }
                        catch (Throwable cleanupFailure) {
                            failure = appendFailure(failure, cleanupFailure);
                        }
                    }
                } else {
                    try { renderer.discard(); }
                    catch (Throwable cleanupFailure) {
                        failure = appendFailure(failure, cleanupFailure);
                    }
                }
            }
            try { if (stream != null) stream.barrier(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            fail(failure);
        }

        private void drain(Throwable reason) {
            failed = true;
            modern = false;
            Throwable failure = reason;
            if (renderer != null && renderer.size() > 0) {
                try {
                    if (buffer != null) flushPending(buffer);
                    else renderer.discard();
                } catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                    try { renderer.discard(); }
                    catch (Throwable discardFailure) {
                        failure = appendFailure(failure, discardFailure);
                    }
                }
            }
            try { if (stream != null) stream.discardAtBarrier(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            if (runtime != null) {
                try {
                    runtime.recordParticleInstances(submittedInstances,
                        legacyFallbacks);
                } catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                try {
                    runtime.endRenderBackendSample(sample, false,
                        submittedInstances > 0);
                } catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
                try { runtime.particleBackendFailure(failure); }
                catch (Throwable reportingFailure) {
                    failure = appendFailure(failure, reportingFailure);
                }
            }
            failure = appendFailure(failure, closePass());
            FatalErrors.rethrowIfFatal(failure);
        }

        private Throwable closePass() {
            long value = passToken;
            passToken = 0L;
            if (runtime != null && value != 0L) {
                try { runtime.endObservedPass(value); }
                catch (Throwable cleanupFailure) {
                    return cleanupFailure;
                }
            }
            return null;
        }

        private void fail(Throwable error) {
            failed = true;
            modern = false;
            safeParticleFailure(runtime, error);
        }

        private ParticleState state() {
            long serial = EarlyGlStateTracker.drawStateSerial();
            if (state != null && serial == stateSerial) return state;
            EarlyGlStateTracker.Snapshot gl = EarlyGlStateTracker.snapshot();
            if (gl == null || !gl.hasParticleState()) return null;
            state = new ParticleState(0, gl.getTexture0(), gl.getTexture1(),
                gl.getProgram(), gl.isBlend(), gl.getBlendSourceRgb(),
                gl.getBlendDestinationRgb(), gl.getBlendSourceAlpha(),
                gl.getBlendDestinationAlpha(), gl.isDepthTest(),
                gl.isDepthMask(), gl.isCull(), gl.getColorMask(), false, token);
            stateSerial = serial;
            return state;
        }
    }

    private enum Emission { NONE, MODERN, LEGACY }
}

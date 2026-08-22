package dev.rlcraft.ice.optimizer.compat.particle;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.VertexFormat;

/** Exact FBP render scope plus raw BLOCK-packet submission at reviewed draws. */
public final class FbpParticleRenderBridge {
    private static final int MAX_DEPTH = 8;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<Scope>();

    private FbpParticleRenderBridge() {
    }

    public static long enter(Object particle) {
        long token = nextPositive();
        if (token == 0L) return 0L;
        Scope parent = CURRENT.get();
        int depth = parent == null ? 1 : parent.depth + 1;
        if (parent != null) parent.fail(new IllegalStateException(
            "nested FBP particle render forced to Legacy"));
        if (depth > MAX_DEPTH) return -token;

        ModernRendererRuntime runtime = null;
        ModernRendererRuntime.RenderBackendSample sample = null;
        LwjglFbpPacketRenderer renderer = null;
        boolean modern = false;
        try {
            runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) {
                sample = runtime.beginRenderBackendSample(
                    OptimizationModule.FBP_PARTICLE_ADAPTER,
                    RenderPass.PARTICLES);
                renderer = runtime.fbpPacketRenderer();
                if (renderer != null) renderer.prepare(runtime.resourceGeneration(),
                    runtime.glContextGeneration());
                if (sample != null && sample.lifecycleState()
                    == BackendLifecycleState.OUTPUT_VALIDATE) {
                    LwjglFbpPacketRenderer.Validation validation =
                        LwjglFbpPacketRenderer.validateFormat();
                    runtime.recordValidation(OptimizationModule.FBP_PARTICLE_ADAPTER,
                        validation.isEquivalent(), validation.getDetail());
                }
                modern = sample != null && sample.usesModernArm()
                    && renderer != null
                    && runtime.isShaderPackSafeForNativeVertexFormats();
            }
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            reportFailure(runtime, error);
            modern = false;
        }
        CURRENT.set(new Scope(token, depth, parent, runtime, sample, renderer,
            modern));
        return token;
    }

    public static void exit(long token) {
        try { finish(token, null); }
        catch (Throwable error) {
            CURRENT.remove();
            FatalErrors.rethrowIfFatal(error);
        }
    }

    public static void abort(long token, Throwable error) {
        try {
            finish(token, error == null
                ? new IllegalStateException("FBP render aborted") : error);
        } catch (Throwable cleanupFailure) {
            // Cleanup instrumentation must not replace the original
            // renderParticle exception propagated by the ASM wrapper.
            CURRENT.remove();
            FatalErrors.rethrowIfFatal(cleanupFailure);
        }
    }

    public static void draw(Tessellator tessellator) {
        try { ParticleRenderBridge.fbpBoundary(); }
        catch (Throwable boundaryFailure) {
            FatalErrors.rethrowIfFatal(boundaryFailure);
        }
        Scope scope = CURRENT.get();
        boolean submitted = false;
        if (scope != null) {
            try { submitted = scope.tryDraw(tessellator); }
            catch (Throwable error) {
                FatalErrors.rethrowIfFatal(error);
                scope.fail(error);
            }
        }
        if (!submitted) tessellator.draw();
    }

    public static void begin(BufferBuilder buffer, int mode, VertexFormat format) {
        buffer.begin(mode, format);
        try { ParticleRenderBridge.fbpBoundary(); }
        catch (Throwable boundaryFailure) {
            FatalErrors.rethrowIfFatal(boundaryFailure);
        }
    }

    private static void finish(long token, Throwable error) {
        if (token == 0L) return;
        if (token < 0L) return;
        Scope scope = CURRENT.get();
        if (scope == null || scope.token != token) {
            while (scope != null) {
                scope.complete(new IllegalStateException("FBP scope token mismatch"));
                scope = scope.parent;
            }
            CURRENT.remove();
            return;
        }
        if (scope.parent == null) CURRENT.remove();
        else CURRENT.set(scope.parent);
        scope.complete(error);
    }

    private static long nextPositive() {
        return MonotonicTokenCounter.nextOrZero(NEXT_TOKEN,
            "FBP particle bridge token");
    }

    static int activeDepthForTest() {
        Scope scope = CURRENT.get();
        return scope == null ? 0 : scope.depth;
    }
    static boolean currentModernForTest() {
        Scope scope = CURRENT.get();
        return scope != null && scope.modern;
    }
    static void resetForTest() { CURRENT.remove(); }

    private static void reportFailure(ModernRendererRuntime runtime,
                                      Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        if (runtime == null) return;
        try { runtime.fbpPacketFailure(error); }
        catch (Throwable reportingFailure) {
            FatalErrors.rethrowIfFatal(reportingFailure);
        }
    }

    private static final class Scope {
        private final long token;
        private final int depth;
        private final Scope parent;
        private final ModernRendererRuntime runtime;
        private final ModernRendererRuntime.RenderBackendSample sample;
        private final LwjglFbpPacketRenderer renderer;
        private boolean modern;
        private boolean failed;
        private int submittedPackets;

        private Scope(long token, int depth, Scope parent,
                      ModernRendererRuntime runtime,
                      ModernRendererRuntime.RenderBackendSample sample,
                      LwjglFbpPacketRenderer renderer, boolean modern) {
            this.token = token;
            this.depth = depth;
            this.parent = parent;
            this.runtime = runtime;
            this.sample = sample;
            this.renderer = renderer;
            this.modern = modern;
        }

        private boolean tryDraw(Tessellator tessellator) {
            if (!modern || renderer == null || tessellator == null) return false;
            boolean submitted = false;
            try {
                BufferBuilder buffer = tessellator.getBuffer();
                if (buffer == null || buffer.getVertexCount() <= 0
                    || !LwjglFbpPacketRenderer.isExactBlockFormat(
                        buffer.getVertexFormat())) return false;
                int vertices = buffer.getVertexCount();
                LwjglFbpPacketRenderer.SubmitResult result =
                    renderer.submitRawPacket(buffer,
                        EarlyGlStateTracker.snapshot(),
                        EarlyGlStateTracker.compatibilitySnapshot());
                submitted = result.submittedModern();
                if (submitted) {
                    submittedPackets++;
                    try {
                        if (runtime != null) runtime.recordFbpPacket(vertices, true);
                    } catch (Throwable telemetryFailure) {
                        FatalErrors.rethrowIfFatal(telemetryFailure);
                        fail(telemetryFailure);
                    }
                    if (result.failed()) {
                        Throwable error = renderer.getLastError();
                        fail(error == null ? new IllegalStateException(
                            "FBP submission failed after draw") : error);
                    }
                    return true;
                }
                if (result.failed()) {
                    Throwable error = renderer.getLastError();
                    fail(error == null ? new IllegalStateException(
                        "FBP submission failed before draw") : error);
                }
                try {
                    if (runtime != null) runtime.recordFbpPacket(vertices, false);
                } catch (Throwable telemetryFailure) {
                    FatalErrors.rethrowIfFatal(telemetryFailure);
                    fail(telemetryFailure);
                }
                return false;
            } catch (Throwable error) {
                FatalErrors.rethrowIfFatal(error);
                fail(error);
                // Never replay once the renderer reported that it consumed
                // the packet; transparent geometry would be duplicated.
                return submitted;
            }
        }

        private void fail(Throwable error) {
            failed = true;
            modern = false;
            reportFailure(runtime, error == null
                ? new IllegalStateException("unknown FBP packet failure") : error);
        }

        private void complete(Throwable error) {
            if (runtime == null) return;
            try {
                runtime.endRenderBackendSample(sample,
                    error == null && !failed, submittedPackets > 0);
            } catch (Throwable finishError) {
                FatalErrors.rethrowIfFatal(finishError);
                reportFailure(runtime, finishError);
            }
        }
    }
}

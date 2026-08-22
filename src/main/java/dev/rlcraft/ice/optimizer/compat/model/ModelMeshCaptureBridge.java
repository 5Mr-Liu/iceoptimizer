package dev.rlcraft.ice.optimizer.compat.model;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime;
import dev.rlcraft.ice.optimizer.compat.renderlib.RenderLibRenderBridge;
import dev.rlcraft.ice.optimizer.render.entity.ModelMeshPayload;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * Captures the exact BufferBuilder bytes while vanilla compiles a
 * ModelRenderer display list.  Ordinary rendering is never suppressed here;
 * the VBO backend makes that decision later at the original call-list site.
 */
public final class ModelMeshCaptureBridge {
    private static final String ENTITY_MODULE = "modern-entity-backend";
    private static final String TESR_MODULE = "modern-tesr-backend";
    private static final int MAX_CAPTURE_DEPTH = 8;
    private static final int MAX_MESH_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PENDING_MESHES = 8192;
    private static final long MAX_PENDING_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_PUBLICATION_BACKOFF_SHIFT = 6;
    private static final ThreadLocal<CaptureState> CAPTURES =
        new ThreadLocal<CaptureState>() {
            @Override protected CaptureState initialValue() {
                return new CaptureState();
            }
        };
    private static final Map<Class<?>, Field> DISPLAY_LIST_FIELDS =
        new ConcurrentHashMap<Class<?>, Field>();
    private static final LinkedHashMap<Integer, PendingModelMesh> PENDING =
        new LinkedHashMap<Integer, PendingModelMesh>(64, 0.75F, true);
    private static volatile boolean hasPendingMeshes;
    private static long pendingBytes;
    private static boolean pendingPurgeInitialized;
    private static long lastPurgedResourceGeneration;
    private static long lastPurgedContextGeneration;
    private static ContextCapabilities lastPurgedContextIdentity;
    private static long captureBegins;
    private static long enabledCaptures;
    private static long earlyCaptures;
    private static long disabledCaptures;
    private static long capturedQuads;
    private static long rejectedCaptures;
    private static long completedCaptures;
    private static long immediatePublications;
    private static long deferredPublications;
    private static long lateQualifications;
    private static long stalePendingDrops;
    private static long disabledPendingDrops;
    private static long pendingPurgeScans;
    private static long publicationAttempts;
    private static long publicationFailures;
    private static long publicationRetrySuppressions;
    private static long publicationDrainCycles;
    private static long publicationDrainExamined;
    private static long drainedPublications;

    private ModelMeshCaptureBridge() {
    }

    public static void begin(Object owner) {
        captureBegins = safeAdd(captureBegins, 1L);
        CaptureState state = CAPTURES.get();
        if (state.overflow != 0 || state.stack.size() >= MAX_CAPTURE_DEPTH) {
            state.overflow++;
            return;
        }
        boolean configured = OptimizerBridge.isEnabled(ENTITY_MODULE)
            || OptimizerBridge.isEnabled(TESR_MODULE);
        boolean early = !configured && earlyCaptureAvailable();
        boolean enabled = owner != null && (configured || early);
        if (enabled && early) earlyCaptures = safeAdd(earlyCaptures, 1L);
        if (enabled) enabledCaptures = safeAdd(enabledCaptures, 1L);
        else disabledCaptures = safeAdd(disabledCaptures, 1L);
        state.stack.push(new Capture(owner, enabled));
    }

    /** Called before each TexturedQuad asks Tessellator to reset its builder. */
    public static void captureQuad(BufferBuilder builder) {
        CaptureState state = CAPTURES.get();
        if (state.overflow != 0) return;
        Capture capture = state.stack.peek();
        if (capture == null || !capture.valid || !capture.enabled) return;
        try {
            if (builder == null) throw new IllegalArgumentException("null builder");
            int count = builder.getVertexCount();
            int mode = builder.getDrawMode();
            VertexFormat format = builder.getVertexFormat();
            if (count <= 0 || count > 1_048_576 || mode != GL11.GL_QUADS
                || format == null || format.getSize() <= 0 || format.getSize() > 256) {
                throw new IllegalArgumentException("unsupported model quad");
            }
            int bytes = Math.multiplyExact(count, format.getSize());
            if (bytes <= 0 || bytes > MAX_MESH_BYTES) {
                throw new IllegalArgumentException("model quad byte count");
            }
            if (capture.format == null) {
                capture.format = new VertexFormat(format);
                capture.drawMode = mode;
            } else if (capture.drawMode != mode || !capture.format.equals(format)) {
                throw new IllegalArgumentException("mixed model vertex format");
            }
            ByteBuffer source = builder.getByteBuffer().duplicate();
            source.clear();
            if (source.capacity() < bytes) throw new IllegalArgumentException("short model buffer");
            source.limit(bytes);
            capture.append(source, count);
            capturedQuads = safeAdd(capturedQuads, 1L);
        } catch (Throwable rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            capture.valid = false;
            rejectedCaptures = safeAdd(rejectedCaptures, 1L);
        }
    }

    /** Called only after vanilla successfully ended the display list. */
    public static void finish(Object owner) {
        Capture capture = pop(owner);
        if (capture == null) return;
        int displayList = displayList(owner);
        if (!capture.enabled || !capture.valid || capture.size <= 0
            || capture.vertexCount <= 0 || capture.format == null
            || displayList <= 0) {
            invalidate(displayList);
            return;
        }
        try {
            PendingModelMesh pending = PendingModelMesh.capture(displayList,
                capture.toByteArray(), capture.format, capture.drawMode,
                capture.vertexCount);
            completedCaptures = safeAdd(completedCaptures, 1L);
            // Uploading here would charge VBO construction to whichever
            // entity/TESR ABBA arm happened to compile the display list.
            // Frame-boundary draining keeps measurement and draw hot paths
            // clean while the just-compiled vanilla list remains authoritative.
            deferredPublications = safeAdd(deferredPublications, 1L);
            putPending(pending);
        } catch (Throwable rejected) {
            FatalErrors.rethrowIfFatal(rejected);
            rejectedCaptures = safeAdd(rejectedCaptures, 1L);
            invalidate(displayList);
        }
    }

    /** Exception path for the wrapped vanilla compiler. */
    public static void cancel(Object owner) {
        pop(owner);
        invalidate(displayList(owner));
    }

    /** Replacement for ModelRenderer's exact GlStateManager.callList sites. */
    public static void callList(int displayList) {
        if (displayList <= 0) {
            GlStateManager.callList(displayList);
            return;
        }
        ModernRendererRuntime runtime =
            ClientOptimizerRuntime.INSTANCE.modernRenderer();
        PendingModelMesh pending = pending(displayList);
        if (pending != null && runtime != null
            && runtime.shouldDropDeferredModelMeshes()) {
            removePending(displayList);
            disabledPendingDrops = safeAdd(disabledPendingDrops, 1L);
            pending = null;
        }
        if (pending != null) {
            // A pending payload is newer than any already-cached VBO for this
            // display-list name.  Never upload inside an entity timing scope
            // and never draw the stale cache entry; the current vanilla list
            // remains the exact fallback until the next bounded frame drain.
            GlStateManager.callList(displayList);
            return;
        }
        if (runtime != null
            && RenderLibRenderBridge.candidateInspectionEnabled()
            && runtime.tryDrawModelMesh(displayList)) return;
        GlStateManager.callList(displayList);
    }

    public static boolean hasPendingModelMeshes() {
        return hasPendingMeshes;
    }

    /**
     * Bounded frame-boundary admission.  Returns the number of VBOs published
     * and performs no work when the renderer/frame is unavailable.
     */
    public static synchronized int drainPendingModelMeshes(int maximumMeshes,
                                                           long maximumBytes) {
        if (!hasPendingMeshes || maximumMeshes <= 0 || maximumBytes <= 0L) {
            return 0;
        }
        ModernRendererRuntime runtime =
            ClientOptimizerRuntime.INSTANCE.modernRenderer();
        if (runtime == null || runtime.modelMeshAdmissionEpoch() < 0L) return 0;
        purgeStalePending();
        publicationDrainCycles = safeAdd(publicationDrainCycles, 1L);
        if (runtime.shouldDropDeferredModelMeshes()) {
            disabledPendingDrops = safeAdd(disabledPendingDrops,
                PENDING.size());
            PENDING.clear();
            pendingBytes = 0L;
            hasPendingMeshes = false;
            return 0;
        }

        int attempted = 0;
        int published = 0;
        long attemptedBytes = 0L;
        long epoch = runtime.modelMeshAdmissionEpoch();
        Iterator<Map.Entry<Integer, PendingModelMesh>> iterator =
            PENDING.entrySet().iterator();
        while (iterator.hasNext() && attempted < maximumMeshes) {
            PendingModelMesh pending = iterator.next().getValue();
            publicationDrainExamined = safeAdd(publicationDrainExamined, 1L);
            ModelMeshPayload payload = pending.qualify();
            if (payload == null) {
                if (pending.isPermanentlyRejected()) {
                    pendingBytes -= pending.getByteLength();
                    stalePendingDrops = safeAdd(stalePendingDrops, 1L);
                    iterator.remove();
                }
                continue;
            }
            if (!pending.shouldAttemptPublication(epoch)) {
                publicationRetrySuppressions = safeAdd(
                    publicationRetrySuppressions, 1L);
                continue;
            }
            int bytes = pending.getByteLength();
            if (attempted > 0 && bytes > maximumBytes - attemptedBytes) break;
            attempted++;
            attemptedBytes = safeAdd(attemptedBytes, bytes);
            if (!attemptPublication(pending, payload, runtime)) continue;
            if (pending.consumeQualifiedLate()) {
                lateQualifications = safeAdd(lateQualifications, 1L);
            }
            pendingBytes -= bytes;
            iterator.remove();
            published++;
            drainedPublications = safeAdd(drainedPublications, 1L);
        }
        if (PENDING.isEmpty()) hasPendingMeshes = false;
        return published;
    }

    private static boolean attemptPublication(PendingModelMesh pending,
                                              ModelMeshPayload payload,
                                              ModernRendererRuntime runtime) {
        if (pending == null || payload == null || runtime == null) return false;
        long epoch;
        try { epoch = runtime.modelMeshAdmissionEpoch(); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
        if (epoch < 0L) return false;
        if (!pending.shouldAttemptPublication(epoch)) {
            publicationRetrySuppressions = safeAdd(
                publicationRetrySuppressions, 1L);
            return false;
        }
        publicationAttempts = safeAdd(publicationAttempts, 1L);
        boolean accepted = publish(payload, runtime);
        if (!accepted) {
            publicationFailures = safeAdd(publicationFailures, 1L);
            pending.recordPublicationFailure(epoch);
        }
        return accepted;
    }

    private static boolean publish(ModelMeshPayload payload,
                                   ModernRendererRuntime runtime) {
        try {
            return runtime != null && runtime.isModelMeshBackendOperational()
                && runtime.acceptModelMesh(payload);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private static Capture pop(Object owner) {
        CaptureState state = CAPTURES.get();
        if (state.overflow > 0) {
            state.overflow--;
            if (state.overflow == 0 && state.stack.isEmpty()) CAPTURES.remove();
            return null;
        }
        Capture capture = state.stack.poll();
        if (state.stack.isEmpty()) CAPTURES.remove();
        if (capture == null || capture.owner != owner) {
            state.stack.clear();
            state.overflow = 0;
            CAPTURES.remove();
            return null;
        }
        return capture;
    }

    static int captureDepthForTest() { return CAPTURES.get().stack.size(); }
    static int captureOverflowForTest() { return CAPTURES.get().overflow; }

    /** Drops resource-qualified payloads immediately at a reload boundary. */
    public static synchronized void reset() {
        CAPTURES.remove();
        PENDING.clear();
        hasPendingMeshes = false;
        pendingBytes = 0L;
        pendingPurgeInitialized = false;
        lastPurgedResourceGeneration = 0L;
        lastPurgedContextGeneration = 0L;
        lastPurgedContextIdentity = null;
    }

    public static synchronized Diagnostics diagnostics() {
        return new Diagnostics(captureBegins, enabledCaptures,
            earlyCaptures, disabledCaptures, capturedQuads, rejectedCaptures,
            completedCaptures, immediatePublications, deferredPublications,
            lateQualifications, stalePendingDrops, disabledPendingDrops,
            pendingPurgeScans, publicationAttempts, publicationFailures,
            publicationRetrySuppressions, publicationDrainCycles,
            publicationDrainExamined, drainedPublications, PENDING.size(),
            pendingBytes);
    }

    static void resetForTest() { reset(); }

    private static int displayList(Object owner) {
        if (owner == null) return 0;
        try {
            Class<?> type = owner.getClass();
            Field field = DISPLAY_LIST_FIELDS.get(type);
            if (field == null) {
                Class<?> current = type;
                while (current != null) {
                    try {
                        field = current.getDeclaredField("field_78811_r");
                        break;
                    } catch (NoSuchFieldException first) {
                        try {
                            field = current.getDeclaredField("displayList");
                            break;
                        } catch (NoSuchFieldException ignored) {
                            current = current.getSuperclass();
                        }
                    }
                }
                if (field == null) return 0;
                field.setAccessible(true);
                DISPLAY_LIST_FIELDS.put(type, field);
            }
            return field.getInt(owner);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return 0;
        }
    }

    private static synchronized void putPending(PendingModelMesh payload) {
        purgeStalePending();
        // Publish the positive gate before the map mutation.  A concurrent
        // lookup that observes it must then acquire this monitor and can only
        // see the completed insertion.
        hasPendingMeshes = true;
        PendingModelMesh old = PENDING.put(
            Integer.valueOf(payload.getDisplayList()), payload);
        if (old != null) pendingBytes -= old.getByteLength();
        pendingBytes = safeAdd(pendingBytes, payload.getByteLength());
        Iterator<Map.Entry<Integer, PendingModelMesh>> iterator =
            PENDING.entrySet().iterator();
        while ((PENDING.size() > MAX_PENDING_MESHES || pendingBytes > MAX_PENDING_BYTES)
            && iterator.hasNext()) {
            PendingModelMesh evicted = iterator.next().getValue();
            pendingBytes -= evicted.getByteLength();
            stalePendingDrops = safeAdd(stalePendingDrops, 1L);
            iterator.remove();
        }
        if (PENDING.isEmpty()) hasPendingMeshes = false;
    }

    private static PendingModelMesh pending(int displayList) {
        // Once all captures have been admitted, ordinary model draws avoid
        // both monitor acquisition and Integer/map lookup entirely.
        if (!hasPendingMeshes) return null;
        synchronized (ModelMeshCaptureBridge.class) {
            purgeStalePending();
            PendingModelMesh value = PENDING.get(Integer.valueOf(displayList));
            if (PENDING.isEmpty()) hasPendingMeshes = false;
            return value;
        }
    }

    private static synchronized void removePending(int displayList) {
        PendingModelMesh removed = PENDING.remove(Integer.valueOf(displayList));
        if (removed != null) pendingBytes -= removed.getByteLength();
        if (PENDING.isEmpty()) hasPendingMeshes = false;
    }

    private static void invalidate(int displayList) {
        if (displayList <= 0) return;
        removePending(displayList);
        try {
            ModernRendererRuntime runtime = ClientOptimizerRuntime.INSTANCE.modernRenderer();
            if (runtime != null) runtime.invalidateModelMesh(displayList);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
    }

    private static void purgeStalePending() {
        long resources = OptimizerBridge.currentResourceGeneration();
        long context = OptimizerBridge.currentGlContextGeneration();
        boolean generationsStable = pendingPurgeInitialized
            && resources == lastPurgedResourceGeneration
            && context == lastPurgedContextGeneration;
        // Positive lifecycle generations are authoritative in steady state,
        // so the hot lookup returns before touching LWJGL.  At startup or a
        // generation transition, capture the raw identity once so pre-epoch
        // payloads can still be discarded safely.
        if (generationsStable && resources > 0L && context > 0L) return;
        ContextCapabilities contextIdentity = currentContextIdentity();
        if (generationsStable
            && contextIdentity == lastPurgedContextIdentity) return;
        pendingPurgeInitialized = true;
        lastPurgedResourceGeneration = resources;
        lastPurgedContextGeneration = context;
        lastPurgedContextIdentity = contextIdentity;
        pendingPurgeScans = safeAdd(pendingPurgeScans, 1L);
        Iterator<PendingModelMesh> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            PendingModelMesh value = iterator.next();
            if (!value.isStale(resources, context, contextIdentity)) continue;
            pendingBytes -= value.getByteLength();
            stalePendingDrops = safeAdd(stalePendingDrops, 1L);
            iterator.remove();
        }
        if (PENDING.isEmpty()) hasPendingMeshes = false;
    }

    private static ContextCapabilities currentContextIdentity() {
        try { return GLContext.getCapabilities(); }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return null;
        }
    }

    static long publicationRetryDelay(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0L;
        int shift = Math.min(MAX_PUBLICATION_BACKOFF_SHIFT,
            consecutiveFailures - 1);
        return 1L << shift;
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static boolean earlyCaptureAvailable() {
        if (OptimizerBridge.currentResourceGeneration() > 0L
            || OptimizerBridge.currentGlContextGeneration() > 0L) return false;
        try { return GLContext.getCapabilities() != null; }
        catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    public static final class Diagnostics {
        private final long captureBegins;
        private final long enabledCaptures;
        private final long earlyCaptures;
        private final long disabledCaptures;
        private final long capturedQuads;
        private final long rejectedCaptures;
        private final long completedCaptures;
        private final long immediatePublications;
        private final long deferredPublications;
        private final long lateQualifications;
        private final long stalePendingDrops;
        private final long disabledPendingDrops;
        private final long pendingPurgeScans;
        private final long publicationAttempts;
        private final long publicationFailures;
        private final long publicationRetrySuppressions;
        private final long publicationDrainCycles;
        private final long publicationDrainExamined;
        private final long drainedPublications;
        private final int pendingMeshes;
        private final long pendingBytes;

        private Diagnostics(long captureBegins, long enabledCaptures,
                            long earlyCaptures, long disabledCaptures,
                            long capturedQuads,
                            long rejectedCaptures, long completedCaptures,
                            long immediatePublications,
                            long deferredPublications,
                            long lateQualifications,
                            long stalePendingDrops,
                            long disabledPendingDrops,
                            long pendingPurgeScans,
                            long publicationAttempts,
                            long publicationFailures,
                            long publicationRetrySuppressions,
                            long publicationDrainCycles,
                            long publicationDrainExamined,
                            long drainedPublications,
                            int pendingMeshes,
                            long pendingBytes) {
            this.captureBegins = captureBegins;
            this.enabledCaptures = enabledCaptures;
            this.earlyCaptures = earlyCaptures;
            this.disabledCaptures = disabledCaptures;
            this.capturedQuads = capturedQuads;
            this.rejectedCaptures = rejectedCaptures;
            this.completedCaptures = completedCaptures;
            this.immediatePublications = immediatePublications;
            this.deferredPublications = deferredPublications;
            this.lateQualifications = lateQualifications;
            this.stalePendingDrops = stalePendingDrops;
            this.disabledPendingDrops = disabledPendingDrops;
            this.pendingPurgeScans = pendingPurgeScans;
            this.publicationAttempts = publicationAttempts;
            this.publicationFailures = publicationFailures;
            this.publicationRetrySuppressions =
                publicationRetrySuppressions;
            this.publicationDrainCycles = publicationDrainCycles;
            this.publicationDrainExamined = publicationDrainExamined;
            this.drainedPublications = drainedPublications;
            this.pendingMeshes = pendingMeshes;
            this.pendingBytes = pendingBytes;
        }

        public long getCaptureBegins() { return captureBegins; }
        public long getEnabledCaptures() { return enabledCaptures; }
        public long getEarlyCaptures() { return earlyCaptures; }
        public long getDisabledCaptures() { return disabledCaptures; }
        public long getCapturedQuads() { return capturedQuads; }
        public long getRejectedCaptures() { return rejectedCaptures; }
        public long getCompletedCaptures() { return completedCaptures; }
        public long getImmediatePublications() {
            return immediatePublications;
        }
        public long getDeferredPublications() { return deferredPublications; }
        public long getLateQualifications() { return lateQualifications; }
        public long getStalePendingDrops() { return stalePendingDrops; }
        public long getDisabledPendingDrops() { return disabledPendingDrops; }
        public long getPendingPurgeScans() { return pendingPurgeScans; }
        public long getPublicationAttempts() { return publicationAttempts; }
        public long getPublicationFailures() { return publicationFailures; }
        public long getPublicationRetrySuppressions() {
            return publicationRetrySuppressions;
        }
        public long getPublicationDrainCycles() {
            return publicationDrainCycles;
        }
        public long getPublicationDrainExamined() {
            return publicationDrainExamined;
        }
        public long getDrainedPublications() { return drainedPublications; }
        public int getPendingMeshes() { return pendingMeshes; }
        public long getPendingBytes() { return pendingBytes; }
    }

    private static final class PendingModelMesh {
        private final int displayList;
        private final byte[] vertices;
        private final VertexFormat format;
        private final int drawMode;
        private final int vertexCount;
        private final ContextCapabilities capturedContext;
        private ModelMeshPayload payload;
        private boolean qualifiedLate;
        private boolean permanentlyRejected;
        private int publicationFailureStreak;
        private long nextPublicationEpoch = Long.MIN_VALUE;

        private PendingModelMesh(int displayList, byte[] vertices,
                                 VertexFormat format, int drawMode,
                                 int vertexCount,
                                 ContextCapabilities capturedContext,
                                 ModelMeshPayload payload) {
            this.displayList = displayList;
            this.vertices = vertices;
            this.format = format;
            this.drawMode = drawMode;
            this.vertexCount = vertexCount;
            this.capturedContext = capturedContext;
            this.payload = payload;
        }

        private static PendingModelMesh capture(int displayList,
                                                byte[] vertices,
                                                VertexFormat format,
                                                int drawMode,
                                                int vertexCount) {
            if (displayList <= 0 || vertices == null || vertices.length == 0
                || format == null || vertexCount <= 0) {
                throw new IllegalArgumentException("pending model mesh");
            }
            long resources = OptimizerBridge.currentResourceGeneration();
            long context = OptimizerBridge.currentGlContextGeneration();
            if (resources > 0L && context > 0L) {
                ModelMeshPayload payload = new ModelMeshPayload(displayList,
                    vertices, format, drawMode, vertexCount, resources,
                    context);
                return new PendingModelMesh(displayList, null, null, drawMode,
                    vertexCount, null, payload);
            }
            ContextCapabilities contextIdentity = GLContext.getCapabilities();
            byte[] copied = vertices.clone();
            return new PendingModelMesh(displayList, copied,
                new VertexFormat(format), drawMode, vertexCount,
                contextIdentity, null);
        }

        private synchronized ModelMeshPayload qualify() {
            if (payload != null || permanentlyRejected) return payload;
            long resources = OptimizerBridge.currentResourceGeneration();
            long context = OptimizerBridge.currentGlContextGeneration();
            if (resources <= 0L || context <= 0L) return null;
            ContextCapabilities current;
            try { current = GLContext.getCapabilities(); }
            catch (Throwable unavailable) {
                FatalErrors.rethrowIfFatal(unavailable);
                return null;
            }
            if (capturedContext == null || current != capturedContext) {
                permanentlyRejected = true;
                return null;
            }
            boolean live;
            try { live = GL11.glIsList(displayList); }
            catch (Throwable queryFailure) {
                FatalErrors.rethrowIfFatal(queryFailure);
                return null;
            }
            if (!live) {
                permanentlyRejected = true;
                return null;
            }
            payload = new ModelMeshPayload(displayList, vertices, format,
                drawMode, vertexCount, resources, context);
            qualifiedLate = true;
            return payload;
        }

        private synchronized boolean isStale(long resources, long context,
                                             ContextCapabilities current) {
            if (permanentlyRejected) return true;
            if (payload != null) {
                return resources > 0L && context > 0L
                    && (payload.getResourceGeneration() != resources
                        || payload.getContextGeneration() != context);
            }
            return capturedContext == null
                || current != null && current != capturedContext;
        }

        private synchronized boolean shouldAttemptPublication(long epoch) {
            return epoch >= 0L && (nextPublicationEpoch == Long.MIN_VALUE
                || epoch >= nextPublicationEpoch);
        }

        private synchronized void recordPublicationFailure(long epoch) {
            if (epoch < 0L) return;
            if (publicationFailureStreak < Integer.MAX_VALUE) {
                publicationFailureStreak++;
            }
            long delay = publicationRetryDelay(publicationFailureStreak);
            nextPublicationEpoch = epoch > Long.MAX_VALUE - delay
                ? Long.MAX_VALUE : epoch + delay;
        }

        private synchronized boolean consumeQualifiedLate() {
            boolean result = qualifiedLate;
            qualifiedLate = false;
            return result;
        }

        private synchronized boolean isPermanentlyRejected() {
            return permanentlyRejected;
        }

        private int getDisplayList() { return displayList; }
        private int getByteLength() {
            return payload == null ? vertices.length : payload.getByteLength();
        }
    }

    private static final class CaptureState {
        private final ArrayDeque<Capture> stack = new ArrayDeque<Capture>();
        private int overflow;
    }

    private static final class Capture {
        private final Object owner;
        private final boolean enabled;
        private byte[] bytes = new byte[1024];
        private int size;
        private int vertexCount;
        private int drawMode;
        private VertexFormat format;
        private boolean valid = true;

        private Capture(Object owner, boolean enabled) {
            this.owner = owner;
            this.enabled = enabled;
        }

        private void append(ByteBuffer source, int vertices) {
            int incoming = source.remaining();
            int required = Math.addExact(size, incoming);
            if (required > MAX_MESH_BYTES) throw new IllegalArgumentException("model mesh too large");
            if (required > bytes.length) {
                int next = bytes.length;
                while (next < required) next = Math.min(MAX_MESH_BYTES,
                    Math.multiplyExact(next, 2));
                byte[] grown = new byte[next];
                System.arraycopy(bytes, 0, grown, 0, size);
                bytes = grown;
            }
            source.get(bytes, size, incoming);
            size = required;
            vertexCount = Math.addExact(vertexCount, vertices);
        }

        private byte[] toByteArray() {
            byte[] result = new byte[size];
            System.arraycopy(bytes, 0, result, 0, size);
            return result;
        }
    }
}

package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.terrain.ModernTerrainBridge;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainUploadContext;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.lwjgl.opengl.ARBCopyBuffer;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Render-thread-only CPU-to-GPU staging ring for chunk VBO uploads. It never
 * waits for a busy slot: unsupported or saturated drivers execute vanilla's
 * original glBufferData path in the same call.
 */
public final class ChunkVboUploadBridge {
    private static final int MODULE = OptimizationModule.VANILLA_CHUNK_VBO_UPLOAD.ordinal();
    private static final int ARRAY_BUFFER = 34962;
    private static final int COPY_WRITE_BUFFER = 36663;
    private static final int COPY_WRITE_BUFFER_BINDING = 36663;
    private static final int STREAM_DRAW = 35040;
    private static final int STATIC_DRAW = 35044;
    private static final int MIN_STAGING_BYTES = 256 * 1024;
    private static final int MAX_STAGING_BYTES = 16 * 1024 * 1024;
    private static final long NATIVE_BUFFER_OBJECT_CHARGE = 4L * 1024L;
    private static final int SLOT_COUNT = 6;
    private static final int MAX_SLOT_PROBES = 2;
    private static final UploadSlot[] SLOTS = new UploadSlot[SLOT_COUNT];
    private static final AtomicLong GPU_UPLOADS = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static final BufferDriver LWJGL_BUFFER_DRIVER =
        new BufferDriver() {
            @Override public int create() { return GL15.glGenBuffers(); }
            @Override public void allocate(int bytes) {
                GL15.glBufferData(ARRAY_BUFFER, (long) bytes, STREAM_DRAW);
            }
            @Override public void delete(int bufferId) {
                GL15.glDeleteBuffers(bufferId);
            }
        };
    private static int slotCursor;
    private static long knownResourceGeneration = Long.MIN_VALUE;
    private static ContextCapabilities knownCapabilities;
    private static ContextCapabilities unsupportedCapabilities;
    private static volatile String backend = "UNSEEN";

    private ChunkVboUploadBridge() {
    }

    public static boolean tryUpload(BufferBuilder builder, VertexBuffer vertexBuffer) {
        TerrainUploadContext.Value terrainContext = TerrainUploadContext.take();
        if (terrainContext != null
            && ModernTerrainBridge.tryUpload(terrainContext, builder, vertexBuffer)) {
            return true;
        }
        // A VertexBuffer has one owner per layer. Before any old upload can
        // publish new bytes, retire a previous arena-owned mesh for this VBO.
        ModernTerrainBridge.beforeLegacyUpload(vertexBuffer);
        if (!OptimizerBridge.isEnabled(MODULE)) return false;
        if (builder == null) {
            return fallback("GUARD");
        }
        if (!(vertexBuffer instanceof ChunkVertexBufferAccessor)) {
            backend = "ABI-MISSING";
            OptimizerBridge.incompatible(MODULE,
                "VertexBuffer 访问器未完整安装，已永久回退原上传");
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || !minecraft.isCallingFromMinecraftThread()) return fallback("THREAD");
        ByteBuffer data = builder.getByteBuffer();
        ChunkVertexBufferAccessor accessor = (ChunkVertexBufferAccessor) vertexBuffer;
        int stride = accessor.ice$vertexStrideBytes();
        int bytes = data == null ? -1 : data.limit();
        if (data == null || data.position() != 0 || bytes <= 0 || bytes > MAX_STAGING_BYTES
            || stride <= 0 || bytes % stride != 0 || accessor.ice$glBufferId() < 0) {
            return fallback("GUARD");
        }
        // Staging adds one CPU upload, one GPU copy and one Fence. That fixed
        // cost is larger than vanilla for small chunk layers on every driver,
        // so keep those calls on the original glBufferData path.
        if (!shouldStage(bytes)) return fallback("SMALL");
        int previousCopyWrite = 0;
        boolean copyBindingTouched = false;
        UploadSlot slot = null;
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            ensureContext(capabilities);
            if (unsupportedCapabilities == capabilities) return false;
            if (!supported(capabilities)) {
                backend = "UNSUPPORTED";
                unsupportedCapabilities = capabilities;
                FALLBACKS.incrementAndGet();
                return false;
            }
            String activeBackend = copyBackend(capabilities);
            slot = acquireSlot(capabilities);
            if (slot == null) {
                backend = activeBackend + "/BUSY";
                return fallback(null);
            }
            previousCopyWrite = GL11.glGetInteger(COPY_WRITE_BUFFER_BINDING);
            GL15.glBindBuffer(ARRAY_BUFFER, slot.bufferId);
            if (!slot.ensureCapacity(bytes)) {
                GL15.glBindBuffer(ARRAY_BUFFER, 0);
                return fallback("BUDGET");
            }
            GL15.glBufferSubData(ARRAY_BUFFER, 0L, data);

            // Publish recovery intent before the native call.  A throwing
            // bind may have changed driver state; the Legacy upload is only
            // safe after the previous COPY_WRITE binding is restored.
            copyBindingTouched = true;
            GL15.glBindBuffer(COPY_WRITE_BUFFER, accessor.ice$glBufferId());
            if (accessor.ice$capacityBytes() < bytes) {
                GL15.glBufferData(COPY_WRITE_BUFFER, (long) bytes, STATIC_DRAW);
                accessor.ice$setCapacityBytes(bytes);
            }
            copyBufferSubData(capabilities, bytes);
            slot.markSubmitted(capabilities);
            GL15.glBindBuffer(ARRAY_BUFFER, 0);
            GL15.glBindBuffer(COPY_WRITE_BUFFER, previousCopyWrite);
            copyBindingTouched = false;

            accessor.ice$setVertexCount(bytes / stride);
            builder.reset();
            GPU_UPLOADS.incrementAndGet();
            backend = activeBackend;
            if (ACTIVATED.compareAndSet(false, true)) {
                OptimizerBridge.activate(MODULE,
                    "区块 VBO 已使用 " + activeBackend + " 有界 Fence staging");
            }
            OptimizerBridge.success(MODULE);
            return true;
        } catch (Throwable error) {
            Throwable failure = error;
            boolean bindingsRestored = true;
            try { if (slot != null) slot.poison(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            try { accessor.ice$setCapacityBytes(0); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            try { GL15.glBindBuffer(ARRAY_BUFFER, 0); }
            catch (Throwable cleanupFailure) {
                bindingsRestored = false;
                failure = appendFailure(failure, cleanupFailure);
            }
            if (copyBindingTouched) {
                try { GL15.glBindBuffer(COPY_WRITE_BUFFER, previousCopyWrite); }
                catch (Throwable cleanupFailure) {
                    bindingsRestored = false;
                    failure = appendFailure(failure, cleanupFailure);
                }
            }
            backend = "ERROR";
            OptimizerBridge.failure(MODULE, failure);
            // The original upload is byte-idempotent, but it cannot be entered
            // with unknown ARRAY/COPY_WRITE bindings after failed cleanup.
            if (!bindingsRestored) rethrow(failure);
            return fallback(null);
        }
    }

    static long gpuUploads() { return GPU_UPLOADS.get(); }
    static long fallbacks() { return FALLBACKS.get(); }
    static String backend() { return backend; }

    static int roundedCapacityForTest(int requiredBytes) {
        return roundedCapacity(requiredBytes);
    }

    static boolean shouldStageForTest(int bytes) {
        return shouldStage(bytes);
    }

    static int maximumSlotProbesForTest() {
        return MAX_SLOT_PROBES;
    }

    private static boolean fallback(String reason) {
        FALLBACKS.incrementAndGet();
        if (reason != null && !"DISABLED".equals(reason)) backend = reason;
        return false;
    }

    private static boolean shouldStage(int bytes) {
        return bytes >= MIN_STAGING_BYTES && bytes <= MAX_STAGING_BYTES;
    }

    private static boolean supported(ContextCapabilities capabilities) {
        return capabilities != null
            && (capabilities.OpenGL31 || capabilities.GL_ARB_copy_buffer)
            && (capabilities.OpenGL32 || capabilities.GL_ARB_sync);
    }

    static String backendForTest(boolean openGl31, boolean arbCopyBuffer,
                                 boolean openGl32, boolean arbSync) {
        if (!(openGl31 || arbCopyBuffer) || !(openGl32 || arbSync)) return "UNSUPPORTED";
        return openGl31 ? "GL31-COPY" : "ARB-COPY";
    }

    private static String copyBackend(ContextCapabilities capabilities) {
        return capabilities.OpenGL31 ? "GL31-COPY" : "ARB-COPY";
    }

    private static void copyBufferSubData(ContextCapabilities capabilities, int bytes) {
        if (capabilities.OpenGL31) {
            GL31.glCopyBufferSubData(ARRAY_BUFFER, COPY_WRITE_BUFFER, 0L, 0L, bytes);
        } else {
            ARBCopyBuffer.glCopyBufferSubData(ARRAY_BUFFER, COPY_WRITE_BUFFER, 0L, 0L, bytes);
        }
    }

    private static UploadSlot acquireSlot(ContextCapabilities capabilities) {
        // A zero-timeout glClientWaitSync still crosses into the driver. Probe
        // only a bounded subset and immediately use vanilla when the GPU lags.
        for (int checked = 0; checked < Math.min(MAX_SLOT_PROBES, SLOT_COUNT); checked++) {
            int index = (slotCursor + checked) % SLOT_COUNT;
            UploadSlot slot = SLOTS[index];
            if (slot == null) {
                slot = new UploadSlot();
                SLOTS[index] = slot;
            }
            if (slot.isReady(capabilities)) {
                slotCursor = (index + 1) % SLOT_COUNT;
                return slot;
            }
        }
        return null;
    }

    private static void ensureContext(ContextCapabilities capabilities) {
        long generation = OptimizerBridge.currentResourceGeneration();
        if (knownCapabilities == capabilities && knownResourceGeneration == generation) return;
        boolean sameContext = knownCapabilities == capabilities && capabilities != null;
        Throwable failure = null;
        for (int i = 0; i < SLOTS.length; i++) {
            UploadSlot slot = SLOTS[i];
            if (slot != null) {
                try {
                    if (sameContext) slot.destroy();
                    else slot.abandon();
                } catch (Throwable error) {
                    failure = appendFailure(failure, error);
                }
            }
            SLOTS[i] = sameContext && slot != null && !slot.isReleased()
                ? slot : null;
        }
        knownCapabilities = capabilities;
        unsupportedCapabilities = null;
        knownResourceGeneration = generation;
        slotCursor = firstAvailableSlot();
        if (failure != null) rethrow(failure);
    }

    /** Releases all accounting after the previously observed Context is gone. */
    public static void contextLost() {
        for (int index = 0; index < SLOTS.length; index++) {
            UploadSlot slot = SLOTS[index];
            if (slot != null) slot.abandon();
            SLOTS[index] = null;
        }
        knownCapabilities = null;
        unsupportedCapabilities = null;
        knownResourceGeneration = Long.MIN_VALUE;
        slotCursor = 0;
    }

    /** Deletes live staging names at a valid client render-thread boundary. */
    public static void shutdown() {
        boolean contextValid = false;
        try {
            contextValid = knownCapabilities != null
                && GLContext.getCapabilities() == knownCapabilities;
        } catch (Throwable unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
        }
        Throwable failure = null;
        boolean retained = false;
        for (int index = 0; index < SLOTS.length; index++) {
            UploadSlot slot = SLOTS[index];
            if (slot == null) continue;
            if (contextValid) {
                try { slot.destroy(); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                }
                if (slot.isReleased()) SLOTS[index] = null;
                else retained = true;
            } else {
                slot.abandon();
                SLOTS[index] = null;
            }
        }
        unsupportedCapabilities = null;
        slotCursor = firstAvailableSlot();
        ACTIVATED.set(false);
        if (!retained) {
            knownCapabilities = null;
            knownResourceGeneration = Long.MIN_VALUE;
        }
        if (failure != null) OptimizerBridge.failure(MODULE, failure);
    }

    private static int firstAvailableSlot() {
        for (int index = 0; index < SLOTS.length; index++) {
            if (SLOTS[index] == null) return index;
        }
        return 0;
    }

    private static int roundedCapacity(int requiredBytes) {
        int required = Math.max(1, requiredBytes);
        int target = MIN_STAGING_BYTES;
        while (target < required && target < MAX_STAGING_BYTES) target <<= 1;
        return Math.min(MAX_STAGING_BYTES, Math.max(required, target));
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
        throw new IllegalStateException("chunk VBO staging lifecycle failed",
            failure);
    }

    interface BufferDriver {
        int create();
        void allocate(int bytes);
        void delete(int bufferId);
    }

    static final class UploadSlot {
        private final int bufferId;
        private final CacheBudget.Reservation objectReservation;
        private final CacheBudget budget;
        private final BufferDriver driver;
        private LwjglRetirementFence fence;
        private LwjglRetirementFence uncertainFence;
        private int capacityBytes;
        private CacheBudget.Reservation reservation;
        private CacheBudget.Reservation uncertainReservation;
        private boolean poisoned;
        private boolean deleteAttempted;
        private boolean bufferDeleted;

        private UploadSlot() {
            this(ClientOptimizerRuntime.INSTANCE.cacheBudget(),
                LWJGL_BUFFER_DRIVER);
        }

        UploadSlot(CacheBudget budget, BufferDriver driver) {
            if (budget == null || driver == null) {
                throw new IllegalStateException(
                    "chunk VBO staging runtime unavailable");
            }
            CacheBudget.Reservation objectCharge = budget.tryReserve(
                BudgetKind.GPU, NATIVE_BUFFER_OBJECT_CHARGE);
            if (objectCharge == null) {
                throw new IllegalStateException(
                    "chunk VBO staging object budget exhausted");
            }
            int created;
            try { created = driver.create(); }
            catch (Throwable error) {
                // No reliable name exists for one-shot cleanup. Keep the
                // token charged so retries remain globally bounded.
                throw error;
            }
            if (created <= 0) {
                objectCharge.close();
                throw new IllegalStateException(
                    "chunk VBO staging glGenBuffers failed");
            }
            bufferId = created;
            objectReservation = objectCharge;
            this.budget = budget;
            this.driver = driver;
        }

        private boolean isReady(ContextCapabilities capabilities) {
            if (poisoned) return false;
            if (fence == null) return true;
            if (fence.isSignaled()) {
                LwjglRetirementFence completed = fence;
                fence = null;
                // Clear publication before the outcome-uncertain native delete
                // so the same sync name is never retried.
                try { completed.destroy(); }
                catch (Throwable error) {
                    uncertainFence = completed;
                    poisoned = true;
                    throw error;
                }
                return true;
            }
            return false;
        }

        boolean ensureCapacity(int requiredBytes) {
            if (capacityBytes >= requiredBytes) return true;
            int target = roundedCapacity(requiredBytes);
            CacheBudget.Reservation replacement = budget.tryReserve(
                BudgetKind.GPU, target);
            if (replacement == null) return false;
            try {
                driver.allocate(target);
            } catch (Throwable error) {
                // glBufferData may have replaced the old store before LWJGL
                // reported failure. Keep both charges and poison this slot;
                // context loss or one confirmed buffer deletion releases them.
                uncertainReservation = replacement;
                poisoned = true;
                throw error;
            }
            CacheBudget.Reservation previous = reservation;
            reservation = replacement;
            capacityBytes = target;
            if (previous != null) previous.close();
            return true;
        }

        private void markSubmitted(ContextCapabilities capabilities) {
            fence = LwjglRetirementFence.tryAfterCurrentCommands(
                ClientOptimizerRuntime.INSTANCE.cacheBudget());
            if (fence == null) throw new IllegalStateException("无法创建 chunk VBO staging Fence");
        }

        private void poison() {
            poisoned = true;
        }

        void destroy() {
            Throwable failure = null;
            if (fence != null) {
                LwjglRetirementFence discarded = fence;
                fence = null;
                try { discarded.destroy(); }
                catch (Throwable error) {
                    uncertainFence = discarded;
                    poisoned = true;
                    failure = appendFailure(failure, error);
                }
            }
            if (!bufferDeleted && !deleteAttempted) {
                deleteAttempted = true;
                try {
                    driver.delete(bufferId);
                    bufferDeleted = true;
                    releaseReservations();
                } catch (Throwable error) {
                    // Never retry an outcome-uncertain raw GL name. Retain the
                    // poisoned slot until Context loss releases accounting.
                    poisoned = true;
                    failure = appendFailure(failure, error);
                }
            }
            if (failure != null) rethrow(failure);
        }

        boolean isReleased() {
            return bufferDeleted && fence == null && uncertainFence == null;
        }

        void abandon() {
            LwjglRetirementFence abandonedFence = fence;
            fence = null;
            if (abandonedFence != null) abandonedFence.abandon();
            LwjglRetirementFence uncertain = uncertainFence;
            uncertainFence = null;
            if (uncertain != null) uncertain.abandon();
            capacityBytes = 0;
            poisoned = false;
            releaseReservations();
        }

        private void releaseReservations() {
            objectReservation.close();
            if (reservation != null) reservation.close();
            reservation = null;
            if (uncertainReservation != null) uncertainReservation.close();
            uncertainReservation = null;
        }
    }
}

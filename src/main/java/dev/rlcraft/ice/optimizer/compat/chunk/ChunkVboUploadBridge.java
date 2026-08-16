package dev.rlcraft.ice.optimizer.compat.chunk;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexBuffer;
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
    private static final String MODULE = "vanilla-chunk-vbo-upload";
    private static final int ARRAY_BUFFER = 34962;
    private static final int COPY_WRITE_BUFFER = 36663;
    private static final int COPY_WRITE_BUFFER_BINDING = 36663;
    private static final int STREAM_DRAW = 35040;
    private static final int STATIC_DRAW = 35044;
    private static final int MIN_STAGING_BYTES = 256 * 1024;
    private static final int MAX_STAGING_BYTES = 16 * 1024 * 1024;
    private static final int SLOT_COUNT = 6;
    private static final UploadSlot[] SLOTS = new UploadSlot[SLOT_COUNT];
    private static final AtomicLong GPU_UPLOADS = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static int slotCursor;
    private static long knownResourceGeneration = Long.MIN_VALUE;
    private static ContextCapabilities knownCapabilities;
    private static ContextCapabilities unsupportedCapabilities;
    private static volatile String backend = "UNSEEN";

    private ChunkVboUploadBridge() {
    }

    public static boolean tryUpload(BufferBuilder builder, VertexBuffer vertexBuffer) {
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
            slot = acquireSlot(capabilities);
            if (slot == null) {
                backend = "GPU-COPY/BUSY";
                return fallback(null);
            }
            previousCopyWrite = GL11.glGetInteger(COPY_WRITE_BUFFER_BINDING);
            GL15.glBindBuffer(ARRAY_BUFFER, slot.bufferId);
            if (!slot.ensureCapacity(bytes)) {
                GL15.glBindBuffer(ARRAY_BUFFER, 0);
                return fallback("BUDGET");
            }
            GL15.glBufferSubData(ARRAY_BUFFER, 0L, data);

            GL15.glBindBuffer(COPY_WRITE_BUFFER, accessor.ice$glBufferId());
            copyBindingTouched = true;
            if (accessor.ice$capacityBytes() < bytes) {
                GL15.glBufferData(COPY_WRITE_BUFFER, (long) bytes, STATIC_DRAW);
                accessor.ice$setCapacityBytes(bytes);
            }
            GL31.glCopyBufferSubData(ARRAY_BUFFER, COPY_WRITE_BUFFER, 0L, 0L, bytes);
            slot.markSubmitted(capabilities);
            GL15.glBindBuffer(ARRAY_BUFFER, 0);
            GL15.glBindBuffer(COPY_WRITE_BUFFER, previousCopyWrite);
            copyBindingTouched = false;

            accessor.ice$setVertexCount(bytes / stride);
            builder.reset();
            GPU_UPLOADS.incrementAndGet();
            backend = "GPU-COPY";
            if (ACTIVATED.compareAndSet(false, true)) {
                OptimizerBridge.activate(MODULE,
                    "区块 VBO 已使用有界 Fence staging 与 GPU buffer copy");
            }
            OptimizerBridge.success(MODULE);
            return true;
        } catch (Throwable error) {
            if (slot != null) slot.poison();
            accessor.ice$setCapacityBytes(0);
            try { GL15.glBindBuffer(ARRAY_BUFFER, 0); } catch (Throwable ignored) { }
            if (copyBindingTouched) {
                try { GL15.glBindBuffer(COPY_WRITE_BUFFER, previousCopyWrite); }
                catch (Throwable ignored) { }
            }
            backend = "ERROR";
            OptimizerBridge.failure(MODULE, error);
            return fallback(null);
        }
    }

    static long gpuUploads() { return GPU_UPLOADS.get(); }
    static long fallbacks() { return FALLBACKS.get(); }
    static String backend() { return backend; }

    static int roundedCapacityForTest(int requiredBytes) {
        return roundedCapacity(requiredBytes);
    }

    private static boolean fallback(String reason) {
        FALLBACKS.incrementAndGet();
        if (reason != null && !"DISABLED".equals(reason)) backend = reason;
        return false;
    }

    private static boolean supported(ContextCapabilities capabilities) {
        return capabilities != null && capabilities.OpenGL31
            && (capabilities.OpenGL32 || capabilities.GL_ARB_sync);
    }

    private static UploadSlot acquireSlot(ContextCapabilities capabilities) {
        for (int checked = 0; checked < SLOT_COUNT; checked++) {
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
        for (int i = 0; i < SLOTS.length; i++) {
            UploadSlot slot = SLOTS[i];
            if (slot != null) {
                if (sameContext) slot.destroy(capabilities);
                else slot.abandon();
            }
            SLOTS[i] = null;
        }
        knownCapabilities = capabilities;
        unsupportedCapabilities = null;
        knownResourceGeneration = generation;
        slotCursor = 0;
    }

    private static int roundedCapacity(int requiredBytes) {
        int required = Math.max(1, requiredBytes);
        int target = MIN_STAGING_BYTES;
        while (target < required && target < MAX_STAGING_BYTES) target <<= 1;
        return Math.min(MAX_STAGING_BYTES, Math.max(required, target));
    }

    private static int waitResult(ContextCapabilities capabilities, GLSync fence) {
        return capabilities.OpenGL32
            ? GL32.glClientWaitSync(fence, 0, 0L)
            : ARBSync.glClientWaitSync(fence, 0, 0L);
    }

    private static GLSync fence(ContextCapabilities capabilities) {
        return capabilities.OpenGL32
            ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            : ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private static void deleteFence(ContextCapabilities capabilities, GLSync fence) {
        if (capabilities.OpenGL32) GL32.glDeleteSync(fence);
        else ARBSync.glDeleteSync(fence);
    }

    private static final class UploadSlot {
        private final int bufferId = GL15.glGenBuffers();
        private GLSync fence;
        private int capacityBytes;
        private CacheBudget.Reservation reservation;
        private boolean poisoned;

        private boolean isReady(ContextCapabilities capabilities) {
            if (poisoned) return false;
            if (fence == null) return true;
            int result = waitResult(capabilities, fence);
            if (result == ARBSync.GL_ALREADY_SIGNALED
                || result == ARBSync.GL_CONDITION_SATISFIED) {
                deleteFence(capabilities, fence);
                fence = null;
                return true;
            }
            if (result == ARBSync.GL_WAIT_FAILED) {
                poisoned = true;
                throw new IllegalStateException("chunk VBO staging Fence 状态读取失败");
            }
            return false;
        }

        private boolean ensureCapacity(int requiredBytes) {
            if (capacityBytes >= requiredBytes) return true;
            int target = roundedCapacity(requiredBytes);
            CacheBudget.Reservation replacement =
                ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU, target);
            if (replacement == null) return false;
            try {
                GL15.glBufferData(ARRAY_BUFFER, (long) target, STREAM_DRAW);
            } catch (Throwable error) {
                replacement.close();
                throw error;
            }
            CacheBudget.Reservation previous = reservation;
            reservation = replacement;
            capacityBytes = target;
            if (previous != null) previous.close();
            return true;
        }

        private void markSubmitted(ContextCapabilities capabilities) {
            fence = fence(capabilities);
            if (fence == null) throw new IllegalStateException("无法创建 chunk VBO staging Fence");
        }

        private void poison() {
            poisoned = true;
        }

        private void destroy(ContextCapabilities capabilities) {
            if (fence != null) deleteFence(capabilities, fence);
            GL15.glDeleteBuffers(bufferId);
            abandon();
        }

        private void abandon() {
            fence = null;
            capacityBytes = 0;
            poisoned = false;
            if (reservation != null) reservation.close();
            reservation = null;
        }
    }
}

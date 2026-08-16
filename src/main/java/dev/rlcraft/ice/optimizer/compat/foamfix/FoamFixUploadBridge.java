package dev.rlcraft.ice.optimizer.compat.foamfix;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/** Fail-open PBO upload path shared by FoamFix's batch helper and vanilla TextureUtil. */
public final class FoamFixUploadBridge {
    private static final String MODULE = "foamfix-texture-upload";
    private static final int MODULE_ORDINAL = OptimizationModule.FOAMFIX_TEXTURE_UPLOAD.ordinal();
    private static final int PIXEL_UNPACK_BUFFER = 35052;
    private static final int PIXEL_UNPACK_BUFFER_BINDING = 35055;
    private static final int STREAM_DRAW = 35040;
    private static final int MAX_STAGING_BYTES = 16 * 1024 * 1024;
    private static final int MIN_BATCH_PBO_BYTES = 256 * 1024;
    private static final int SLOT_COUNT = 3;
    private static final PboSlot[] SLOTS = new PboSlot[SLOT_COUNT];
    private static IntBuffer staging;
    private static CacheBudget.Reservation stagingReservation;
    private static int stagingBytes;
    private static int slotCursor;
    private static long knownContextGeneration = Long.MIN_VALUE;
    private static boolean activated;
    private static volatile boolean coreBridgeInstalled;

    private FoamFixUploadBridge() {
    }

    /**
     * Connects the regular optimizer runtime to the early CoreMod trampoline.
     * The lookup is intentionally reflective so the main JAR still starts
     * safely when the optional Core JAR is missing or incompatible.
     */
    public static synchronized boolean installCoreBridge() {
        if (coreBridgeInstalled) return true;
        try {
            ClassLoader loader = FoamFixUploadBridge.class.getClassLoader();
            Class<?> bootstrap = Class.forName(
                "dev.rlcraft.ice.hooks.TextureUploadBootstrap", true, loader);
            Object installed = bootstrap.getMethod("install", Class.class)
                .invoke(null, FoamFixUploadBridge.class);
            if (Boolean.TRUE.equals(installed)) {
                coreBridgeInstalled = true;
                return true;
            }
            OptimizerBridge.failure(MODULE,
                new IllegalStateException("Core 纹理上传桥签名不兼容"));
        } catch (ClassNotFoundException missingCore) {
            return false;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
            OptimizerBridge.failure(MODULE, cause);
        }
        return false;
    }

    /**
     * Generic TextureUtil entry used when FoamFix loaded its sprite subclass
     * before ICE's late structural transformer became available.
     */
    public static boolean tryUploadLevel(int mipLevel, int[] data, int width, int height,
                                         int originX, int originY, boolean linearFiltering,
                                         boolean clamped, boolean mipFiltering) {
        // A single TextureUtil level is not a batch. Intercepting it creates one
        // fence per tiny animated sprite and was measured as a 42-45% render-thread tax.
        return false;
    }

    public static boolean tryUpload(int maxMips, int[][] data, int width, int height, int originX, int originY,
                                    boolean linearFiltering, boolean clamped, boolean mipFiltering) {
        if (!OptimizerBridge.isEnabled(MODULE_ORDINAL) || data == null
            || width <= 0 || height <= 0) return false;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.gameSettings == null || minecraft.gameSettings.anaglyph) return false;
        int mips = maxMips >= 0 ? Math.min(maxMips, data.length - 1) : data.length - 1;
        if (mips < 0) return true;
        long totalIntsLong = 0L;
        for (int mip = 0; mip <= mips; mip++) {
            int mipWidth = width >> mip;
            int mipHeight = height >> mip;
            if (mipWidth <= 0 || mipHeight <= 0) continue;
            long count = (long) mipWidth * (long) mipHeight;
            if (data[mip] == null || count > data[mip].length) return false;
            if (count > MAX_STAGING_BYTES / 4L || totalIntsLong > MAX_STAGING_BYTES / 4L - count) return false;
            totalIntsLong += count;
        }
        int totalInts = (int) totalIntsLong;
        if (totalInts == 0) return true;
        int totalBytes = totalInts * 4;
        if (totalBytes < MIN_BATCH_PBO_BYTES) return false;
        int previousPbo = 0;
        boolean bindingChanged = false;
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            if (!supportsPbo(capabilities)) return false;
            ensureContext();
            PboSlot slot = acquirePboSlot(capabilities);
            if (slot == null) return false;
            IntBuffer pixels = preparePixels(data, width, height, mips, totalInts);
            if (pixels == null) return false;
            previousPbo = GL11.glGetInteger(PIXEL_UNPACK_BUFFER_BINDING);
            GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, slot.bufferId);
            bindingChanged = true;
            if (!slot.ensureCapacity(totalBytes)) {
                GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, previousPbo);
                bindingChanged = false;
                return false;
            }
            setTextureParameters(linearFiltering, clamped, mipFiltering);
            try {
                GL15.glBufferSubData(PIXEL_UNPACK_BUFFER, 0L, pixels);
                uploadFromPbo(width, height, originX, originY, mips);
                slot.markSubmitted(capabilities);
            } catch (Throwable error) {
                slot.poison();
                throw error;
            }
            if (bindingChanged) GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, previousPbo);
            activate();
            OptimizerBridge.success(MODULE_ORDINAL);
            return true;
        } catch (Throwable error) {
            try {
                if (bindingChanged) GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, previousPbo);
            } catch (Throwable ignored) {
            }
            OptimizerBridge.failure(MODULE_ORDINAL, error);
            return false;
        }
    }

    private static IntBuffer preparePixels(int[][] data, int width, int height, int mips, int totalInts) {
        ensureContext();
        int requiredBytes = totalInts * 4;
        if (!ensureStagingCapacity(requiredBytes)) return null;
        staging.clear();
        for (int mip = 0; mip <= mips; mip++) {
            int mipWidth = width >> mip;
            int mipHeight = height >> mip;
            if (mipWidth > 0 && mipHeight > 0) staging.put(data[mip], 0, mipWidth * mipHeight);
        }
        staging.flip();
        return staging;
    }

    private static void activate() {
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE_ORDINAL,
            "仅大批量动画纹理使用三槽 PBO；小纹理与忙槽立即回退原上传路径");
    }

    private static boolean ensureStagingCapacity(int requiredBytes) {
        if (staging != null && stagingBytes >= requiredBytes) return true;
        int targetBytes = roundedCapacity(requiredBytes);
        CacheBudget.Reservation reservation = ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.DIRECT, targetBytes);
        if (reservation == null) return false;
        IntBuffer replacement;
        try {
            replacement = ByteBuffer.allocateDirect(targetBytes).order(ByteOrder.nativeOrder()).asIntBuffer();
        } catch (Throwable error) {
            reservation.close();
            throw error;
        }
        CacheBudget.Reservation previous = stagingReservation;
        staging = replacement;
        stagingBytes = targetBytes;
        stagingReservation = reservation;
        if (previous != null) previous.close();
        return true;
    }

    private static void setTextureParameters(boolean linear, boolean clamped, boolean mipFiltering) {
        int min = linear ? (mipFiltering ? 9987 : 9729) : (mipFiltering ? 9986 : 9728);
        int mag = linear ? 9729 : 9728;
        int wrap = clamped ? 10496 : 10497;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mag);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
    }

    private static void uploadFromPbo(int width, int height, int originX, int originY, int mips) {
        long offsetBytes = 0L;
        for (int mip = 0; mip <= mips; mip++) {
            int mipWidth = width >> mip;
            int mipHeight = height >> mip;
            if (mipWidth <= 0 || mipHeight <= 0) continue;
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, mip, originX >> mip, originY >> mip,
                mipWidth, mipHeight, 32993, 33639, offsetBytes);
            offsetBytes += (long) mipWidth * (long) mipHeight * 4L;
        }
    }

    static int minimumBatchPboBytesForTest() {
        return MIN_BATCH_PBO_BYTES;
    }

    private static boolean supportsPbo(ContextCapabilities capabilities) {
        return capabilities != null && supportsPboForTest(capabilities.OpenGL15,
            capabilities.OpenGL21, capabilities.GL_ARB_pixel_buffer_object,
            capabilities.OpenGL32, capabilities.GL_ARB_sync);
    }

    static boolean supportsPboForTest(boolean openGl15, boolean openGl21,
                                      boolean arbPixelBufferObject,
                                      boolean openGl32, boolean arbSync) {
        return openGl15 && (openGl21 || arbPixelBufferObject) && (openGl32 || arbSync);
    }

    private static PboSlot acquirePboSlot(ContextCapabilities capabilities) {
        for (int checked = 0; checked < SLOT_COUNT; checked++) {
            int index = (slotCursor + checked) % SLOT_COUNT;
            PboSlot slot = SLOTS[index];
            if (slot == null) {
                slot = new PboSlot();
                SLOTS[index] = slot;
            }
            if (slot.isReady(capabilities)) {
                slotCursor = (index + 1) % SLOT_COUNT;
                return slot;
            }
        }
        return null;
    }

    private static void ensureContext() {
        long generation = OptimizerBridge.currentGlContextGeneration();
        if (knownContextGeneration == generation) return;
        knownContextGeneration = generation;
        for (int i = 0; i < SLOTS.length; i++) {
            PboSlot slot = SLOTS[i];
            if (slot != null) slot.abandon();
            SLOTS[i] = null;
        }
        slotCursor = 0;
    }

    private static int roundedCapacity(int requiredBytes) {
        int target = 64 * 1024;
        while (target < requiredBytes && target < MAX_STAGING_BYTES) target <<= 1;
        return Math.min(MAX_STAGING_BYTES, Math.max(requiredBytes, target));
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

    private static final class PboSlot {
        private final int bufferId = GL15.glGenBuffers();
        private GLSync fence;
        private int capacityBytes;
        private CacheBudget.Reservation reservation;
        private boolean poisoned;

        private boolean isReady(ContextCapabilities capabilities) {
            if (poisoned) return false;
            if (fence == null) return true;
            int result = waitResult(capabilities, fence);
            if (result == ARBSync.GL_ALREADY_SIGNALED || result == ARBSync.GL_CONDITION_SATISFIED) {
                deleteFence(capabilities, fence);
                fence = null;
                return true;
            }
            if (result == ARBSync.GL_WAIT_FAILED) {
                poisoned = true;
                throw new IllegalStateException("PBO Fence 状态读取失败");
            }
            return false;
        }

        private boolean ensureCapacity(int requiredBytes) {
            if (capacityBytes >= requiredBytes) return true;
            int targetBytes = roundedCapacity(requiredBytes);
            CacheBudget.Reservation replacement = ClientOptimizerRuntime.INSTANCE.tryReserve(BudgetKind.GPU, targetBytes);
            if (replacement == null) return false;
            try {
                GL15.glBufferData(PIXEL_UNPACK_BUFFER, (long) targetBytes, STREAM_DRAW);
            } catch (Throwable error) {
                replacement.close();
                throw error;
            }
            CacheBudget.Reservation previous = reservation;
            reservation = replacement;
            capacityBytes = targetBytes;
            if (previous != null) previous.close();
            return true;
        }

        private void markSubmitted(ContextCapabilities capabilities) {
            fence = fence(capabilities);
            if (fence == null) throw new IllegalStateException("无法创建 PBO 同步 Fence");
        }

        private void poison() {
            poisoned = true;
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

package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * One-bind animation-atlas PBO stream with independent streaming and
 * persistent slot pools.  Every busy/error/budget path replays commands
 * through TextureUtil in their original order without waiting on a Fence.
 */
public final class LwjglAnimatedTextureUploadStream {
    private static final int PIXEL_UNPACK_BUFFER = 0x88EC;
    private static final int PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    private static final int STREAM_DRAW = 0x88E0;
    private static final int SLOT_COUNT = 3;
    private static final int MIN_PBO_BYTES = 64 * 1024;
    private static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;
    private static final int CLIENT_MAPPED_BUFFER_BARRIER_BIT = 0x00004000;

    public enum FlushResult {
        EMPTY(false, false),
        STREAMING_PBO(true, false),
        PERSISTENT_PBO(true, true),
        LEGACY(false, false),
        STALE(false, false),
        FAILED_TO_LEGACY(false, false),
        FAILED_BEFORE_UPLOAD(false, false),
        FAILED_AFTER_UPLOAD(true, false);

        private final boolean modern;
        private final boolean persistent;

        FlushResult(boolean modern, boolean persistent) {
            this.modern = modern;
            this.persistent = persistent;
        }

        public boolean usedModern() { return modern; }
        public boolean usedPersistent() { return persistent; }
    }

    interface LegacyUploader {
        void upload(int[][] data, int width, int height, int originX,
                    int originY, boolean blur, boolean clamp);
    }

    private static final LegacyUploader TEXTURE_UTIL_UPLOADER =
        new LegacyUploader() {
            @Override public void upload(int[][] data, int width, int height,
                                         int originX, int originY,
                                         boolean blur, boolean clamp) {
                TextureUtil.uploadTextureMipmap(data, width, height, originX,
                    originY, blur, clamp);
            }
        };

    private final RenderThreadGuard guard;
    private final ResourceLedger ledger;
    private final CacheBudget budget;
    private final CapabilityReport capabilities;
    private final LegacyUploader legacyUploader;
    private final AnimationTextureCommandQueue commands;
    private final Slot[] streaming = slots(false);
    private final Slot[] persistent = slots(true);
    private int streamingCursor;
    private int persistentCursor;
    private ByteBuffer stagingBytes;
    private IntBuffer stagingInts;
    private CacheBudget.Reservation stagingReservation;
    private int stagingCapacity;
    private long knownResourceGeneration = Long.MIN_VALUE;
    private long knownContextGeneration = Long.MIN_VALUE;
    private int lastCommandCount;
    private int lastMipLevels;
    private long lastBytes;
    private boolean lastFenceBusy;
    private Throwable lastError;
    private Throwable lastLegacyReplayFailure;

    public LwjglAnimatedTextureUploadStream(RenderThreadGuard guard,
                                            ResourceLedger ledger,
                                            CacheBudget budget,
                                            CapabilityReport capabilities) {
        this(guard, ledger, budget, capabilities, TEXTURE_UTIL_UPLOADER);
    }

    LwjglAnimatedTextureUploadStream(RenderThreadGuard guard,
                                     ResourceLedger ledger,
                                     CacheBudget budget,
                                     CapabilityReport capabilities,
                                     LegacyUploader legacyUploader) {
        if (guard == null || ledger == null || budget == null || capabilities == null) {
            throw new IllegalArgumentException("texture stream dependencies");
        }
        if (legacyUploader == null) {
            throw new IllegalArgumentException("legacy texture uploader");
        }
        this.guard = guard;
        this.ledger = ledger;
        this.budget = budget;
        this.capabilities = capabilities;
        this.legacyUploader = legacyUploader;
        this.commands = new AnimationTextureCommandQueue(4096,
            MAX_BATCH_BYTES, budget);
    }

    public boolean offer(int[][] data, int width, int height, int originX,
                         int originY, boolean blur, boolean clamp) {
        guard.check();
        return commands.offer(data, width, height, originX, originY, blur, clamp);
    }

    public boolean hasCommands() {
        guard.check();
        return commands.size() != 0;
    }

    public FlushResult flush(long resourceGeneration, long contextGeneration,
                             boolean requestPersistent) {
        guard.check();
        captureLast();
        lastFenceBusy = false;
        lastError = null;
        lastLegacyReplayFailure = null;
        if (commands.size() == 0) return FlushResult.EMPTY;
        if (resourceGeneration <= 0L || contextGeneration <= 0L) {
            return legacyAndClear(FlushResult.LEGACY);
        }
        if (knownContextGeneration != contextGeneration
            || knownResourceGeneration != resourceGeneration) {
            try {
                if (knownContextGeneration != Long.MIN_VALUE) {
                    if (knownContextGeneration == contextGeneration) {
                        retirePool(streaming, contextGeneration);
                        retirePool(persistent, contextGeneration);
                    } else {
                        abandonPools();
                    }
                }
                streamingCursor = 0;
                persistentCursor = 0;
                knownContextGeneration = contextGeneration;
                knownResourceGeneration = resourceGeneration;
            } catch (Throwable generationFailure) {
                lastError = generationFailure;
                clearAndRethrowFatal(generationFailure);
                return legacyAndClear(FlushResult.LEGACY);
            }
        }
        if (!capabilities.passed(ModernCapability.PIXEL_UNPACK_BUFFER)
            || !capabilities.passed(ModernCapability.SYNC_FENCE)
            || commands.getBytes() < MIN_PBO_BYTES) {
            return legacyAndClear(FlushResult.LEGACY);
        }
        int previousBinding = EarlyGlStateTracker.pixelUnpackBufferBinding();
        if (previousBinding == Integer.MIN_VALUE) {
            return legacyAndClear(FlushResult.LEGACY);
        }
        boolean usePersistent = requestPersistent
            && capabilities.passed(ModernCapability.PERSISTENT_MAPPING);
        Slot slot;
        try {
            slot = acquire(usePersistent ? persistent : streaming,
                usePersistent, contextGeneration);
        } catch (Throwable acquisitionFailure) {
            clearAndRethrowFatal(acquisitionFailure);
            rethrow(acquisitionFailure);
            return FlushResult.FAILED_BEFORE_UPLOAD;
        }
        if (slot == null) {
            lastFenceBusy = lastError == null;
            return legacyAndClear(lastError == null ? FlushResult.LEGACY
                : FlushResult.FAILED_TO_LEGACY);
        }

        boolean bindingChanged = false;
        boolean submissionStarted = false;
        try {
            bindPixelUnpackBuffer(slot.nativeId());
            bindingChanged = true;
            if (!ensureCapacity(slot, commands.getBytes(), resourceGeneration,
                contextGeneration)) {
                bindPixelUnpackBuffer(previousBinding);
                bindingChanged = false;
                return legacyAndClear(FlushResult.LEGACY);
            }
            // ensureCapacity may replace the GL name.
            bindPixelUnpackBuffer(slot.nativeId());
            writePixels(slot);
            submissionStarted = true;
            issueUploads();
            slot.fence = LwjglRetirementFence.tryAfterCurrentCommands(ledger);
            if (slot.fence == null) {
                slot.poisoned = true;
                throw new IllegalStateException("texture upload Fence creation failed");
            }
            bindPixelUnpackBuffer(previousBinding);
            bindingChanged = false;
            commands.clear();
            return usePersistent ? FlushResult.PERSISTENT_PBO
                : FlushResult.STREAMING_PBO;
        } catch (Throwable error) {
            Throwable failure = error;
            if (lastError != null && lastError != error) {
                failure = appendFailure(failure, lastError);
            }
            slot.poisoned = true;
            if (submissionStarted && slot.fence == null) {
                try {
                    slot.fence = LwjglRetirementFence.afterCurrentCommands(ledger);
                } catch (Throwable fenceFailure) {
                    failure = appendFailure(failure, fenceFailure);
                }
            }
            boolean restored = !bindingChanged;
            try {
                if (bindingChanged) {
                    bindPixelUnpackBuffer(previousBinding);
                    bindingChanged = false;
                    restored = true;
                }
            } catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
                EarlyGlStateTracker.invalidate();
            }
            lastError = failure;
            clearAndRethrowFatal(failure);
            // Replaying the whole ordered sequence is pixel-idempotent even if
            // a driver accepted a strict prefix before reporting the error.
            if (restored) {
                return legacyAndClear(FlushResult.FAILED_TO_LEGACY);
            }
            // A client-memory upload while an unpack PBO may still be bound
            // would reinterpret the Java pointer as a byte offset.  Preserve
            // the Fence on this poisoned slot for reset/ledger ownership and
            // fail this one submission without issuing a corrupt fallback.
            commands.clear();
            return submissionStarted ? FlushResult.FAILED_AFTER_UPLOAD
                : FlushResult.FAILED_BEFORE_UPLOAD;
        }
    }

    /** Retires resources only when the named context is still valid. */
    public void reset(boolean contextValid, long contextGeneration) {
        guard.check();
        Throwable failure = null;
        try { commands.clear(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (contextValid) {
            try { retirePool(streaming, contextGeneration); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            try { retirePool(persistent, contextGeneration); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        } else {
            try { abandonPools(); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        streamingCursor = 0;
        persistentCursor = 0;
        knownContextGeneration = contextValid ? contextGeneration : Long.MIN_VALUE;
        knownResourceGeneration = Long.MIN_VALUE;
        try { releaseStaging(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public void close(long contextGeneration) {
        close(true, contextGeneration);
    }

    /** Final graph disposal; unlike reset(), also releases retained Heap metadata. */
    public void close(boolean contextValid, long contextGeneration) {
        guard.check();
        Throwable failure = null;
        try { reset(contextValid, contextGeneration); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { commands.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public int getLastCommandCount() { return lastCommandCount; }
    public int getLastMipLevels() { return lastMipLevels; }
    public long getLastBytes() { return lastBytes; }
    public boolean wasLastFenceBusy() { return lastFenceBusy; }
    public Throwable getLastError() { return lastError; }
    public Throwable getLastLegacyReplayFailure() {
        return lastLegacyReplayFailure;
    }

    private Slot acquire(Slot[] pool, boolean persistentPool,
                         long contextGeneration) {
        int cursor = persistentPool ? persistentCursor : streamingCursor;
        for (int checked = 0; checked < pool.length; checked++) {
            int index = (cursor + checked) % pool.length;
            Slot slot = pool[index];
            if (slot.poisoned) continue;
            if (slot.handle != null
                && slot.handle.getContextGeneration() != contextGeneration) continue;
            if (slot.fence != null) {
                boolean ready;
                try { ready = slot.fence.isSignaled(); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    lastError = error;
                    FatalErrors.rethrowIfFatal(error);
                    return null;
                }
                if (!ready) continue;
                try {
                    destroySlotFence(slot);
                } catch (Throwable error) {
                    slot.poisoned = true;
                    lastError = error;
                    FatalErrors.rethrowIfFatal(error);
                    return null;
                }
            }
            if (persistentPool) persistentCursor = (index + 1) % pool.length;
            else streamingCursor = (index + 1) % pool.length;
            return slot;
        }
        return null;
    }

    private static void destroySlotFence(Slot slot) {
        ResourceLedger.RetirementFence fence = slot.fence;
        slot.fence = null;
        if (fence == null) return;
        try { fence.destroy(); }
        catch (Throwable error) {
            slot.uncertainFence = fence;
            slot.poisoned = true;
            throw error;
        }
    }

    private boolean ensureCapacity(Slot slot, long requiredBytes,
                                   long resourceGeneration,
                                   long contextGeneration) {
        if (requiredBytes <= slot.capacity && slot.handle != null) return true;
        int target = roundedCapacity(requiredBytes);
        if (target <= 0) return false;
        if (slot.handle != null) {
            Throwable retirementFailure = null;
            if (slot.mapped != null) {
                try {
                    if (!GL15.glUnmapBuffer(PIXEL_UNPACK_BUFFER)) {
                        retirementFailure = appendFailure(retirementFailure,
                            new IllegalStateException(
                                "persistent texture PBO unmap reported corruption"));
                    }
                } catch (Throwable error) {
                    retirementFailure = appendFailure(retirementFailure, error);
                } finally {
                    slot.mapped = null;
                }
            }
            RenderHandle retiring = slot.handle;
            try { ledger.retire(retiring, null); }
            catch (Throwable error) {
                retirementFailure = appendFailure(retirementFailure, error);
            }
            slot.handle = null;
            slot.capacity = 0;
            try { ledger.collect(contextGeneration, 8); }
            catch (Throwable error) {
                retirementFailure = appendFailure(retirementFailure, error);
            }
            if (retirementFailure != null) {
                slot.poisoned = true;
                lastError = retirementFailure;
                FatalErrors.rethrowIfFatal(retirementFailure);
                return false;
            }
        }
        CacheBudget.Reservation reservation = ledger.reserveGpu(target);
        if (reservation == null) return false;
        int created = 0;
        ByteBuffer mapped = null;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        boolean coherent = false;
        Throwable failure = null;
        try {
            created = GL15.glGenBuffers();
            allocationReturned = true;
            if (created <= 0) throw new IllegalStateException(
                "texture PBO glGenBuffers failed");
            nativeNameCreated = true;
            bindPixelUnpackBuffer(created);
            if (slot.persistent) {
                int storageFlags = GL30.GL_MAP_WRITE_BIT
                    | ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
                int mapFlags = storageFlags | GL30.GL_MAP_FLUSH_EXPLICIT_BIT;
                if (capabilities.passed(ModernCapability.COHERENT_MAPPING)) {
                    storageFlags |= ARBBufferStorage.GL_MAP_COHERENT_BIT;
                    mapFlags = storageFlags;
                    coherent = true;
                }
                ARBBufferStorage.glBufferStorage(PIXEL_UNPACK_BUFFER,
                    (long) target, storageFlags);
                mapped = GL30.glMapBufferRange(PIXEL_UNPACK_BUFFER, 0L,
                    (long) target, mapFlags, null);
                if (mapped == null) throw new IllegalStateException(
                    "persistent texture PBO map returned null");
                mapped.order(ByteOrder.nativeOrder());
            } else {
                GL15.glBufferData(PIXEL_UNPACK_BUFFER, (long) target, STREAM_DRAW);
            }
            RenderHandle handle = ledger.registerReserved(
                RenderResourceKind.BUFFER, created, target,
                resourceGeneration, contextGeneration, reservation);
            if (handle != null) {
                // From this point the ledger exclusively owns both the name
                // and the pre-allocation GPU reservation.
                created = 0;
                nativeNameCreated = false;
                reservation = null;
                slot.handle = handle;
                slot.capacity = target;
                slot.mapped = mapped;
                slot.coherent = coherent;
                return true;
            }
        } catch (Throwable error) {
            failure = error;
        }
        if (mapped != null) {
            try {
                if (!GL15.glUnmapBuffer(PIXEL_UNPACK_BUFFER)) {
                    failure = appendFailure(failure,
                        new IllegalStateException(
                            "failed texture PBO unmap reported corruption"));
                }
            } catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            mapped = null;
        }
        boolean deleteCompleted = false;
        if (nativeNameCreated) {
            try {
                GL15.glDeleteBuffers(created);
                deleteCompleted = true;
            }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        boolean noNameCreated = allocationReturned && !nativeNameCreated;
        if (reservation != null && (deleteCompleted || noNameCreated)) {
            try { reservation.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        if (failure != null) {
            slot.poisoned = true;
            lastError = failure;
            FatalErrors.rethrowIfFatal(failure);
        }
        // A clean capacity rejection is an ordinary legacy fallback; native
        // or cleanup faults poison only this slot and are surfaced above.
        return false;
    }

    private void writePixels(Slot slot) {
        int totalInts = Math.toIntExact(commands.getBytes() / 4L);
        if (slot.persistent) {
            ByteBuffer bytes = slot.mapped.duplicate().order(ByteOrder.nativeOrder());
            bytes.position(0).limit(totalInts * 4);
            IntBuffer target = bytes.slice().order(ByteOrder.nativeOrder()).asIntBuffer();
            commands.copyPixels(target);
            if (slot.coherent) {
                GL42.glMemoryBarrier(CLIENT_MAPPED_BUFFER_BARRIER_BIT);
            } else {
                GL30.glFlushMappedBufferRange(PIXEL_UNPACK_BUFFER, 0L,
                    commands.getBytes());
            }
            return;
        }
        ensureStaging(totalInts * 4);
        stagingInts.clear();
        stagingInts.limit(totalInts);
        commands.copyPixels(stagingInts);
        stagingInts.flip();
        GL15.glBufferData(PIXEL_UNPACK_BUFFER, (long) slot.capacity, STREAM_DRAW);
        GL15.glBufferSubData(PIXEL_UNPACK_BUFFER, 0L, stagingInts);
    }

    private void issueUploads() {
        long offset = 0L;
        for (int command = 0; command < commands.size(); command++) {
            int[][] levels = commands.data(command);
            boolean mipFiltering = levels.length > 1;
            for (int mip = 0; mip < levels.length; mip++) {
                int width = commands.width(command) >> mip;
                int height = commands.height(command) >> mip;
                if (width <= 0 || height <= 0) break;
                setTextureParameters(commands.blur(command),
                    commands.clamp(command), mipFiltering);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, mip,
                    commands.originX(command) >> mip,
                    commands.originY(command) >> mip, width, height,
                    32993, 33639, offset);
                offset = Math.addExact(offset,
                    Math.multiplyExact((long) width * (long) height, 4L));
            }
        }
        if (offset != commands.getBytes()) {
            throw new IllegalStateException("texture PBO layout mismatch");
        }
    }

    private static void setTextureParameters(boolean blur, boolean clamp,
                                             boolean mipFiltering) {
        int min = blur ? (mipFiltering ? 9987 : 9729)
            : (mipFiltering ? 9986 : 9728);
        int mag = blur ? 9729 : 9728;
        int wrap = clamp ? 10496 : 10497;
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D,
            GL11.GL_TEXTURE_MIN_FILTER, min);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D,
            GL11.GL_TEXTURE_MAG_FILTER, mag);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D,
            GL11.GL_TEXTURE_WRAP_S, wrap);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D,
            GL11.GL_TEXTURE_WRAP_T, wrap);
    }

    private FlushResult legacyAndClear(FlushResult result) {
        Throwable failure = null;
        try {
            for (int command = 0; command < commands.size(); command++) {
                legacyUploader.upload(commands.data(command),
                    commands.width(command), commands.height(command),
                    commands.originX(command), commands.originY(command),
                    commands.blur(command), commands.clamp(command));
            }
        } catch (Throwable error) {
            Throwable infrastructureFailure = lastError;
            if (infrastructureFailure != null
                && infrastructureFailure != error) {
                failure = appendFailure(error, infrastructureFailure);
            } else {
                failure = error;
            }
            lastLegacyReplayFailure = error;
        }
        try {
            commands.clear();
        } catch (Throwable clearFailure) {
            failure = appendFailure(failure, clearFailure);
        }
        if (failure != null) {
            lastError = failure;
            FatalErrors.rethrowIfFatal(failure);
            rethrow(failure);
        }
        return result;
    }

    private void clearAndRethrowFatal(Throwable failure) {
        if (FatalErrors.findFatal(failure) == null) return;
        Throwable combined = failure;
        try { commands.clear(); }
        catch (Throwable clearFailure) {
            combined = appendFailure(combined, clearFailure);
        }
        lastError = combined;
        FatalErrors.rethrowIfFatal(combined);
    }

    private void ensureStaging(int requiredBytes) {
        if (stagingInts != null && stagingCapacity >= requiredBytes) return;
        int target = roundedCapacity(requiredBytes);
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, target);
        if (reservation == null) throw new IllegalStateException(
            "texture PBO direct budget exhausted");
        ByteBuffer replacement;
        try {
            replacement = ByteBuffer.allocateDirect(target)
                .order(ByteOrder.nativeOrder());
        } catch (Throwable error) {
            Throwable failure = error;
            try { reservation.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            FatalErrors.rethrowIfFatal(failure);
            rethrow(failure);
            return;
        }
        CacheBudget.Reservation old = stagingReservation;
        stagingBytes = replacement;
        stagingInts = replacement.asIntBuffer();
        stagingCapacity = target;
        stagingReservation = reservation;
        if (old != null) old.close();
    }

    private void retirePool(Slot[] pool, long contextGeneration) {
        Throwable failure = null;
        int previousBinding = EarlyGlStateTracker.pixelUnpackBufferBinding();
        if (previousBinding == Integer.MIN_VALUE) try {
            // Generation transitions are cold lifecycle paths. A single
            // synchronous query is preferable to deleting mapped storage or
            // restoring an invented binding.
            previousBinding = GL11.glGetInteger(PIXEL_UNPACK_BUFFER_BINDING);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        for (Slot slot : pool) {
            Throwable slotFailure = null;
            try {
                if (slot.mapped != null && slot.handle != null) {
                    // Clear Java publication before outcome-uncertain native
                    // cleanup so this mapping is never unmapped twice.
                    slot.mapped = null;
                    bindPixelUnpackBuffer(slot.handle.getNativeId());
                    if (!GL15.glUnmapBuffer(PIXEL_UNPACK_BUFFER)) {
                        throw new IllegalStateException(
                            "persistent texture PBO unmap reported corruption");
                    }
                } else {
                    slot.mapped = null;
                }
            } catch (Throwable error) {
                slotFailure = appendFailure(slotFailure, error);
            }
            try {
                if (slot.handle != null) {
                    ResourceLedger.RetirementFence fence = slot.fence;
                    slot.fence = null;
                    ledger.retire(slot.handle, fence);
                } else if (slot.fence != null) destroySlotFence(slot);
            } catch (Throwable error) {
                slotFailure = appendFailure(slotFailure, error);
            } finally {
                slot.clear(true);
            }
            if (slotFailure != null) {
                failure = appendFailure(failure, slotFailure);
            }
        }
        if (previousBinding != Integer.MIN_VALUE) try {
            bindPixelUnpackBuffer(previousBinding);
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            failure = appendFailure(failure, error);
        }
        try { ledger.collect(contextGeneration, pool.length * 2); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    private void abandonPools() {
        Throwable failure = null;
        for (Slot slot : streaming) {
            try {
                LwjglRetirementFence.abandon(slot.fence);
                LwjglRetirementFence.abandon(slot.uncertainFence);
            }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            finally { slot.clearWithoutGl(); }
        }
        for (Slot slot : persistent) {
            try {
                LwjglRetirementFence.abandon(slot.fence);
                LwjglRetirementFence.abandon(slot.uncertainFence);
            }
            catch (Throwable error) { failure = appendFailure(failure, error); }
            finally { slot.clearWithoutGl(); }
        }
        if (failure != null) rethrow(failure);
    }

    private void releaseStaging() {
        stagingBytes = null;
        stagingInts = null;
        stagingCapacity = 0;
        CacheBudget.Reservation reservation = stagingReservation;
        stagingReservation = null;
        if (reservation != null) reservation.close();
    }

    private void captureLast() {
        lastCommandCount = commands.size();
        lastMipLevels = commands.getMipLevels();
        lastBytes = commands.getBytes();
    }

    private static int roundedCapacity(long required) {
        if (required <= 0L || required > MAX_BATCH_BYTES) return -1;
        int value = 64 * 1024;
        while (value < required) {
            if (value > MAX_BATCH_BYTES / 2) return MAX_BATCH_BYTES;
            value <<= 1;
        }
        return value;
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

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("texture stream cleanup failed", failure);
    }

    private static void bindPixelUnpackBuffer(int nativeId) {
        GL15.glBindBuffer(PIXEL_UNPACK_BUFFER, nativeId);
        EarlyGlStateTracker.bindBuffer(PIXEL_UNPACK_BUFFER, nativeId);
    }

    private static Slot[] slots(boolean persistent) {
        Slot[] result = new Slot[SLOT_COUNT];
        for (int i = 0; i < result.length; i++) result[i] = new Slot(persistent);
        return result;
    }

    private static final class Slot {
        private final boolean persistent;
        private RenderHandle handle;
        private int capacity;
        private ByteBuffer mapped;
        private boolean coherent;
        private boolean poisoned;
        private ResourceLedger.RetirementFence fence;
        private ResourceLedger.RetirementFence uncertainFence;

        private Slot(boolean persistent) { this.persistent = persistent; }

        private int nativeId() {
            return handle == null ? 0 : handle.getNativeId();
        }

        private void clear(boolean preserveUncertain) {
            handle = null;
            capacity = 0;
            mapped = null;
            coherent = false;
            fence = null;
            if (!preserveUncertain) uncertainFence = null;
            poisoned = preserveUncertain && uncertainFence != null;
        }

        private void clearWithoutGl() { clear(false); }
    }

}

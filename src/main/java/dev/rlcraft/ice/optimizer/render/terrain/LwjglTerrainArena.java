package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.ArenaStatus;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Render-thread-only terrain arena. New bytes are published only after the
 * complete range has been written; replaced ranges are reclaimed behind a
 * zero-timeout Fence. A layer can mix arena-owned and old VertexBuffers while
 * it migrates, but one VertexBuffer is never resident in both owners.
 */
public final class LwjglTerrainArena {
    private static final int STRIDE = 28;
    private static final int REGION_SIZE = 256;
    private static final int MAX_UPLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_VISIBLE_MESHES = 131072;
    private static final int COVERAGE_BITS = 21;
    private static final long COVERAGE_MASK = (1L << COVERAGE_BITS) - 1L;
    private static final int DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int DRAW_INDIRECT_BUFFER_BINDING = 0x8F43;
    private static final int INDIRECT_COMMAND_BYTES = 16;
    private static final int INDIRECT_SLOT_COUNT = 3;
    private static final int MAX_FENCE_POLLS = 4096;
    private static final float MODEL_SCALE = 1.000001F;
    private static final float MODEL_TRANSLATION = 8.0F * MODEL_SCALE - 8.0F;
    private static final IndirectBufferBindingSandbox.Access INDIRECT_BINDINGS =
        new IndirectBufferBindingSandbox.Access() {
            @Override public int trackedBinding() {
                return EarlyGlStateTracker.drawIndirectBufferBinding();
            }
            @Override public int queryBinding() {
                return GL11.glGetInteger(DRAW_INDIRECT_BUFFER_BINDING);
            }
            @Override public void publishBinding(int nativeId) {
                EarlyGlStateTracker.seedDrawIndirectBuffer(nativeId);
            }
            @Override public void bind(int nativeId) {
                bindIndirectBuffer(nativeId);
            }
            @Override public void invalidate() {
                EarlyGlStateTracker.invalidate();
            }
        };

    private final RenderThreadGuard guard;
    private final ResourceLedger resources;
    private final CacheBudget budget;
    private final CapabilityReport capabilities;
    private final TerrainCommandGenerator commandGenerator =
        new TerrainCommandGenerator();
    private final TerrainCommandGenerator.CommandSink fallbackCommandSink =
        new TerrainCommandGenerator.CommandSink() {
            @Override public void accept(int count, int first, int baseInstance,
                                         int originX, int originY, int originZ,
                                         long sequence, long checksum) {
                firstCommands.put(first);
                countCommands.put(count);
            }
        };
    private final TerrainCommandGenerator.CommandSink indirectCommandSink =
        new TerrainCommandGenerator.CommandSink() {
            @Override public void accept(int count, int first, int baseInstance,
                                         int originX, int originY, int originZ,
                                         long sequence, long checksum) {
                putIndirectCommand(indirectCommands, count, first, baseInstance);
            }
        };
    private final long capacityBytes;
    private final long pageBytes;
    private final IdentityHashMap<VertexBuffer, MeshRecord> meshes =
        new IdentityHashMap<VertexBuffer, MeshRecord>();
    private final ArrayDeque<ArenaRange> pendingRetirement;
    private final ArrayDeque<RetiredRanges> retired =
        new ArrayDeque<RetiredRanges>();
    private GpuArenaAllocator allocator;
    private long arenaGeneration;
    private int bufferId;
    private RenderHandle bufferHandle;
    private ByteBuffer mapped;
    private boolean persistent;
    private boolean mappingPoisoned;
    private Throwable mappingFailure;
    private boolean lastUploadPersistent;
    private long persistentUploads;
    private long subDataUploads;
    private ContextCapabilities contextCapabilities;
    private ByteBuffer staging;
    private CacheBudget.Reservation stagingReservation;
    private IntBuffer firstCommands;
    private IntBuffer countCommands;
    private ByteBuffer fallbackCommandBytes;
    private CacheBudget.Reservation fallbackCommandReservation;
    private ByteBuffer indirectCommands;
    private CacheBudget.Reservation indirectCommandReservation;
    private final IndirectSlot[] indirectSlots = new IndirectSlot[INDIRECT_SLOT_COUNT];
    private int indirectCursor;
    private Throwable indirectFailure;
    private int lastIndirectCommands;
    private int lastIndirectEligibleCommands;
    private boolean lastSubmissionStarted;
    private long nextSequence;
    private long uploadedBytes;
    private long rejectedUploads;
    private long busyFences;
    private long invalidPayloads;
    private long drawCalls;
    private long multiDrawCalls;
    private long indirectDrawCalls;
    private long indirectFallbacks;
    private long multiDrawCapacityFallbacks;
    private long indirectUnknownBindings;
    private long indirectBindingReauthentications;
    private long indirectBindingQueryFailures;
    private long indirectBindingQuerySuppressions;
    private long chunkAnimatorCompatibilityDraws;
    private long failedBindingQueryInvalidation = Long.MIN_VALUE;
    private final long[] indirectReasons =
        new long[TerrainIndirectReason.values().length];
    private TerrainIndirectReason acquireIndirectFailureReason;
    private TerrainIndirectReason slotCapacityFailureReason;
    private long strandedRanges;
    private long strandedBytes;
    private Throwable publicationFailure;

    public LwjglTerrainArena(RenderThreadGuard guard, ResourceLedger resources,
                             CacheBudget budget, CapabilityReport capabilities,
                             long requestedCapacity, long generation) {
        this(guard, resources, budget, capabilities, requestedCapacity,
            generation, new ArrayDeque<ArenaRange>());
    }

    LwjglTerrainArena(RenderThreadGuard guard, ResourceLedger resources,
                      CacheBudget budget, CapabilityReport capabilities,
                      long requestedCapacity, long generation,
                      ArrayDeque<ArenaRange> pendingRetirement) {
        if (guard == null || resources == null || budget == null || capabilities == null
            || generation <= 0L || pendingRetirement == null) {
            throw new IllegalArgumentException("terrain arena");
        }
        this.guard = guard;
        this.resources = resources;
        this.budget = budget;
        this.capabilities = capabilities;
        this.pendingRetirement = pendingRetirement;
        long bounded = Math.max(16L * 1024L * 1024L,
            Math.min(128L * 1024L * 1024L, requestedCapacity));
        bounded = Math.min((long) Integer.MAX_VALUE - STRIDE, bounded);
        long nominalPage = 4L * 1024L * 1024L;
        pageBytes = nominalPage - nominalPage % STRIDE;
        capacityBytes = Math.max(pageBytes, bounded - bounded % pageBytes);
        arenaGeneration = generation;
        allocator = new GpuArenaAllocator(pageBytes, capacityBytes, 4L,
            generation, budget);
        for (int i = 0; i < indirectSlots.length; i++) {
            indirectSlots[i] = new IndirectSlot();
        }
    }

    public boolean upload(TerrainUploadContext.Value context, BufferBuilder builder,
                          VertexBuffer vertexBuffer, FrameStamp stamp,
                          boolean allowPersistentStorage,
                          boolean preferPersistentWrite,
                          boolean retainLegacyCopy) {
        guard.check();
        lastUploadPersistent = false;
        mappingFailure = null;
        if (context == null || builder == null || vertexBuffer == null || stamp == null
            || !(vertexBuffer instanceof ChunkVertexBufferAccessor)) {
            invalidPayloads++;
            return false;
        }
        TerrainLayer layer = layer(context.getLayer());
        ByteBuffer source = builder.getByteBuffer();
        VertexFormat format = builder.getVertexFormat();
        int stride = format == null ? 0 : format.getSize();
        int sourceBytes = source == null ? -1 : source.limit();
        int vertices = builder.getVertexCount();
        int bytes;
        try { bytes = Math.multiplyExact(vertices, STRIDE); }
        catch (ArithmeticException overflow) { bytes = -1; }
        boolean formatCompatible = stride == STRIDE
            || retainLegacyCopy && hasVanillaBlockPrefix(format);
        if (layer == null || !formatCompatible || source == null
            || source.position() != 0
            || sourceBytes <= 0 || sourceBytes > MAX_UPLOAD_BYTES
            || stride < STRIDE || sourceBytes % stride != 0
            || vertices != sourceBytes / stride || bytes <= 0
            || bytes > MAX_UPLOAD_BYTES || (vertices & 3) != 0) {
            invalidPayloads++;
            return false;
        }
        if (!ensureStaging(bytes)) {
            rejectedUploads++;
            return false;
        }
        if (!ensureBuffer(stamp, allowPersistentStorage)) {
            rejectedUploads++;
            return false;
        }
        ArenaRange range = allocator.allocate(bytes);
        if (range == null || range.getOffset() % STRIDE != 0L
            || range.endExclusive() > capacityBytes) {
            if (range != null) allocator.free(range);
            rejectedUploads++;
            return false;
        }

        BlockPos position = context.getChunk().getPosition();
        int regionX = regionOrigin(position.getX());
        int regionY = regionOrigin(position.getY());
        int regionZ = regionOrigin(position.getZ());
        MeshRecord replacement = null;
        MeshRecord previous = null;
        boolean putReturned = false;
        boolean previousRetired = false;
        try {
            ByteBuffer normalized = normalizeIntoStaging(source, sourceBytes,
                stride, bytes, position, regionX, regionY, regionZ);
            long normalizedChecksum = checksum(normalized, bytes);
            lastUploadPersistent = write(range, normalized,
                preferPersistentWrite);
            TerrainMesh mesh = TerrainMesh.metadataOnly(layer, vertices, STRIDE,
                position.getX(), position.getY(), position.getZ(),
                nextSequence(), normalizedChecksum, range);
            replacement = new MeshRecord(context.getChunk(), regionX, regionY,
                regionZ, stamp, mesh, lastUploadPersistent, retainLegacyCopy);
            previous = meshes.get(vertexBuffer);
            MeshRecord displaced = meshes.put(vertexBuffer, replacement);
            putReturned = true;
            if (displaced != previous) {
                throw new IllegalStateException(
                    "terrain mesh registry changed during publication");
            }
            if (previous != null) {
                retireOrStrand(previous.range);
                previousRetired = true;
            }
            if (!retainLegacyCopy) builder.reset();
            uploadedBytes = safeAdd(uploadedBytes, bytes);
            if (lastUploadPersistent) {
                persistentUploads = safeAdd(persistentUploads, 1L);
            } else {
                subDataUploads = safeAdd(subDataUploads, 1L);
            }
            return true;
        } catch (Throwable error) {
            Throwable failure = rollbackFailedUpload(vertexBuffer, replacement,
                previous, putReturned, previousRetired, range, error);
            rethrow(failure);
            return false;
        }
    }

    public void release(VertexBuffer vertexBuffer) {
        guard.check();
        if (vertexBuffer == null) return;
        MeshRecord previous = null;
        boolean previousKnown = false;
        boolean retirementAttempted = false;
        try {
            previous = meshes.get(vertexBuffer);
            previousKnown = true;
            MeshRecord removed = meshes.remove(vertexBuffer);
            if (removed != previous) throw new IllegalStateException(
                "terrain mesh registry changed during release");
            if (previous != null) {
                retirementAttempted = true;
                retireOrStrand(previous.range);
            }
        } catch (Throwable error) {
            Throwable failure = error;
            if (previousKnown && previous != null && !retirementAttempted) {
                try {
                    MeshRecord current = meshes.get(vertexBuffer);
                    if (current != previous) retireOrStrand(previous.range);
                } catch (Throwable recoveryFailure) {
                    failure = appendFailure(failure, recoveryFailure);
                }
            }
            // A mapping that may still be published is left live; a mapping
            // proven removed is retired or explicitly stranded above.
            recordPublicationFailure(failure);
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Confirms the one untouched VBO upload paired with a ShaderPack shadow
     * publication.  Any unrelated legacy upload invalidates the old arena
     * record so stale geometry can never win a later native decision.
     */
    public boolean beforeLegacyUpload(VertexBuffer vertexBuffer) {
        guard.check();
        if (vertexBuffer == null) return false;
        MeshRecord record = meshes.get(vertexBuffer);
        if (record != null && record.retainLegacyCopy
            && record.legacyUploadExpected) {
            record.legacyUploadExpected = false;
            return true;
        }
        release(vertexBuffer);
        return false;
    }

    public boolean ownsAny(Object container, BlockRenderLayer blockLayer) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor)) return false;
        TerrainLayer expected = layer(blockLayer);
        if (expected == null) return false;
        for (RenderChunk chunk : ((TerrainRenderListAccessor) container).ice$renderChunks()) {
            MeshRecord record = record(chunk, expected);
            if (record != null) return true;
        }
        return false;
    }

    /**
     * Packs visible count, arena-owned count and contiguous same-region runs
     * into one allocation-free render-thread snapshot.
     */
    public long inspectCoverage(Object container, BlockRenderLayer blockLayer) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor)) return 0L;
        TerrainLayer expected = layer(blockLayer);
        if (expected == null) return 0L;
        List<RenderChunk> visible =
            ((TerrainRenderListAccessor) container).ice$renderChunks();
        if (visible == null) return 0L;
        int size = Math.min(visible.size(), (int) COVERAGE_MASK);
        int owned = 0;
        int regionRuns = 0;
        MeshRecord previous = null;
        for (int index = 0; index < size; index++) {
            MeshRecord current = record(visible.get(index), expected);
            if (current == null) {
                previous = null;
                continue;
            }
            owned++;
            if (previous == null || !previous.sameRegion(current)) regionRuns++;
            previous = current;
        }
        return packCoverage(size, owned, regionRuns);
    }

    /** True when every arena-owned visible mesh has a completed Legacy twin. */
    public boolean canReplayLegacy(Object container,
                                   BlockRenderLayer blockLayer) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor)) return false;
        TerrainLayer expected = layer(blockLayer);
        if (expected == null) return false;
        List<RenderChunk> visible =
            ((TerrainRenderListAccessor) container).ice$renderChunks();
        if (visible == null) return false;
        boolean found = false;
        for (RenderChunk chunk : visible) {
            MeshRecord record = record(chunk, expected);
            if (record == null) continue;
            found = true;
            if (!record.retainLegacyCopy || record.legacyUploadExpected) {
                return false;
            }
        }
        return found;
    }

    public static int coverageVisible(long packed) {
        return (int) (packed & COVERAGE_MASK);
    }

    public static int coverageOwned(long packed) {
        return (int) ((packed >>> COVERAGE_BITS) & COVERAGE_MASK);
    }

    public static int coverageRegionRuns(long packed) {
        return (int) ((packed >>> (COVERAGE_BITS * 2)) & COVERAGE_MASK);
    }

    static long packCoverage(int visible, int owned, int regionRuns) {
        long boundedVisible = Math.max(0L,
            Math.min(COVERAGE_MASK, (long) visible));
        long boundedOwned = Math.max(0L,
            Math.min(boundedVisible, (long) owned));
        long boundedRuns = Math.max(0L,
            Math.min(boundedOwned, (long) regionRuns));
        return boundedVisible | boundedOwned << COVERAGE_BITS
            | boundedRuns << (COVERAGE_BITS * 2);
    }

    /** Returns false only when the untouched VboRenderList must run. */
    public boolean render(Object container, BlockRenderLayer blockLayer,
                          boolean batchMappedRanges, boolean useIndirect,
                          long frameId) {
        guard.check();
        lastIndirectCommands = 0;
        lastIndirectEligibleCommands = 0;
        lastSubmissionStarted = false;
        indirectFailure = null;
        if (!(container instanceof TerrainRenderListAccessor) || bufferId == 0) return false;
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        if (!accessor.ice$initialized()) return false;
        TerrainLayer expected = layer(blockLayer);
        if (expected == null || !ownsAny(container, blockLayer)) return false;
        List<RenderChunk> visible = accessor.ice$renderChunks();
        int previousArrayBuffer = preserveArrayBufferBinding();
        int index = 0;
        boolean completed = false;
        Throwable failure = null;
        try {
            while (index < visible.size()) {
                RenderChunk chunk = visible.get(index);
                MeshRecord record = record(chunk, expected);
                if (record == null) {
                    drawLegacy(chunk, blockLayer, accessor);
                    drawCalls++;
                    index++;
                    continue;
                }
                if (ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)) {
                    drawMappedWithCompatibilityTransform(chunk, record, accessor,
                        frameId);
                    index++;
                    continue;
                }
                int end = index + 1;
                if (batchMappedRanges) {
                    while (end < visible.size()) {
                        RenderChunk nextChunk = visible.get(end);
                        MeshRecord next = record(nextChunk, expected);
                        if (next == null || !record.sameRegion(next)
                            || ChunkAnimatorRenderBridge
                                .requiresCompatibilityDraw(nextChunk)) break;
                        end++;
                    }
                }
                drawMappedRun(visible, index, end, expected, accessor,
                    useIndirect, frameId);
                index = end;
            }
            completed = true;
        } catch (Throwable error) {
            failure = error;
        } finally {
            try { restoreArrayBufferBinding(previousArrayBuffer); }
            catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
            }
            try { GlStateManager.resetColor(); }
            catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
            }
            // Preserve the list only when no draw could have reached the
            // driver. The bridge can then retry the exact sequence through
            // the unbatched arena/legacy mixed path. Once any submission may
            // have happened, clearing prevents duplicate translucent draws.
            if (completed || lastSubmissionStarted) try { visible.clear(); }
            catch (Throwable clearError) {
                failure = appendFailure(failure, clearError);
            }
        }
        if (failure != null) rethrow(failure);
        return true;
    }

    /** Expensive readback is used only by the OUTPUT_VALIDATE state. */
    public Boolean validateOne(Object container, BlockRenderLayer blockLayer,
                               FrameStamp current) {
        return validateOne(container, blockLayer, current, false);
    }

    public Boolean validateOnePersistent(Object container,
                                         BlockRenderLayer blockLayer,
                                         FrameStamp current) {
        return validateOne(container, blockLayer, current, true);
    }

    private Boolean validateOne(Object container, BlockRenderLayer blockLayer,
                                FrameStamp current, boolean persistentOnly) {
        guard.check();
        if (!(container instanceof TerrainRenderListAccessor) || bufferId == 0) return false;
        TerrainLayer expected = layer(blockLayer);
        for (RenderChunk chunk : ((TerrainRenderListAccessor) container).ice$renderChunks()) {
            MeshRecord record = record(chunk, expected);
            if (record == null || !record.matchesGeneration(current)) continue;
            if (persistentOnly && !record.persistentUpload) continue;
            int bytes = record.byteCount;
            if (!ensureStaging(bytes)) return Boolean.FALSE;
            int previous = preserveArrayBufferBinding();
            Boolean result = null;
            Throwable failure = null;
            try {
                bindArrayBuffer(bufferId);
                staging.clear();
                staging.limit(bytes);
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,
                    record.range.getOffset(), staging);
                result = Boolean.valueOf(
                    checksum(staging, bytes) == record.checksum);
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally {
                try { restoreArrayBufferBinding(previous); }
                catch (Throwable restoreError) {
                    failure = appendFailure(failure, restoreError);
                }
            }
            if (failure != null) rethrow(failure);
            return result;
        }
        return null;
    }

    public void endFrame(boolean contextValid) {
        guard.check();
        collectRetired(8, contextValid);
        if (pendingRetirement.isEmpty()) return;
        if (!contextValid) {
            pendingRetirement.clear();
            return;
        }
        LwjglRetirementFence fence;
        try {
            fence = LwjglRetirementFence.tryAfterCurrentCommands(resources);
        }
        catch (Throwable error) {
            Throwable failure = error;
            try { strandPendingRanges(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            busyFences++;
            recordPublicationFailure(failure);
            FatalErrors.rethrowIfFatal(failure);
            return;
        }
        if (fence == null) {
            // Never reuse ranges whose completion cannot be proven.
            strandPendingRanges();
            busyFences++;
            recordPublicationFailure(new IllegalStateException(
                "terrain retirement Fence creation failed"));
            return;
        }
        RetiredRanges publishedBatch = null;
        try {
            ArrayList<ArenaRange> ranges =
                new ArrayList<ArenaRange>(pendingRetirement);
            publishedBatch = new RetiredRanges(ranges, fence);
            retired.addLast(publishedBatch);
            pendingRetirement.clear();
        } catch (Throwable publicationFailure) {
            if (publishedBatch != null) try {
                retired.removeLastOccurrence(publishedBatch);
            } catch (Throwable cleanupFailure) {
                publicationFailure = appendFailure(publicationFailure,
                    cleanupFailure);
            }
            try { fence.destroy(); }
            catch (Throwable cleanupFailure) {
                publicationFailure = appendFailure(publicationFailure,
                    cleanupFailure);
            }
            recordPublicationFailure(publicationFailure);
            FatalErrors.rethrowIfFatal(publicationFailure);
        }
    }

    public void reset(long nextGeneration, boolean contextValid) {
        guard.check();
        if (nextGeneration <= arenaGeneration) throw new IllegalArgumentException("terrain generation");
        Throwable failure = null;
        try { meshes.clear(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { pendingRetirement.clear(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        failure = releaseRetiredFences(contextValid, failure);
        try { closeIndirectSlots(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { closeBuffer(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try {
            allocator.reset(nextGeneration);
            arenaGeneration = nextGeneration;
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        if (failure != null) rethrow(failure);
    }

    public void close(boolean contextValid) {
        guard.check();
        Throwable failure = null;
        try { meshes.clear(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { pendingRetirement.clear(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        failure = releaseRetiredFences(contextValid, failure);
        try { closeIndirectSlots(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { closeBuffer(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { closeStaging(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { allocator.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public long getUploadedBytes() { return uploadedBytes; }
    public long getRejectedUploads() { return rejectedUploads; }
    public long getInvalidPayloads() { return invalidPayloads; }
    public int getOwnedMeshes() { guard.check(); return meshes.size(); }
    public int getShadowOwnedMeshes() {
        guard.check();
        int count = 0;
        for (MeshRecord record : meshes.values()) {
            if (record.retainLegacyCopy) count++;
        }
        return count;
    }
    public long getShadowOwnedBytes() {
        guard.check();
        long bytes = 0L;
        for (MeshRecord record : meshes.values()) {
            if (record.retainLegacyCopy) {
                bytes = safeAdd(bytes, record.byteCount);
            }
        }
        return bytes;
    }
    public ArenaStatus allocatorStatus() { guard.check(); return allocator.snapshot(); }
    public long getBusyFences() { return busyFences; }
    public long getDrawCalls() { return drawCalls; }
    public long getMultiDrawCalls() { return multiDrawCalls; }
    public long getIndirectDrawCalls() { return indirectDrawCalls; }
    public long getIndirectFallbacks() { return indirectFallbacks; }
    public long getMultiDrawCapacityFallbacks() {
        return multiDrawCapacityFallbacks;
    }
    public long getIndirectUnknownBindings() { return indirectUnknownBindings; }
    public long getIndirectBindingReauthentications() {
        return indirectBindingReauthentications;
    }
    public long getIndirectBindingQueryFailures() {
        return indirectBindingQueryFailures;
    }
    public long getIndirectBindingQuerySuppressions() {
        return indirectBindingQuerySuppressions;
    }
    public long getChunkAnimatorCompatibilityDraws() {
        return chunkAnimatorCompatibilityDraws;
    }
    public long getIndirectReason(TerrainIndirectReason reason) {
        return reason == null ? 0L : indirectReasons[reason.ordinal()];
    }
    public int getLastIndirectCommands() { return lastIndirectCommands; }
    public int getLastIndirectEligibleCommands() {
        return lastIndirectEligibleCommands;
    }
    public boolean wasLastSubmissionStarted() { return lastSubmissionStarted; }
    public long getStrandedRanges() { return strandedRanges; }
    public long getStrandedBytes() { return strandedBytes; }
    public Throwable consumePublicationFailure() {
        Throwable result = publicationFailure;
        publicationFailure = null;
        return result;
    }
    void retireRangeForTest(ArenaRange range) { retireOrStrand(range); }
    public boolean isPersistent() { return persistent; }
    public boolean wasLastUploadPersistent() { return lastUploadPersistent; }
    public long getPersistentUploads() { return persistentUploads; }
    public long getSubDataUploads() { return subDataUploads; }
    public Throwable consumeMappingFailure() {
        Throwable result = mappingFailure;
        mappingFailure = null;
        return result;
    }
    public boolean supportsIndirectCommands() {
        return capabilities.passed(ModernCapability.MULTI_DRAW_INDIRECT);
    }
    public Throwable consumeIndirectFailure() {
        Throwable result = indirectFailure;
        indirectFailure = null;
        return result;
    }

    private boolean ensureBuffer(FrameStamp stamp,
                                 boolean allowPersistentStorage) {
        if (bufferId != 0) return true;
        contextCapabilities = GLContext.getCapabilities();
        int previous = preserveArrayBufferBinding();
        CacheBudget.Reservation reservation = null;
        int created = 0;
        ByteBuffer createdMapping = null;
        boolean createdPersistent = false;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        boolean nativeDeleteAttempted = false;
        boolean result = false;
        Throwable failure = null;
        try {
            reservation = resources.reserveGpu(capacityBytes);
            if (reservation == null) {
                // State still has to be restored because preserving the
                // compatibility binding may have pushed client attributes.
                result = false;
            } else {
            allocationReturned = false;
            created = GL15.glGenBuffers();
            allocationReturned = true;
            if (created <= 0) throw new IllegalStateException(
                "terrain glGenBuffers failed");
            nativeNameCreated = true;
            bindArrayBuffer(created);
            if (allowPersistentStorage
                && capabilities.passed(ModernCapability.PERSISTENT_MAPPING)) {
                try {
                    int storage = GL30.GL_MAP_WRITE_BIT
                        | ARBBufferStorage.GL_MAP_PERSISTENT_BIT
                        | ARBBufferStorage.GL_DYNAMIC_STORAGE_BIT;
                    int mapping = GL30.GL_MAP_WRITE_BIT
                        | ARBBufferStorage.GL_MAP_PERSISTENT_BIT
                        | GL30.GL_MAP_FLUSH_EXPLICIT_BIT;
                    ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, capacityBytes, storage);
                    createdMapping = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0L,
                        capacityBytes, mapping, null);
                    if (createdMapping == null) throw new IllegalStateException(
                        "null terrain mapping");
                    createdMapping.order(ByteOrder.nativeOrder());
                    createdPersistent = true;
                } catch (Throwable mappingFailure) {
                    mappingPoisoned = true;
                    if (createdMapping != null) {
                        try {
                            if (!GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER)) {
                                throw new IllegalStateException(
                                    "failed terrain mapping unmap reported corruption");
                            }
                        }
                        catch (Throwable cleanup) {
                            mappingFailure = appendFailure(mappingFailure,
                                cleanup);
                        }
                    }
                    createdMapping = null;
                    createdPersistent = false;
                    boolean deleteCompleted = false;
                    nativeDeleteAttempted = true;
                    try {
                        GL15.glDeleteBuffers(created);
                        deleteCompleted = true;
                    } catch (Throwable cleanup) {
                        Throwable combined = appendFailure(mappingFailure,
                            cleanup);
                        recordMappingFailure(combined);
                        throw propagate(combined);
                    }
                    if (!deleteCompleted) {
                        recordMappingFailure(mappingFailure);
                        throw propagate(mappingFailure);
                    }
                    created = 0;
                    nativeNameCreated = false;
                    recordMappingFailure(mappingFailure);
                    FatalErrors.rethrowIfFatal(mappingFailure);
                    try {
                        allocationReturned = false;
                        nativeDeleteAttempted = false;
                        created = GL15.glGenBuffers();
                        allocationReturned = true;
                        if (created <= 0) throw new IllegalStateException(
                            "terrain fallback glGenBuffers failed");
                        nativeNameCreated = true;
                        bindArrayBuffer(created);
                        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, capacityBytes,
                            GL15.GL_DYNAMIC_DRAW);
                    } catch (Throwable fallbackFailure) {
                        Throwable combined = appendFailure(fallbackFailure,
                            mappingFailure);
                        recordMappingFailure(combined);
                        throw propagate(combined);
                    }
                }
            } else {
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, capacityBytes, GL15.GL_DYNAMIC_DRAW);
            }
            RenderHandle registered = resources.registerReserved(
                RenderResourceKind.BUFFER, created, capacityBytes,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            if (registered != null) {
                // Registration transfers exclusive ownership before publication.
                int publishedId = created;
                created = 0;
                nativeNameCreated = false;
                reservation = null;
                bufferId = publishedId;
                bufferHandle = registered;
                mapped = createdMapping;
                createdMapping = null;
                persistent = createdPersistent;
                if (createdPersistent) mappingPoisoned = false;
                result = true;
            }
            }
        } catch (Throwable error) {
            failure = error;
        }
        if (createdMapping != null) {
            createdMapping = null;
            try {
                if (!GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER)) {
                    failure = appendFailure(failure,
                        new IllegalStateException(
                            "terrain buffer unmap reported corruption"));
                }
            } catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        boolean deleteCompleted = false;
        if (nativeNameCreated && !nativeDeleteAttempted) {
            nativeDeleteAttempted = true;
            try {
                GL15.glDeleteBuffers(created);
                deleteCompleted = true;
            } catch (Throwable cleanup) {
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
        try { restoreArrayBufferBinding(previous); }
        catch (Throwable restoreError) {
            EarlyGlStateTracker.invalidate();
            failure = appendFailure(failure, restoreError);
        }
        if (failure != null) rethrow(failure);
        return result;
    }

    private boolean write(ArenaRange range, ByteBuffer normalized,
                          boolean preferPersistentWrite) {
        int offset = checkedInt(range.getOffset());
        int bytes = normalized.remaining();
        if (preferPersistentWrite && mapped != null && !mappingPoisoned) {
            int previous = Integer.MIN_VALUE;
            boolean bindingPreserved = false;
            Throwable failure = null;
            try {
                ByteBuffer destination = mapped.duplicate()
                    .order(ByteOrder.nativeOrder());
                destination.position(offset);
                destination.limit(checkedInt(range.endExclusive()));
                destination.put(normalized.duplicate());
                previous = preserveArrayBufferBinding();
                bindingPreserved = true;
                bindArrayBuffer(bufferId);
                GL30.glFlushMappedBufferRange(GL15.GL_ARRAY_BUFFER,
                    range.getOffset(), bytes);
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally {
                if (bindingPreserved) try {
                    restoreArrayBufferBinding(previous);
                } catch (Throwable restoreError) {
                    EarlyGlStateTracker.invalidate();
                    failure = appendFailure(failure, restoreError);
                }
            }
            if (failure == null) return true;
            // The range is not published yet. Re-uploading identical bytes
            // through subdata repairs a partial mapped write without ever
            // exposing it to a draw.
            mappingPoisoned = true;
            recordMappingFailure(failure);
            EarlyGlStateTracker.invalidate();
            FatalErrors.rethrowIfFatal(failure);
        }
        int previous = preserveArrayBufferBinding();
        Throwable failure = null;
        try {
            bindArrayBuffer(bufferId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, range.getOffset(),
                normalized.duplicate());
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            try { restoreArrayBufferBinding(previous); }
            catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
            }
        }
        if (failure != null) rethrow(failure);
        return false;
    }

    private boolean ensureStaging(int required) {
        if (required <= 0 || required > MAX_UPLOAD_BYTES) return false;
        if (staging != null && staging.capacity() >= required) return true;
        int capacity = 64 * 1024;
        while (capacity < required && capacity < MAX_UPLOAD_BYTES) capacity <<= 1;
        capacity = Math.max(required, Math.min(MAX_UPLOAD_BYTES, capacity));
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.DIRECT, capacity);
        if (reservation == null) return false;
        ByteBuffer replacement;
        try { replacement = BufferUtils.createByteBuffer(capacity).order(ByteOrder.nativeOrder()); }
        catch (Throwable error) {
            Throwable failure = error;
            try { reservation.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            rethrow(failure);
            return false;
        }
        CacheBudget.Reservation old = stagingReservation;
        staging = replacement;
        stagingReservation = reservation;
        if (old != null) old.close();
        return true;
    }

    private void drawMappedRun(List<RenderChunk> visible, int start, int end,
                               TerrainLayer expected,
                               TerrainRenderListAccessor accessor,
                               boolean useIndirect, long frameId) {
        MeshRecord first = record(visible.get(start), expected);
        if (first == null) throw new IllegalStateException("missing mapped terrain run");
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate((float) (first.regionX - accessor.ice$viewEntityX()),
                (float) (first.regionY - accessor.ice$viewEntityY()),
                (float) (first.regionZ - accessor.ice$viewEntityZ()));
            bindArrayBuffer(bufferId);
            setupArrayPointers(0L);
            int count = end - start;
            if (count == 1) {
                ArenaRange range = first.range;
                int firstVertex = checkedInt(range.getOffset() / STRIDE);
                lastSubmissionStarted = true;
                GL11.glDrawArrays(GL11.GL_QUADS, firstVertex, first.vertexCount);
                drawCalls++;
                first.lastDrawFrame = frameId;
                return;
            }
            if (supportsIndirectCommands()) {
                lastIndirectEligibleCommands = Math.addExact(
                    lastIndirectEligibleCommands, count);
            }
            if (useIndirect && drawIndirect(visible, start, end, expected,
                first.generation)) {
                for (int i = start; i < end; i++) {
                    MeshRecord record = record(visible.get(i), expected);
                    if (record != null) record.lastDrawFrame = frameId;
                }
                return;
            }
            if (!ensureCommandCapacity(count)) {
                multiDrawCapacityFallbacks = safeAdd(
                    multiDrawCapacityFallbacks, 1L);
                for (int i = start; i < end; i++) {
                    MeshRecord record = record(visible.get(i), expected);
                    int firstVertex = checkedInt(record.range.getOffset() / STRIDE);
                    lastSubmissionStarted = true;
                    GL11.glDrawArrays(GL11.GL_QUADS, firstVertex,
                        record.vertexCount);
                    record.lastDrawFrame = frameId;
                    drawCalls++;
                }
                return;
            }
            firstCommands.clear();
            countCommands.clear();
            for (int i = start; i < end; i++) {
                MeshRecord record = record(visible.get(i), expected);
                commandGenerator.emit(record.mesh, expected, i - start,
                    fallbackCommandSink);
                record.lastDrawFrame = frameId;
            }
            firstCommands.flip();
            countCommands.flip();
            lastSubmissionStarted = true;
            GL14.glMultiDrawArrays(GL11.GL_QUADS, firstCommands, countCommands);
            multiDrawCalls++;
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            try { GlStateManager.popMatrix(); }
            catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
            }
            if (failure != null) rethrow(failure);
        }
    }

    private void drawLegacy(RenderChunk chunk, BlockRenderLayer layer,
                            TerrainRenderListAccessor accessor) {
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            preRenderChunk(chunk, accessor);
            chunk.multModelviewMatrix();
            VertexBuffer vertexBuffer = chunk.getVertexBufferByLayer(layer.ordinal());
            vertexBuffer.bindBuffer();
            setupArrayPointers(0L);
            lastSubmissionStarted = true;
            vertexBuffer.drawArrays(GL11.GL_QUADS);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            try { GlStateManager.popMatrix(); }
            catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
            }
            if (failure != null) rethrow(failure);
        }
    }

    /**
     * A pending ChunkAnimator transform cannot share a region matrix with a
     * multi-draw run.  Preserve the transformed container call, then translate
     * the arena's region-local vertices back from chunk to region origin.
     */
    private void drawMappedWithCompatibilityTransform(
        RenderChunk chunk, MeshRecord record, TerrainRenderListAccessor accessor,
        long frameId) {
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            preRenderChunk(chunk, accessor);
            BlockPos position = chunk.getPosition();
            GlStateManager.translate(record.regionX - position.getX(),
                record.regionY - position.getY(),
                record.regionZ - position.getZ());
            bindArrayBuffer(bufferId);
            setupArrayPointers(0L);
            int firstVertex = checkedInt(record.range.getOffset() / STRIDE);
            lastSubmissionStarted = true;
            GL11.glDrawArrays(GL11.GL_QUADS, firstVertex, record.vertexCount);
            record.lastDrawFrame = frameId;
            drawCalls++;
            chunkAnimatorCompatibilityDraws = safeAdd(
                chunkAnimatorCompatibilityDraws, 1L);
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            try { GlStateManager.popMatrix(); }
            catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
            }
            if (failure != null) rethrow(failure);
        }
    }

    private static void preRenderChunk(RenderChunk chunk,
                                       TerrainRenderListAccessor accessor) {
        if (accessor instanceof ChunkRenderContainer) {
            ((ChunkRenderContainer) accessor).preRenderChunk(chunk);
            return;
        }
        BlockPos position = chunk.getPosition();
        GlStateManager.translate((float) (position.getX()
                - accessor.ice$viewEntityX()),
            (float) (position.getY() - accessor.ice$viewEntityY()),
            (float) (position.getZ() - accessor.ice$viewEntityZ()));
    }

    private static void setupArrayPointers(long base) {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, base);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, base + 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, base + 16L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, STRIDE, base + 24L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private boolean ensureCommandCapacity(int required) {
        if (required <= 0 || required > MAX_VISIBLE_MESHES) {
            throw new IllegalArgumentException("terrain command count");
        }
        if (firstCommands != null && firstCommands.capacity() >= required) return true;
        int capacity = 256;
        while (capacity < required && capacity < MAX_VISIBLE_MESHES) capacity <<= 1;
        capacity = Math.min(MAX_VISIBLE_MESHES, Math.max(required, capacity));
        int bytes = Math.multiplyExact(capacity, 8);
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, bytes);
        if (reservation == null) return false;
        ByteBuffer replacement;
        try {
            replacement = ByteBuffer.allocateDirect(bytes)
                .order(ByteOrder.nativeOrder());
        } catch (Throwable error) {
            Throwable failure = error;
            try { reservation.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            rethrow(failure);
            return false;
        }
        ByteBuffer firstBytes = replacement.duplicate().order(ByteOrder.nativeOrder());
        firstBytes.position(0).limit(capacity * 4);
        IntBuffer replacementFirst = firstBytes.slice()
            .order(ByteOrder.nativeOrder()).asIntBuffer();
        ByteBuffer countBytes = replacement.duplicate().order(ByteOrder.nativeOrder());
        countBytes.position(capacity * 4).limit(bytes);
        IntBuffer replacementCount = countBytes.slice()
            .order(ByteOrder.nativeOrder()).asIntBuffer();
        CacheBudget.Reservation old = fallbackCommandReservation;
        fallbackCommandBytes = replacement;
        firstCommands = replacementFirst;
        countCommands = replacementCount;
        fallbackCommandReservation = reservation;
        if (old != null) old.close();
        return true;
    }

    private boolean drawIndirect(List<RenderChunk> visible, int start, int end,
                                 TerrainLayer expected, FrameStamp stamp) {
        int count = end - start;
        if (count <= 1 || !supportsIndirectCommands()) return false;
        boolean bindingWasUnknown = EarlyGlStateTracker
            .drawIndirectBufferBinding() == IndirectBufferBindingSandbox.UNKNOWN;
        if (bindingWasUnknown) {
            indirectUnknownBindings = safeAdd(indirectUnknownBindings, 1L);
            if (failedBindingQueryInvalidation
                == EarlyGlStateTracker.invalidations()) {
                indirectBindingQuerySuppressions = safeAdd(
                    indirectBindingQuerySuppressions, 1L);
                recordIndirectFallback(TerrainIndirectReason.UNKNOWN_BINDING);
                return false;
            }
        }
        IndirectBufferBindingSandbox.Lease binding;
        try {
            binding = IndirectBufferBindingSandbox.acquire(INDIRECT_BINDINGS);
            if (binding.queried()) {
                indirectBindingReauthentications = safeAdd(
                    indirectBindingReauthentications, 1L);
                failedBindingQueryInvalidation = Long.MIN_VALUE;
            }
        } catch (Throwable authenticationFailure) {
            indirectBindingQueryFailures = safeAdd(
                indirectBindingQueryFailures, 1L);
            failedBindingQueryInvalidation =
                EarlyGlStateTracker.invalidations();
            recordIndirectFallback(TerrainIndirectReason.UNKNOWN_BINDING);
            recordIndirectFailure(authenticationFailure);
            FatalErrors.rethrowIfFatal(authenticationFailure);
            return false;
        }
        int previousIndirect = binding.previous();
        IndirectSlot slot = acquireIndirectSlot(stamp);
        if (slot == null) {
            recordIndirectFallback(acquireIndirectFailureReason == null
                ? TerrainIndirectReason.SLOT_UNAVAILABLE_OR_BUSY
                : acquireIndirectFailureReason);
            return false;
        }
        if (!ensureIndirectCommandCapacity(count)) {
            recordIndirectFallback(
                TerrainIndirectReason.DIRECT_COMMAND_CAPACITY);
            return false;
        }
        if (!ensureIndirectSlotCapacity(slot,
            Math.multiplyExact(count, INDIRECT_COMMAND_BYTES), stamp,
            previousIndirect)) {
            recordIndirectFallback(slotCapacityFailureReason == null
                ? TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION
                : slotCapacityFailureReason);
            return false;
        }
        int bytes = Math.multiplyExact(count, INDIRECT_COMMAND_BYTES);
        indirectCommands.clear();
        indirectCommands.limit(bytes);
        for (int i = start; i < end; i++) {
            MeshRecord record = record(visible.get(i), expected);
            if (record == null) throw new IllegalStateException(
                "terrain indirect record disappeared");
            commandGenerator.emit(record.mesh, expected, i - start,
                indirectCommandSink);
        }
        indirectCommands.flip();
        boolean submissionStarted = false;
        boolean result = false;
        boolean failureReasonRecorded = false;
        Throwable failure = null;
        try {
            binding.bind(slot.nativeId());
            GL15.glBufferData(DRAW_INDIRECT_BUFFER, (long) slot.capacity,
                GL15.GL_STREAM_DRAW);
            GL15.glBufferSubData(DRAW_INDIRECT_BUFFER, 0L, indirectCommands);
            submissionStarted = true;
            lastSubmissionStarted = true;
            GL43.glMultiDrawArraysIndirect(GL11.GL_QUADS, 0L, count, 0);
            slot.fence = LwjglRetirementFence.tryAfterCurrentCommands(resources);
            if (slot.fence == null) throw new IllegalStateException(
                "terrain indirect Fence creation failed");
            lastIndirectCommands = Math.addExact(lastIndirectCommands, count);
            indirectDrawCalls++;
            result = true;
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
            slot.poisoned = true;
            if (submissionStarted && slot.fence == null) {
                // A throwing driver call may already have consumed commands.
                // Without a real Fence this name must not be deleted or reused
                // while the context is alive.  The ledger will eventually
                // strand it behind this never-ready marker.
                slot.fence = NeverReadyFence.INSTANCE;
            }
            if (submissionStarted) {
                // The driver may have accepted the MDI command. Never replay
                // this run and risk duplicate translucent/side-effect draws.
                lastIndirectCommands = Math.addExact(lastIndirectCommands, count);
                indirectDrawCalls++;
                result = true;
                recordIndirectReason(
                    TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE);
                failureReasonRecorded = true;
            } else {
                recordIndirectFallback(
                    TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE);
                failureReasonRecorded = true;
            }
        } finally {
            try {
                binding.restore();
            } catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
                if (!failureReasonRecorded) {
                    if (result) recordIndirectReason(
                        TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE);
                    else recordIndirectFallback(
                        TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE);
                    failureReasonRecorded = true;
                }
            }
        }
        if (failure != null) {
            recordIndirectFailure(failure);
            FatalErrors.rethrowIfFatal(failure);
        }
        return result;
    }

    private boolean ensureIndirectCommandCapacity(int required) {
        if (required <= 0 || required > MAX_VISIBLE_MESHES) return false;
        int requiredBytes = Math.multiplyExact(required, INDIRECT_COMMAND_BYTES);
        if (indirectCommands != null
            && indirectCommands.capacity() >= requiredBytes) return true;
        int capacity = 256;
        while (capacity < required && capacity < MAX_VISIBLE_MESHES) capacity <<= 1;
        capacity = Math.min(MAX_VISIBLE_MESHES, Math.max(required, capacity));
        int bytes = Math.multiplyExact(capacity, INDIRECT_COMMAND_BYTES);
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, bytes);
        if (reservation == null) return false;
        ByteBuffer replacement;
        try {
            replacement = ByteBuffer.allocateDirect(bytes)
                .order(ByteOrder.nativeOrder());
        } catch (Throwable error) {
            Throwable failure = error;
            try { reservation.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            rethrow(failure);
            return false;
        }
        CacheBudget.Reservation old = indirectCommandReservation;
        indirectCommands = replacement;
        indirectCommandReservation = reservation;
        if (old != null) old.close();
        return true;
    }

    private IndirectSlot acquireIndirectSlot(FrameStamp stamp) {
        acquireIndirectFailureReason = null;
        for (int checked = 0; checked < indirectSlots.length; checked++) {
            int index = (indirectCursor + checked) % indirectSlots.length;
            IndirectSlot slot = indirectSlots[index];
            if (slot.poisoned) continue;
            if (slot.handle != null && !slot.handle.belongsTo(
                stamp.getResourceGeneration(), stamp.getGlContextGeneration())) {
                slot.poisoned = true;
                acquireIndirectFailureReason =
                    TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION;
                recordIndirectFailure(new IllegalStateException(
                    "stale terrain indirect command buffer"));
                return null;
            }
            if (slot.fence != null) {
                boolean ready;
                try { ready = slot.fence.isSignaled(); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    acquireIndirectFailureReason =
                        TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE;
                    recordIndirectFailure(error);
                    FatalErrors.rethrowIfFatal(error);
                    return null;
                }
                if (!ready) {
                    slot.polls++;
                    busyFences++;
                    if (slot.polls >= MAX_FENCE_POLLS) {
                        slot.poisoned = true;
                        acquireIndirectFailureReason =
                            TerrainIndirectReason.FENCE_TIMEOUT;
                        recordIndirectFailure(new IllegalStateException(
                            "terrain indirect Fence timeout"));
                        return null;
                    }
                    continue;
                }
                try { destroySlotFence(slot); }
                catch (Throwable error) {
                    slot.poisoned = true;
                    acquireIndirectFailureReason =
                        TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE;
                    recordIndirectFailure(error);
                    FatalErrors.rethrowIfFatal(error);
                    return null;
                }
                slot.polls = 0;
            }
            indirectCursor = (index + 1) % indirectSlots.length;
            return slot;
        }
        acquireIndirectFailureReason =
            TerrainIndirectReason.SLOT_UNAVAILABLE_OR_BUSY;
        return null;
    }

    private static void destroySlotFence(IndirectSlot slot) {
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

    private boolean ensureIndirectSlotCapacity(IndirectSlot slot, int required,
                                               FrameStamp stamp,
                                               int previousIndirect) {
        slotCapacityFailureReason = null;
        if (slot.handle != null && slot.capacity >= required) return true;
        if (slot.handle != null) {
            RenderHandle retiring = slot.handle;
            int retiringId = retiring.getNativeId();
            if (previousIndirect == retiringId) {
                slot.poisoned = true;
                slotCapacityFailureReason =
                    TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION;
                recordIndirectFailure(new IllegalStateException(
                    "refusing to resize the currently bound terrain indirect buffer"));
                return false;
            }
            try {
                resources.retire(retiring, null);
            } catch (Throwable retirementFailure) {
                slot.poisoned = true;
                slotCapacityFailureReason =
                    TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION;
                recordIndirectFailure(retirementFailure);
                FatalErrors.rethrowIfFatal(retirementFailure);
                return false;
            }
            slot.handle = null;
            slot.capacity = 0;
            try {
                resources.collect(stamp.getGlContextGeneration(), 4);
            } catch (Throwable deletionFailure) {
                // The ledger consumed the handle and permanently retains its
                // budget reservation after an outcome-uncertain delete. Do
                // not publish a replacement from this poisoned MDI slot.
                slot.poisoned = true;
                slotCapacityFailureReason =
                    TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE;
                if (previousIndirect == retiringId) {
                    EarlyGlStateTracker.invalidate();
                }
                recordIndirectFailure(deletionFailure);
                FatalErrors.rethrowIfFatal(deletionFailure);
                return false;
            }
        }
        int target = 256 * INDIRECT_COMMAND_BYTES;
        int maximum = MAX_VISIBLE_MESHES * INDIRECT_COMMAND_BYTES;
        while (target < required && target < maximum) target <<= 1;
        target = Math.min(maximum, Math.max(required, target));
        CacheBudget.Reservation reservation = resources.reserveGpu(target);
        if (reservation == null) {
            slotCapacityFailureReason =
                TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION;
            return false;
        }
        int created = 0;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        boolean bindingTouched = false;
        boolean result = false;
        Throwable failure = null;
        try {
            created = GL15.glGenBuffers();
            allocationReturned = true;
            if (created <= 0) throw new IllegalStateException(
                "terrain indirect glGenBuffers failed");
            nativeNameCreated = true;
            bindingTouched = true;
            bindIndirectBuffer(created);
            GL15.glBufferData(DRAW_INDIRECT_BUFFER, (long) target,
                GL15.GL_STREAM_DRAW);
            RenderHandle handle = resources.registerReserved(
                RenderResourceKind.BUFFER, created, target,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            if (handle != null) {
                created = 0;
                nativeNameCreated = false;
                reservation = null;
                slot.handle = handle;
                slot.capacity = target;
                result = true;
            }
        } catch (Throwable error) {
            failure = error;
        }
        boolean deleteCompleted = false;
        if (nativeNameCreated) {
            try {
                GL15.glDeleteBuffers(created);
                deleteCompleted = true;
            } catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        boolean noNameCreated = allocationReturned && !nativeNameCreated;
        if (reservation != null && (deleteCompleted || noNameCreated)) {
            try { reservation.close(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
        }
        if (bindingTouched) {
            try { bindIndirectBuffer(previousIndirect); }
            catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
                slot.poisoned = true;
                result = false;
            }
        }
        if (failure != null) {
            slotCapacityFailureReason =
                TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE;
            recordIndirectFailure(failure);
            result = false;
            FatalErrors.rethrowIfFatal(failure);
        }
        if (!result && slotCapacityFailureReason == null) {
            slotCapacityFailureReason =
                TerrainIndirectReason.GPU_BUDGET_OR_SLOT_ALLOCATION;
        }
        return result;
    }

    private MeshRecord record(RenderChunk chunk, TerrainLayer expected) {
        if (chunk == null || expected == null) return null;
        VertexBuffer vertexBuffer = chunk.getVertexBufferByLayer(expected.ordinal());
        MeshRecord record = meshes.get(vertexBuffer);
        if (record == null || record.chunk != chunk
            || record.layer != expected
            || !allocator.isLive(record.range)) return null;
        BlockPos position = chunk.getPosition();
        return record.chunkX == position.getX()
            && record.chunkY == position.getY()
            && record.chunkZ == position.getZ() ? record : null;
    }

    private void collectRetired(int maximum, boolean contextValid) {
        int checked = 0;
        Throwable failure = null;
        java.util.Iterator<RetiredRanges> iterator = retired.iterator();
        while (iterator.hasNext() && checked < maximum) {
            RetiredRanges value = iterator.next();
            if (!contextValid) {
                try { value.fence.abandon(); }
                catch (Throwable error) {
                    recordPublicationFailure(error);
                    failure = appendFailure(failure, error);
                }
                iterator.remove();
                continue;
            }
            if (value.poisoned) continue;
            checked++;
            boolean signaled;
            try {
                signaled = value.fence.isSignaled();
            } catch (Throwable failedFence) {
                // Keep the non-retryable Fence wrapper as the ownership
                // witness until Context loss; the ranges themselves are
                // permanently stranded under the fixed arena capacity.
                busyFences++;
                strand(value.ranges);
                value.ranges.clear();
                value.poisoned = true;
                recordPublicationFailure(failedFence);
                failure = appendFailure(failure, failedFence);
                continue;
            }
            if (!signaled) {
                busyFences++;
                value.polls++;
                if (value.polls >= MAX_FENCE_POLLS) {
                    Throwable timeout = new IllegalStateException(
                        "terrain retirement Fence timeout");
                    strand(value.ranges);
                    value.ranges.clear();
                    try { value.fence.destroy(); }
                    catch (Throwable cleanupFailure) {
                        timeout = appendFailure(timeout, cleanupFailure);
                        value.poisoned = true;
                    }
                    if (!value.poisoned) iterator.remove();
                    recordPublicationFailure(timeout);
                    failure = appendFailure(failure, timeout);
                }
                continue;
            }
            Throwable releaseFailure = null;
            boolean fenceReleased = false;
            try { value.fence.destroy(); }
            catch (Throwable error) {
                releaseFailure = appendFailure(releaseFailure, error);
            }
            if (releaseFailure == null) fenceReleased = true;
            for (ArenaRange range : value.ranges) try { allocator.free(range); }
            catch (Throwable error) {
                releaseFailure = appendFailure(releaseFailure, error);
            }
            value.ranges.clear();
            if (fenceReleased) iterator.remove();
            else value.poisoned = true;
            if (releaseFailure != null) {
                recordPublicationFailure(releaseFailure);
                failure = appendFailure(failure, releaseFailure);
            }
        }
        FatalErrors.rethrowIfFatal(failure);
    }

    private Throwable releaseRetiredFences(boolean contextValid,
                                            Throwable failure) {
        java.util.Iterator<RetiredRanges> iterator = retired.iterator();
        while (iterator.hasNext()) {
            RetiredRanges value = iterator.next();
            if (!contextValid) {
                try { value.fence.abandon(); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                }
                iterator.remove();
                continue;
            }
            if (value.poisoned) continue;
            try {
                value.fence.destroy();
                iterator.remove();
            } catch (Throwable error) {
                value.poisoned = true;
                value.ranges.clear();
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private void closeBuffer(boolean contextValid) {
        if (bufferId == 0) return;
        Throwable failure = null;
        if (contextValid) {
            int previous = Integer.MIN_VALUE;
            try {
                previous = preserveArrayBufferBinding();
                bindArrayBuffer(bufferId);
                if (mapped != null
                    && !GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER)) {
                    throw new IllegalStateException(
                        "terrain persistent mapping unmap reported corruption");
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally {
                if (previous != Integer.MIN_VALUE) try {
                    restoreArrayBufferBinding(previous);
                } catch (Throwable error) {
                    EarlyGlStateTracker.invalidate();
                    failure = appendFailure(failure, error);
                }
            }
            if (bufferHandle != null) try {
                resources.retire(bufferHandle,
                    LwjglRetirementFence.afterCurrentCommands(resources));
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        bufferId = 0;
        bufferHandle = null;
        mapped = null;
        persistent = false;
        mappingPoisoned = false;
        mappingFailure = null;
        lastUploadPersistent = false;
        contextCapabilities = null;
        if (failure != null) rethrow(failure);
    }

    private void closeStaging() {
        Throwable failure = null;
        staging = null;
        CacheBudget.Reservation reservation = stagingReservation;
        stagingReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        fallbackCommandBytes = null;
        firstCommands = null;
        countCommands = null;
        reservation = fallbackCommandReservation;
        fallbackCommandReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        indirectCommands = null;
        reservation = indirectCommandReservation;
        indirectCommandReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    private ByteBuffer normalizeIntoStaging(ByteBuffer source, int sourceBytes,
                                            int sourceStride, int bytes,
                                            BlockPos chunk, int regionX,
                                            int regionY, int regionZ) {
        if (staging == null || staging.capacity() < bytes) {
            throw new IllegalStateException("terrain staging budget");
        }
        ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
        input.position(0);
        input.limit(sourceBytes);
        staging.clear();
        staging.limit(bytes);
        if (sourceStride == STRIDE) {
            staging.put(input);
        } else {
            int vertices = sourceBytes / sourceStride;
            for (int vertex = 0; vertex < vertices; vertex++) {
                int offset = vertex * sourceStride;
                ByteBuffer prefix = input.duplicate().order(ByteOrder.nativeOrder());
                prefix.position(offset);
                prefix.limit(offset + STRIDE);
                staging.put(prefix);
            }
        }
        ByteBuffer output = staging.duplicate().order(ByteOrder.nativeOrder());
        float dx = chunk.getX() - regionX;
        float dy = chunk.getY() - regionY;
        float dz = chunk.getZ() - regionZ;
        for (int offset = 0; offset < bytes; offset += STRIDE) {
            output.putFloat(offset, output.getFloat(offset) * MODEL_SCALE
                + MODEL_TRANSLATION + dx);
            output.putFloat(offset + 4, output.getFloat(offset + 4) * MODEL_SCALE
                + MODEL_TRANSLATION + dy);
            output.putFloat(offset + 8, output.getFloat(offset + 8) * MODEL_SCALE
                + MODEL_TRANSLATION + dz);
        }
        output.position(0);
        output.limit(bytes);
        return output;
    }

    static boolean hasVanillaBlockPrefix(VertexFormat format) {
        if (format == null || format.getSize() < STRIDE
            || format.getElementCount() < 4) return false;
        return format.getOffset(0) == 0
            && format.getOffset(1) == 12
            && format.getOffset(2) == 16
            && format.getOffset(3) == 24
            && DefaultVertexFormats.POSITION_3F.equals(format.getElement(0))
            && DefaultVertexFormats.COLOR_4UB.equals(format.getElement(1))
            && DefaultVertexFormats.TEX_2F.equals(format.getElement(2))
            && DefaultVertexFormats.TEX_2S.equals(format.getElement(3));
    }

    static byte[] packVanillaBlockPrefixForTest(byte[] source, int sourceStride) {
        if (source == null || sourceStride < STRIDE
            || source.length == 0 || source.length % sourceStride != 0) {
            throw new IllegalArgumentException("test extended terrain bytes");
        }
        int vertices = source.length / sourceStride;
        byte[] result = new byte[Math.multiplyExact(vertices, STRIDE)];
        for (int vertex = 0; vertex < vertices; vertex++) {
            System.arraycopy(source, vertex * sourceStride, result,
                vertex * STRIDE, STRIDE);
        }
        return result;
    }

    private static TerrainLayer layer(BlockRenderLayer layer) {
        if (layer == null) return null;
        int ordinal = layer.ordinal();
        TerrainLayer[] values = TerrainLayer.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static int regionOrigin(int coordinate) {
        return Math.floorDiv(coordinate, REGION_SIZE) * REGION_SIZE;
    }

    static byte[] normalizeForTest(byte[] source, int chunkX, int chunkY, int chunkZ) {
        if (source == null || source.length % STRIDE != 0) {
            throw new IllegalArgumentException("test terrain bytes");
        }
        ByteBuffer input = ByteBuffer.wrap(source.clone())
            .order(ByteOrder.nativeOrder());
        BlockPos chunk = new BlockPos(chunkX, chunkY, chunkZ);
        byte[] result = new byte[source.length];
        ByteBuffer output = ByteBuffer.wrap(result).order(ByteOrder.nativeOrder());
        output.put(input);
        float dx = chunkX - regionOrigin(chunkX);
        float dy = chunkY - regionOrigin(chunkY);
        float dz = chunkZ - regionOrigin(chunkZ);
        for (int offset = 0; offset < result.length; offset += STRIDE) {
            output.putFloat(offset, output.getFloat(offset) * MODEL_SCALE
                + MODEL_TRANSLATION + dx);
            output.putFloat(offset + 4, output.getFloat(offset + 4) * MODEL_SCALE
                + MODEL_TRANSLATION + dy);
            output.putFloat(offset + 8, output.getFloat(offset + 8) * MODEL_SCALE
                + MODEL_TRANSLATION + dz);
        }
        return result;
    }

    static int regionOriginForTest(int coordinate) { return regionOrigin(coordinate); }

    private long nextSequence() {
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("terrain sequence exhausted");
        }
        return nextSequence++;
    }

    private static int preserveArrayBufferBinding() {
        int tracked = EarlyGlStateTracker.arrayBufferBinding();
        if (tracked != Integer.MIN_VALUE) return tracked;
        // This is a cold fault-only path. A read-only query has a deterministic
        // outcome; glPushClientAttrib could throw after changing the native
        // stack and would leave no reliable way to decide whether to pop it.
        return GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    }

    private static void restoreArrayBufferBinding(int saved) {
        try {
            bindArrayBuffer(saved);
        } catch (Throwable error) {
            EarlyGlStateTracker.invalidate();
            throw error;
        }
    }

    private static void bindArrayBuffer(int nativeId) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
    }

    private static void bindIndirectBuffer(int nativeId) {
        GL15.glBindBuffer(DRAW_INDIRECT_BUFFER, nativeId);
        EarlyGlStateTracker.bindBuffer(DRAW_INDIRECT_BUFFER, nativeId);
    }

    private void strandPendingRanges() {
        while (!pendingRetirement.isEmpty()) {
            ArenaRange range = pendingRetirement.removeFirst();
            strandedRanges = safeAdd(strandedRanges, 1L);
            strandedBytes = safeAdd(strandedBytes, range.getLength());
        }
    }

    private void retireOrStrand(ArenaRange range) {
        if (range == null) return;
        try {
            pendingRetirement.addLast(range);
        } catch (Throwable error) {
            recordPublicationFailure(error);
            strandRange(range);
            FatalErrors.rethrowIfFatal(error);
        }
    }

    private Throwable rollbackFailedUpload(VertexBuffer vertexBuffer,
                                            MeshRecord replacement,
                                            MeshRecord previous,
                                            boolean putReturned,
                                            boolean previousRetired,
                                            ArenaRange range,
                                            Throwable failure) {
        boolean registryKnown = false;
        MeshRecord actual = null;
        try {
            actual = meshes.get(vertexBuffer);
            registryKnown = true;
        } catch (Throwable inspectionFailure) {
            failure = appendFailure(failure, inspectionFailure);
            recordPublicationFailure(inspectionFailure);
        }

        if (!previousRetired && previous != null && registryKnown
            && actual != previous && (putReturned || actual == replacement)) {
            retireOrStrand(previous.range);
            previousRetired = true;
        }

        boolean replacementRemoved = false;
        if (registryKnown && replacement != null && actual == replacement) {
            try {
                MeshRecord removed = meshes.remove(vertexBuffer);
                replacementRemoved = removed == replacement;
                if (!replacementRemoved && removed != null) {
                    // A render-thread-only registry can never legitimately
                    // change here. Restore the unexpected mapping if possible.
                    meshes.put(vertexBuffer, removed);
                }
            } catch (Throwable removalFailure) {
                failure = appendFailure(failure, removalFailure);
                recordPublicationFailure(removalFailure);
            }
        } else if (registryKnown) {
            replacementRemoved = true;
        }

        if (replacementRemoved) {
            try {
                if (!allocator.free(range) && allocator.isLive(range)) {
                    throw new IllegalStateException(
                        "unpublished terrain range remained live");
                }
            } catch (Throwable freeFailure) {
                failure = appendFailure(failure, freeFailure);
                recordPublicationFailure(freeFailure);
                // free() has an outcome-uncertain boundary.  A second
                // allocator inspection could both replace the primary error
                // and tempt a double-free.  Conservatively account the range
                // as stranded without touching allocator state again.
                strandRange(range);
            }
        } else {
            // The mapping could still expose this range. Never recycle it;
            // the bridge will make one more non-throwing release attempt.
            strandRange(range);
        }
        return failure;
    }

    private void strandRange(ArenaRange range) {
        if (range == null) return;
        strandedRanges = safeAdd(strandedRanges, 1L);
        strandedBytes = safeAdd(strandedBytes, range.getLength());
    }

    private void strand(List<ArenaRange> ranges) {
        for (ArenaRange range : ranges) {
            if (range == null) continue;
            strandedRanges = safeAdd(strandedRanges, 1L);
            strandedBytes = safeAdd(strandedBytes, range.getLength());
        }
    }

    private void recordPublicationFailure(Throwable error) {
        if (error == null) return;
        publicationFailure = appendFailure(publicationFailure, error);
    }

    private void closeIndirectSlots(boolean contextValid) {
        Throwable failure = null;
        for (IndirectSlot slot : indirectSlots) {
            try {
                if (contextValid && slot.handle != null) {
                    // The ledger owns the Fence even when the handle is stale.
                    ResourceLedger.RetirementFence fence = slot.fence;
                    slot.fence = null;
                    resources.retire(slot.handle, fence);
                } else if (contextValid && slot.fence != null) {
                    destroySlotFence(slot);
                } else if (!contextValid && slot.fence != null) {
                    LwjglRetirementFence.abandon(slot.fence);
                }
                if (!contextValid && slot.uncertainFence != null) {
                    LwjglRetirementFence.abandon(slot.uncertainFence);
                    slot.uncertainFence = null;
                }
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            } finally {
                slot.clear(contextValid);
            }
        }
        indirectCursor = 0;
        indirectFailure = null;
        if (failure != null) rethrow(failure);
    }

    private void recordIndirectFailure(Throwable error) {
        if (error == null) return;
        indirectFailure = appendFailure(indirectFailure, error);
    }

    private void recordIndirectFallback(TerrainIndirectReason reason) {
        indirectFallbacks = safeAdd(indirectFallbacks, 1L);
        recordIndirectReason(reason);
    }

    private void recordIndirectReason(TerrainIndirectReason reason) {
        TerrainIndirectReason actual = reason == null
            ? TerrainIndirectReason.DRIVER_OR_SUBMISSION_FAILURE : reason;
        int index = actual.ordinal();
        indirectReasons[index] = safeAdd(indirectReasons[index], 1L);
    }

    private void recordMappingFailure(Throwable error) {
        if (error == null) return;
        mappingFailure = appendFailure(mappingFailure, error);
    }

    private static int checkedInt(long value) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new ArithmeticException("terrain GL offset overflow");
        }
        return (int) value;
    }

    static void putIndirectCommand(ByteBuffer target, int vertexCount,
                                   long byteOffset) {
        if (byteOffset < 0L || byteOffset % STRIDE != 0L) {
            throw new IllegalArgumentException("terrain indirect command");
        }
        putIndirectCommand(target, vertexCount,
            checkedInt(byteOffset / STRIDE), 0);
    }

    private static void putIndirectCommand(ByteBuffer target, int vertexCount,
                                           int firstVertex,
                                           int baseInstance) {
        if (target == null || target.remaining() < INDIRECT_COMMAND_BYTES
            || vertexCount < 0 || firstVertex < 0 || baseInstance < 0) {
            throw new IllegalArgumentException("terrain indirect command");
        }
        target.putInt(vertexCount);
        target.putInt(1);
        target.putInt(firstVertex);
        target.putInt(baseInstance);
    }

    private static long checksum(ByteBuffer value, int length) {
        long result = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            result ^= value.get(i) & 0xffL;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long checksum(byte[] value) {
        long result = 0xcbf29ce484222325L;
        for (byte current : value) {
            result ^= current & 0xffL;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
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
        throw new IllegalStateException("terrain resource cleanup failed", failure);
    }

    private static RuntimeException propagate(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        return new IllegalStateException("terrain operation failed", failure);
    }

    private static final class MeshRecord {
        private final RenderChunk chunk;
        private final int regionX;
        private final int regionY;
        private final int regionZ;
        private final int chunkX;
        private final int chunkY;
        private final int chunkZ;
        private final TerrainLayer layer;
        private final int vertexCount;
        private final int byteCount;
        private final long checksum;
        private final FrameStamp generation;
        private final ArenaRange range;
        private final TerrainMesh mesh;
        private final boolean persistentUpload;
        private final boolean retainLegacyCopy;
        private boolean legacyUploadExpected;
        private long lastDrawFrame;

        private MeshRecord(RenderChunk chunk, int regionX, int regionY, int regionZ,
                           FrameStamp generation, TerrainMesh mesh,
                           boolean persistentUpload,
                           boolean retainLegacyCopy) {
            this.chunk = chunk;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.chunkX = mesh.getChunkX();
            this.chunkY = mesh.getChunkY();
            this.chunkZ = mesh.getChunkZ();
            this.layer = mesh.getLayer();
            this.vertexCount = mesh.getVertexCount();
            this.byteCount = Math.toIntExact(mesh.getRange().getLength());
            this.checksum = mesh.getChecksum();
            this.generation = generation;
            this.range = mesh.getRange();
            this.mesh = mesh;
            this.persistentUpload = persistentUpload;
            this.retainLegacyCopy = retainLegacyCopy;
            this.legacyUploadExpected = retainLegacyCopy;
        }

        private boolean sameRegion(MeshRecord other) {
            return other != null && regionX == other.regionX
                && regionY == other.regionY && regionZ == other.regionZ;
        }

        private boolean matchesGeneration(FrameStamp current) {
            if (current == null) return false;
            return generation.getWorldGeneration() == current.getWorldGeneration()
                && generation.getResourceGeneration() == current.getResourceGeneration()
                && generation.getGlContextGeneration() == current.getGlContextGeneration()
                && generation.getShaderPackGeneration() == current.getShaderPackGeneration()
                && generation.getShaderPermutationGeneration()
                    == current.getShaderPermutationGeneration()
                && generation.getVertexFormatGeneration()
                    == current.getVertexFormatGeneration()
                && generation.getViewFrustumGeneration()
                    == current.getViewFrustumGeneration();
        }
    }

    private static final class RetiredRanges {
        private final List<ArenaRange> ranges;
        private final LwjglRetirementFence fence;
        private int polls;
        private boolean poisoned;
        private RetiredRanges(List<ArenaRange> ranges,
                              LwjglRetirementFence fence) {
            this.ranges = ranges;
            this.fence = fence;
        }
    }

    private static final class IndirectSlot {
        private RenderHandle handle;
        private int capacity;
        private ResourceLedger.RetirementFence fence;
        private ResourceLedger.RetirementFence uncertainFence;
        private int polls;
        private boolean poisoned;

        private int nativeId() {
            return handle == null ? 0 : handle.getNativeId();
        }

        private void clear(boolean preserveUncertain) {
            handle = null;
            capacity = 0;
            fence = null;
            polls = 0;
            if (!preserveUncertain) uncertainFence = null;
            poisoned = preserveUncertain && uncertainFence != null;
        }
    }

    /** Deliberately strands a possibly-consumed command buffer. */
    private enum NeverReadyFence implements ResourceLedger.RetirementFence {
        INSTANCE;
        @Override public boolean isSignaled() { return false; }
        @Override public void destroy() { }
    }
}

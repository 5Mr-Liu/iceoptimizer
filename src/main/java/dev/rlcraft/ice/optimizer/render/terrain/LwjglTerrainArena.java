package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkVertexBufferAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
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
import dev.rlcraft.ice.optimizer.render.visibility.CurrentFrameTerrainOcclusion;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;
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
import org.lwjgl.opengl.ARBCopyBuffer;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
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
    private static final TerrainLayer[] TERRAIN_LAYERS = TerrainLayer.values();
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
    private CurrentFrameTerrainOcclusion visibilitySource;
    private RenderMatrixBridge.Snapshot visibilityMatrices;
    private boolean nativeShaderBatching;
    private boolean directionalCulling;
    private boolean cpuDirection;
    private ByteBuffer faceStaging;
    private CacheBudget.Reservation faceStagingReservation;
    private final double[] directionalEye = new double[3];
    private final float[] directionalView = new float[16];
    private final FloatBuffer directionalViewBuffer = BufferUtils.createFloatBuffer(16);
    private long shaderBatchDrawCalls;
    private long shaderBatchCommands;
    private long directionalCulledVertices;
    private long shaderBatchFallbacks;
    private long animatorBatchedMeshes;
    private final TerrainCommandGenerator commandGenerator =
        new TerrainCommandGenerator();
    private int commandPageFirst;
    private final TerrainCommandGenerator.CommandSink fallbackCommandSink =
        new TerrainCommandGenerator.CommandSink() {
            @Override public void accept(int count, int first, int baseInstance,
                                         int originX, int originY, int originZ,
                                         long sequence, long checksum) {
                firstCommands.put(first - commandPageFirst);
                countCommands.put(count);
            }
        };
    private final TerrainCommandGenerator.CommandSink indirectCommandSink =
        new TerrainCommandGenerator.CommandSink() {
            @Override public void accept(int count, int first, int baseInstance,
                                         int originX, int originY, int originZ,
                                         long sequence, long checksum) {
                putIndirectCommand(indirectCommands, count,
                    first - commandPageFirst, baseInstance);
            }
        };
    private final long capacityBytes;
    private final long pageBytes;
    private final BufferPage[] bufferPages;
    private final boolean[] failedAllocationPages;
    private final long[] allocationPageCapacities;
    private final IdentityHashMap<VertexBuffer, MeshRecord> meshes =
        new IdentityHashMap<VertexBuffer, MeshRecord>();
    private final ArrayDeque<ArenaRange> pendingRetirement;
    private final ArrayDeque<RetiredRanges> retired =
        new ArrayDeque<RetiredRanges>();
    private GpuArenaAllocator allocator;
    private long arenaGeneration;
    private int bufferId;
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
    private long exactSizeReplacementUploads;
    private long rejectedUploads;
    private long busyFences;
    private long invalidPayloads;
    private long drawCalls;
    private long arrayLayoutSets;
    private long arrayLayoutReuses;
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
    private final LwjglTerrainUploadStream uploadStream;
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
        this.uploadStream = new LwjglTerrainUploadStream(resources);
        this.budget = budget;
        this.capabilities = capabilities;
        this.pendingRetirement = pendingRetirement;
        long bounded = Math.max(16L * 1024L * 1024L, requestedCapacity);
        bounded = Math.min((long) Integer.MAX_VALUE - STRIDE, bounded);
        long nominalPage = 16L * 1024L * 1024L;
        // A complete quad is 112 bytes. Every page starts on a quad boundary
        // and can contain the largest accepted upload without spanning VBOs.
        pageBytes = nominalPage - nominalPage % (STRIDE * 4L);
        capacityBytes = Math.max(pageBytes, bounded - bounded % pageBytes);
        bufferPages = new BufferPage[(int) (capacityBytes / pageBytes)];
        failedAllocationPages = new boolean[bufferPages.length];
        allocationPageCapacities = new long[bufferPages.length];
        arenaGeneration = generation;
        allocator = new GpuArenaAllocator(pageBytes, capacityBytes, 4L,
            generation, budget, pageBytes, GpuArenaAllocator.rangeHeapBytes() + 128L);
        for (int i = 0; i < indirectSlots.length; i++) {
            indirectSlots[i] = new IndirectSlot();
        }
    }

    public void configureNativeBatching(boolean enabled, boolean cullFaces) {
        guard.check();
        nativeShaderBatching = enabled;
        directionalCulling = cullFaces;
    }

    public void configureVisibility(FrameStamp frame, CurrentFrameTerrainOcclusion source,
                                    RenderMatrixBridge.Snapshot matrices) {
        visibilitySource = source;
        visibilityMatrices = matrices;
    }

    /** Existing upload hook invalidates addresses while retaining reusable vertex layouts. */
    public void beforeBufferStorageReplacement(VertexBuffer vertexBuffer) {
        guard.check();
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
        TerrainVertexSpace vertexSpace = TerrainVertexSpace.capture(builder, context.getChunk());
        if (vertexSpace == null) {
            invalidPayloads++;
            return false;
        }
        if (!ensureStaging(bytes)) {
            rejectedUploads++;
            return false;
        }
        MeshRecord previous = meshes.get(vertexBuffer);
        ArenaRange range = allocateUploadRange(bytes, previous, stamp);
        if (range == null) {
            rejectedUploads++;
            return false;
        }
        boolean reused = previous != null && range == previous.range;

        BlockPos position = context.getChunk().getPosition();
        int regionX = regionOrigin(position.getX());
        int regionY = regionOrigin(position.getY());
        int regionZ = regionOrigin(position.getZ());
        MeshRecord replacement = null;
        boolean putReturned = false;
        boolean previousRetired = false;
        try {
            ByteBuffer normalized = normalizeIntoStaging(source, sourceBytes,
                stride, bytes, vertexSpace.originX, vertexSpace.originY, vertexSpace.originZ,
                vertexSpace.scale, vertexSpace.translation, regionX, regionY, regionZ, staging);
            TerrainFaceRanges faces = null;
            // Only opaque ordinary terrain may be reordered. A ShaderPack
            // shadow copy and every translucent mesh retain their exact order.
            if (nativeShaderBatching && directionalCulling && !retainLegacyCopy
                && layer == TerrainLayer.SOLID && ensureFaceStaging(bytes)) {
                faces = TerrainFaceRanges.group(normalized, faceStaging, bytes);
                normalized = faceStaging;
            }
            lastUploadPersistent = write(range, normalized,
                allowPersistentStorage && preferPersistentWrite);
            TerrainMesh mesh = TerrainMesh.metadataOnly(layer, vertices, STRIDE,
                position.getX(), position.getY(), position.getZ(),
                // Production draws need no diagnostic payload checksum.
                nextSequence(), 0L, range);
            mesh.setFaceRanges(faces);
            replacement = new MeshRecord(context.getChunk(), regionX, regionY,
                regionZ, stamp, mesh, retainLegacyCopy);
            MeshRecord displaced = meshes.put(vertexBuffer, replacement);
            putReturned = true;
            if (displaced != previous) {
                throw new IllegalStateException(
                    "terrain mesh registry changed during publication");
            }
            if (previous != null && !reused) {
                retireOrStrand(previous.range);
                previousRetired = true;
            }
            if (!retainLegacyCopy) builder.reset();
            uploadedBytes = safeAdd(uploadedBytes, bytes);
            if (reused && bytes == range.getLength()) {
                exactSizeReplacementUploads = safeAdd(exactSizeReplacementUploads, 1L);
            }
            if (lastUploadPersistent) {
                persistentUploads = safeAdd(persistentUploads, 1L);
            } else {
                subDataUploads = safeAdd(subDataUploads, 1L);
            }
            return true;
        } catch (Throwable error) {
            Throwable failure = reused
                ? rollbackReusedUpload(vertexBuffer, range, error)
                : rollbackFailedUpload(vertexBuffer, replacement,
                    previous, putReturned, previousRetired, range, error);
            rethrow(failure);
            return false;
        }
    }

    private ArenaRange allocateUploadRange(int bytes, MeshRecord previous,
                                          FrameStamp stamp) {
        // Resident writes are ordered GPU copies or glBufferSubData, never
        // CPU writes into a draw buffer. An equal-size rebuild can keep its
        // address even with queued old draws, avoiding allocation/retirement
        // churn without retaining an oversized tail. Failure uses the existing
        // reused-upload rollback, which discards metadata for changed bytes.
        if (previous != null && bytes == previous.range.getLength()
            && allocator.ownsLiveRange(previous.range)) return previous.range;
        captureAllocationPageCapacities();
        ArenaRange range = allocator.allocatePaged(bytes, allocationPageCapacities, null);
        if (range == null) {
            // Poll only on allocation pressure; these are zero-timeout polls.
            // Finished replacements should be reusable before rejecting a
            // new mesh merely because endFrame has not visited their batch.
            collectRetired(retired.size(), true);
            captureAllocationPageCapacities();
            range = allocator.allocatePaged(bytes, allocationPageCapacities, null);
        }
        boolean retrying = false;
        while (range != null) {
            try {
                if (ensureBuffer(range, stamp)) return range;
            } catch (Throwable error) {
                try { allocator.free(range); }
                catch (Throwable cleanup) { error = appendFailure(error, cleanup); }
                rethrow(error);
            }
            int failedPage = pageIndex(range);
            allocator.free(range);
            if (!retrying) {
                java.util.Arrays.fill(failedAllocationPages, false);
                retrying = true;
            }
            failedAllocationPages[failedPage] = true;
            captureAllocationPageCapacities();
            long available = budget.availableBytes(BudgetKind.GPU);
            if (!contextCapabilities.OpenGL31 && !contextCapabilities.GL_ARB_copy_buffer
                && available < pageBytes) available = 0L;
            range = allocator.allocatePaged(bytes, allocationPageCapacities, failedAllocationPages,
                available);
        }
        // Both GPU copy and glBufferSubData are ordered after queued draws.
        // On a full arena they can replace an equal/smaller mesh in place;
        // no CPU writes touch the draw buffer. Unused tail bytes stay owned.
        return previous != null && bytes <= previous.range.getLength()
            ? previous.range : null;
    }

    private void captureAllocationPageCapacities() {
        for (int page = 0; page < bufferPages.length; page++) {
            BufferPage buffer = bufferPages[page];
            allocationPageCapacities[page] = buffer == null ? 0L : buffer.capacity;
        }
    }

    private Throwable rollbackReusedUpload(VertexBuffer vertexBuffer,
                                             ArenaRange range,
                                             Throwable failure) {
        // The queued update may have changed old bytes already: discard its
        // metadata rather than presenting an old mesh with new vertex data.
        try {
            meshes.remove(vertexBuffer);
            retireOrStrand(range);
        } catch (Throwable cleanup) {
            failure = appendFailure(failure, cleanup);
            strandRange(range);
        }
        return failure;
    }

    public void release(VertexBuffer vertexBuffer) {
        guard.check();
        if (vertexBuffer == null) return;
        try {
        } finally { releaseMesh(vertexBuffer); }
    }

    private void releaseMesh(VertexBuffer vertexBuffer) {
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
        beforeBufferStorageReplacement(vertexBuffer);
        MeshRecord record = meshes.get(vertexBuffer);
        if (record != null && record.retainLegacyCopy
            && record.legacyUploadExpected) {
            record.legacyUploadExpected = false;
            return true;
        }
        // Replacing this buffer's storage leaves its name and VAO layout valid.
        // Deletion, unlike bufferData, goes through release() above.
        releaseMesh(vertexBuffer);
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
        if (!(container instanceof TerrainRenderListAccessor)) return false;
        TerrainRenderListAccessor accessor = (TerrainRenderListAccessor) container;
        if (!accessor.ice$initialized()) return false;
        TerrainLayer expected = layer(blockLayer);
        if (expected == null) return false;
        List<RenderChunk> visible = accessor.ice$renderChunks();
        if (!ownsAny(container, blockLayer)) return false;
        // Direction tests are once per layer, then O(1) per face range. The
        // ordinary fixed pipeline retains exact fog/lightmap/model semantics.
        cpuDirection = batchMappedRanges && nativeShaderBatching && directionalCulling
            && expected == TerrainLayer.SOLID && directionStateSafe()
            && captureDirectionalEye();
        int previousArrayBuffer = preserveArrayBufferBinding();
        int arrayLayoutBuffer = 0;
        int index = 0;
        boolean completed = false;
        Throwable failure = null;
        try {
            if (visibilitySource != null && blockLayer == BlockRenderLayer.SOLID)
                visibilitySource.filterAdaptive(container, blockLayer, visibilityMatrices);
            while (index < visible.size()) {
                RenderChunk chunk = visible.get(index);
                MeshRecord record = record(chunk, expected);
                if (record == null) {
                    arrayLayoutBuffer = 0;
                    index = drawLegacyRun(visible, index, blockLayer, accessor);
                    continue;
                }
                if (ChunkAnimatorRenderBridge.requiresCompatibilityDraw(chunk)) {
                    arrayLayoutBuffer = 0;
                    drawMappedWithCompatibilityTransform(chunk, record, accessor,
                        frameId);
                    index++;
                    continue;
                }
                if (batchMappedRanges && nativeShaderBatching
                    && chunk.getClass() == RenderChunk.class) {
                    int regionEnd = index + 1;
                    while (regionEnd < visible.size()) {
                        RenderChunk nextChunk = visible.get(regionEnd);
                        // Do not call an unknown virtual getter while a shared
                        // transform is active, or cross a compatibility draw.
                        if (nextChunk.getClass() != RenderChunk.class) break;
                        MeshRecord next = record(nextChunk, expected);
                        if (next == null || !record.sameRegion(next)
                            || ChunkAnimatorRenderBridge.requiresCompatibilityDraw(nextChunk)) break;
                        regionEnd++;
                    }
                    if (regionEnd > index + 1) {
                        arrayLayoutBuffer = drawMappedRegion(visible, index, regionEnd,
                            expected, accessor, frameId, record, arrayLayoutBuffer);
                        index = regionEnd;
                        continue;
                    }
                }
                int end = index + 1;
                // Unknown virtual getters can mutate GL state during a scan.
                // Reuse layouts only across exact vanilla chunk instances.
                boolean reusableLayout = chunk.getClass() == RenderChunk.class;
                if (batchMappedRanges) {
                    while (end < visible.size()) {
                        RenderChunk nextChunk = visible.get(end);
                        if (nextChunk.getClass() != RenderChunk.class) reusableLayout = false;
                        MeshRecord next = record(nextChunk, expected);
                        if (next == null || !record.sameRegion(next)
                            || pageIndex(record.range) != pageIndex(next.range)
                            || ChunkAnimatorRenderBridge
                                .requiresCompatibilityDraw(nextChunk)) break;
                        end++;
                    }
                }
                drawMappedRun(visible, index, end, expected, accessor,
                    useIndirect && !nativeShaderBatching, frameId,
                    reusableLayout ? arrayLayoutBuffer : 0);
                arrayLayoutBuffer = reusableLayout ? nativeBuffer(record.range) : 0;
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

    public void endFrame(boolean contextValid) {
        guard.check();
        if (contextValid) try { uploadStream.endFrame(); }
        catch (Throwable error) {
            mappingPoisoned = true;
            recordMappingFailure(error);
            FatalErrors.rethrowIfFatal(error);
        }
        collectRetired(8, contextValid);
        if (contextValid) retireEmptyPages();
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
        visibilitySource = null; visibilityMatrices = null;
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
        visibilitySource = null; visibilityMatrices = null;
        try { closeBuffer(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { closeStaging(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        try { allocator.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public long getUploadedBytes() { return uploadedBytes; }
    public long getExactSizeReplacementUploads() { return exactSizeReplacementUploads; }
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
    public long getMaximumCapacityBytes() { return capacityBytes; }
    public long getAllocatedBufferBytes() {
        guard.check();
        long bytes = 0L;
        for (BufferPage page : bufferPages) if (page != null) bytes += page.capacity;
        return bytes;
    }
    public long getBusyFences() { return busyFences; }
    public long getDrawCalls() { return drawCalls; }
    public long getArrayLayoutSets() { return arrayLayoutSets; }
    public long getArrayLayoutReuses() { return arrayLayoutReuses; }
    public long getShaderBatchDrawCalls() { return shaderBatchDrawCalls; }
    public long getShaderBatchCommands() { return shaderBatchCommands; }
    public long getDirectionalCulledVertices() { return directionalCulledVertices; }
    public long getShaderBatchFallbacks() { return shaderBatchFallbacks; }
    public long getAnimatorBatchedMeshes() { return animatorBatchedMeshes; }
    public String getShaderBatchDetail() {
        return shaderBatchDrawCalls == 0L ? "not-initialized"
            : nativeShaderBatching ? "ordered-fixed-ranges" : "legacy-arena";
    }
    public long getGpuVisibilityFrames() { return 0L; }
    public long getGpuVisibilityCandidates() { return 0L; }
    public long getGpuVisibilityFaces() { return 0L; }
    public Throwable consumeShaderBatchFailure() { return null; }
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
    /** On-demand diagnostics; never scanned on the per-frame draw path. */
    public long getLiveVertexBytes() {
        long bytes = 0L;
        for (MeshRecord record : meshes.values()) bytes += (long) record.vertexCount * STRIDE;
        return bytes;
    }
    public long getLiveUnusedTailBytes() {
        long bytes = 0L;
        for (MeshRecord record : meshes.values())
            bytes += record.range.getLength() - (long) record.vertexCount * STRIDE;
        return bytes;
    }
    public Throwable consumePublicationFailure() {
        Throwable result = publicationFailure;
        publicationFailure = null;
        return result;
    }
    void retireRangeForTest(ArenaRange range) { retireOrStrand(range); }
    public boolean isPersistent() { return uploadStream.isMapped(); }
    public long getUploadStagingBytes() { return uploadStream.allocatedBytes(); }
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

    private boolean ensureBuffer(ArenaRange range, FrameStamp stamp) {
        int pageIndex = pageIndex(range);
        BufferPage oldPage = bufferPages[pageIndex];
        long required = pageOffset(range) + range.getLength();
        if (oldPage != null && oldPage.capacity >= required) return true;
        contextCapabilities = GLContext.getCapabilities();
        boolean canCopy = contextCapabilities.OpenGL31 || contextCapabilities.GL_ARB_copy_buffer;
        long physicalBytes = physicalPageCapacity(required, pageBytes, canCopy);
        int previous = preserveArrayBufferBinding();
        CacheBudget.Reservation reservation = null;
        int created = 0;
        boolean allocationReturned = false;
        boolean nativeNameCreated = false;
        boolean result = false;
        Throwable failure = null;
        try {
            reservation = resources.reserveGpu(physicalBytes);
            if (reservation == null && canCopy && required < physicalBytes) {
                long fitted = physicalPageCapacityUnderBudget(required, physicalBytes,
                    budget.availableBytes(BudgetKind.GPU));
                if (fitted > 0L && fitted < physicalBytes) {
                    reservation = resources.reserveGpu(fitted);
                    if (reservation != null) physicalBytes = fitted;
                }
            }
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
            // Long-lived vertex data is never CPU-mapped. On the tested driver
            // mapped terrain was much slower to draw, even between uploads.
            // CPU-visible storage belongs only to the bounded upload stream.
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, physicalBytes, GL15.GL_STATIC_DRAW);
            if (oldPage != null) copyPage(oldPage);
            RenderHandle registered = resources.registerReserved(
                RenderResourceKind.BUFFER, created, physicalBytes,
                stamp.getResourceGeneration(), stamp.getGlContextGeneration(),
                reservation);
            if (registered != null) {
                // Registration transfers exclusive ownership before publication.
                int publishedId = created;
                created = 0;
                nativeNameCreated = false;
                reservation = null;
                bufferPages[pageIndex] = new BufferPage(publishedId, registered, physicalBytes);
                bufferId = publishedId;
                result = true;
                if (oldPage != null) failure = retirePage(oldPage, failure);
            }
            }
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        boolean deleteCompleted = false;
        if (nativeNameCreated) {
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

    static long physicalPageCapacity(long required, long maximum) {
        return physicalPageCapacity(required, maximum, true);
    }

    static long physicalPageCapacity(long required, long maximum, boolean canCopy) {
        if (required <= 0L || required > maximum) throw new IllegalArgumentException("terrain page extent");
        // VBO + ARB_sync contexts predate buffer copying. Keep their original
        // physical page size so later uploads never require an unavailable API.
        if (!canCopy) return maximum;
        long size = Math.min(256L * 1024L, maximum);
        while (size < required) size = Math.min(size << 1, maximum);
        return size;
    }

    /** Fit real spare payload space without dropping the old in-flight page. */
    static long physicalPageCapacityUnderBudget(long required, long preferred, long available) {
        long capacity = Math.min(preferred, available);
        if (capacity < required) return 0L;
        long aligned = capacity - capacity % (256L * 1024L);
        return aligned >= required ? aligned : required;
    }

    /** Destination is the new GL_ARRAY_BUFFER; both storage names remain owned. */
    private void copyPage(BufferPage source) {
        int previous = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        Throwable failure = null;
        try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, source.nativeId);
            if (contextCapabilities.OpenGL31) {
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL15.GL_ARRAY_BUFFER,
                    0L, 0L, source.capacity);
            } else {
                ARBCopyBuffer.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL15.GL_ARRAY_BUFFER,
                    0L, 0L, source.capacity);
            }
        } catch (Throwable error) { failure = error; }
        finally {
            try { GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, previous); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) rethrow(failure);
    }

    private boolean write(ArenaRange range, ByteBuffer normalized,
                          boolean preferPersistentWrite) {
        BufferPage page = bufferPages[pageIndex(range)];
        int offset = pageOffset(range);
        if (preferPersistentWrite && !mappingPoisoned
            && capabilities.passed(ModernCapability.PERSISTENT_MAPPING)
            && (contextCapabilities.OpenGL31 || contextCapabilities.GL_ARB_copy_buffer)) {
            try {
                if (uploadStream.upload(page.handle, offset, normalized)) return true;
            } catch (Throwable error) {
                // The unpublished destination can safely receive the same
                // bytes again through the ordered GL fallback.
                mappingPoisoned = true;
                recordMappingFailure(error);
                EarlyGlStateTracker.invalidate();
                FatalErrors.rethrowIfFatal(error);
            }
        }
        int previous = preserveArrayBufferBinding();
        Throwable failure = null;
        try {
            bindArrayBuffer(page.nativeId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset,
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

    private boolean ensureFaceStaging(int required) {
        if (required <= 0 || required > MAX_UPLOAD_BYTES) return false;
        if (faceStaging != null && faceStaging.capacity()
            >= TerrainFaceRanges.storageBytes(required)) return true;
        int capacity = 64 * 1024;
        while (capacity < required) capacity <<= 1;
        capacity = TerrainFaceRanges.storageBytes(capacity);
        CacheBudget.Reservation reservation = budget.tryReserve(BudgetKind.DIRECT, capacity);
        if (reservation == null) return false;
        ByteBuffer replacement;
        try { replacement = BufferUtils.createByteBuffer(capacity).order(ByteOrder.nativeOrder()); }
        catch (Throwable error) {
            try { reservation.close(); }
            catch (Throwable cleanup) { error = appendFailure(error, cleanup); }
            rethrow(error);
            return false;
        }
        CacheBudget.Reservation previous = faceStagingReservation;
        faceStaging = replacement;
        faceStagingReservation = reservation;
        if (previous != null) previous.close();
        return true;
    }

    private static boolean directionStateSafe() {
        EarlyGlStateTracker.CompatibilitySnapshot state = EarlyGlStateTracker.compatibilitySnapshot();
        int program = EarlyGlStateTracker.modelProgramBinding();
        if (program == Integer.MIN_VALUE) program = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        return program == 0 && (state != null
            ? state.isCull() && !state.isBlend() && state.getCullFace() == GL11.GL_BACK
            : GL11.glIsEnabled(GL11.GL_CULL_FACE) && !GL11.glIsEnabled(GL11.GL_BLEND)
                && GL11.glGetInteger(GL11.GL_CULL_FACE_MODE) == GL11.GL_BACK)
            && GL11.glGetInteger(GL11.GL_FRONT_FACE) == GL11.GL_CCW;
    }

    private boolean captureDirectionalEye() {
        if (!EarlyMatrixStateTracker.copyModelView(directionalView, 0)) {
            directionalViewBuffer.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, directionalViewBuffer);
            for (int index = 0; index < 16; index++) directionalView[index] = directionalViewBuffer.get(index);
        }
        return TerrainCamera.eye(directionalView, directionalEye);
    }

    private void drawMappedRun(List<RenderChunk> visible, int start, int end,
                               TerrainLayer expected,
                               TerrainRenderListAccessor accessor,
                               boolean useIndirect, long frameId, int arrayLayoutBuffer) {
        drawMappedRun(visible, start, end, expected, accessor,
            useIndirect, frameId, arrayLayoutBuffer, true);
    }

    /** A storage-page change does not change a region's model-view transform.
     * Keep one matrix scope across its ordered page runs, with no geometry
     * relocation, retained command cache or extra GPU storage. */
    private int drawMappedRegion(List<RenderChunk> visible, int start, int end,
                                 TerrainLayer expected, TerrainRenderListAccessor accessor,
                                 long frameId, MeshRecord origin, int arrayLayoutBuffer) {
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate((float) (origin.regionX - accessor.ice$viewEntityX()),
                (float) (origin.regionY - accessor.ice$viewEntityY()),
                (float) (origin.regionZ - accessor.ice$viewEntityZ()));
            for (int index = start; index < end;) {
                MeshRecord first = record(visible.get(index), expected);
                int page = pageIndex(first.range);
                int runEnd = index + 1;
                while (runEnd < end) {
                    MeshRecord next = record(visible.get(runEnd), expected);
                    if (pageIndex(next.range) != page) break;
                    runEnd++;
                }
                drawMappedRun(visible, index, runEnd, expected, accessor,
                    false, frameId, arrayLayoutBuffer, false);
                arrayLayoutBuffer = nativeBuffer(first.range);
                index = runEnd;
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            try { GlStateManager.popMatrix(); }
            catch (Throwable restoreError) { failure = appendFailure(failure, restoreError); }
        }
        if (failure != null) rethrow(failure);
        return arrayLayoutBuffer;
    }

    private void drawMappedRun(List<RenderChunk> visible, int start, int end,
                               TerrainLayer expected, TerrainRenderListAccessor accessor,
                               boolean useIndirect, long frameId, int arrayLayoutBuffer,
                               boolean applyTransform) {
        MeshRecord first = record(visible.get(start), expected);
        if (first == null) throw new IllegalStateException("missing mapped terrain run");
        if (applyTransform) GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            if (applyTransform) GlStateManager.translate((float) (first.regionX - accessor.ice$viewEntityX()),
                (float) (first.regionY - accessor.ice$viewEntityY()),
                (float) (first.regionZ - accessor.ice$viewEntityZ()));
            int buffer = nativeBuffer(first.range);
            if (arrayLayoutBuffer != buffer) {
                bindArrayBuffer(buffer);
                setupArrayPointers(0L);
                arrayLayoutSets++;
            } else {
                arrayLayoutReuses++;
            }
            commandPageFirst = checkedInt(
                (first.range.getOffset() - pageOffset(first.range)) / STRIDE);
            int count = end - start;
            if (cpuDirection) {
                drawDirectionalRanges(visible, start, end, expected, accessor, first);
                return;
            }
            if (count == 1) {
                ArenaRange range = first.range;
                int firstVertex = pageOffset(range) / STRIDE;
                lastSubmissionStarted = true;
                GL11.glDrawArrays(GL11.GL_QUADS, firstVertex, first.vertexCount);
                drawCalls++;
                return;
            }
            if (nativeShaderBatching) {
                int pendingFirst = 0, pendingCount = 0;
                for (int i = start; i < end; i++) {
                    MeshRecord record = record(visible.get(i), expected);
                    int firstVertex = pageOffset(record.range) / STRIDE;
                    // These complete quad ranges already share one page and
                    // transform. Joining adjacent storage preserves draw order.
                    if (pendingCount > 0 && pendingFirst + pendingCount != firstVertex) {
                        submitFixedRange(pendingFirst, pendingCount);
                        pendingCount = 0;
                    }
                    if (pendingCount == 0) pendingFirst = firstVertex;
                    pendingCount += record.vertexCount;
                }
                if (pendingCount > 0) submitFixedRange(pendingFirst, pendingCount);
                return;
            }
            if (supportsIndirectCommands()) {
                lastIndirectEligibleCommands = Math.addExact(
                    lastIndirectEligibleCommands, count);
            }
            if (useIndirect && drawIndirect(visible, start, end, expected,
                first.generation)) {
                return;
            }
            if (!ensureCommandCapacity(count)) {
                multiDrawCapacityFallbacks = safeAdd(
                    multiDrawCapacityFallbacks, 1L);
                for (int i = start; i < end; i++) {
                    MeshRecord record = record(visible.get(i), expected);
                    int firstVertex = pageOffset(record.range) / STRIDE;
                    lastSubmissionStarted = true;
                    GL11.glDrawArrays(GL11.GL_QUADS, firstVertex,
                        record.vertexCount);
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
            }
            firstCommands.flip();
            countCommands.flip();
            lastSubmissionStarted = true;
            GL14.glMultiDrawArrays(GL11.GL_QUADS, firstCommands, countCommands);
            multiDrawCalls++;
        } catch (Throwable error) {
            failure = appendFailure(failure, error);
        } finally {
            try { if (applyTransform) GlStateManager.popMatrix(); }
            catch (Throwable restoreError) {
                failure = appendFailure(failure, restoreError);
            }
            if (failure != null) rethrow(failure);
        }
    }

    /** Fixed transforms are shared by the region; only visible face spans draw.
     * Adjacent spans coalesce without crossing a skipped face, page or layer. */
    private void drawDirectionalRanges(List<RenderChunk> visible, int start, int end,
                                       TerrainLayer expected, TerrainRenderListAccessor view,
                                       MeshRecord origin) {
        double eyeX = directionalEye[0] - (float) (origin.regionX - view.ice$viewEntityX());
        double eyeY = directionalEye[1] - (float) (origin.regionY - view.ice$viewEntityY());
        double eyeZ = directionalEye[2] - (float) (origin.regionZ - view.ice$viewEntityZ());
        int pendingFirst = 0, pendingCount = 0;
        for (int i = start; i < end; i++) {
            MeshRecord record = record(visible.get(i), expected);
            TerrainFaceRanges faces = record.mesh.getFaceRanges();
            int offset = pageOffset(record.range) / STRIDE;
            int plan = faces == null ? 1 << 24 : faces.submissionPlan(eyeX, eyeY, eyeZ);
            int submittedVertices = 0;
            for (int spans = plan >>> 24; spans > 0; spans--, plan >>>= 6) {
                int count = faces == null ? record.vertexCount : faces.spanCount(plan);
                if (count == 0) continue;
                submittedVertices += count;
                int first = offset + (faces == null ? 0 : faces.spanFirst(plan));
                if (pendingCount > 0 && pendingFirst + pendingCount != first) {
                    submitFixedRange(pendingFirst, pendingCount);
                    pendingCount = 0;
                }
                if (pendingCount == 0) pendingFirst = first;
                pendingCount += count;
            }
            int omitted = record.vertexCount - submittedVertices;
            if (omitted > 0) directionalCulledVertices = safeAdd(directionalCulledVertices, omitted);
        }
        if (pendingCount > 0) submitFixedRange(pendingFirst, pendingCount);
    }

    private void submitFixedRange(int first, int count) {
        lastSubmissionStarted = true;
        GL11.glDrawArrays(GL11.GL_QUADS, first, count);
        drawCalls++;
        shaderBatchDrawCalls++;
        shaderBatchCommands++;
    }

    private void drawLegacyVertexBuffer(RenderChunk chunk, VertexBuffer vertexBuffer,
                                         TerrainRenderListAccessor accessor) {
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            preRenderChunk(chunk, accessor);
            chunk.multModelviewMatrix();
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

    /** OptiFine queues ranges; flush once per contiguous region, just as its
     * ordinary VboRenderList does, without crossing any native/legacy boundary. */
    private int drawLegacyRun(List<RenderChunk> visible, int start,
                              BlockRenderLayer layer,
                              TerrainRenderListAccessor accessor) {
        RenderChunk first = visible.get(start);
        int layerIndex = layer.ordinal();
        VertexBuffer firstBuffer = first.getVertexBufferByLayer(layerIndex);
        Object region = OptifineTerrainRegionBridge.region(firstBuffer);
        if (region == null) {
            drawLegacyVertexBuffer(first, firstBuffer, accessor);
            drawCalls++;
            return start + 1;
        }
        BlockPos position = first.getPosition();
        int x = regionOrigin(position.getX()), z = regionOrigin(position.getZ());
        int end = start + 1;
        while (end < visible.size()) {
            RenderChunk next = visible.get(end);
            if (record(next, layer(layer)) != null) break;
            BlockPos nextPosition = next.getPosition();
            if (regionOrigin(nextPosition.getX()) != x || regionOrigin(nextPosition.getZ()) != z
                || OptifineTerrainRegionBridge.region(next.getVertexBufferByLayer(layerIndex)) != region) break;
            end++;
        }
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate((float) (x - accessor.ice$viewEntityX()),
                (float) -accessor.ice$viewEntityY(), (float) (z - accessor.ice$viewEntityZ()));
            lastSubmissionStarted = true;
            for (int index = start; index < end; index++) {
                visible.get(index).getVertexBufferByLayer(layerIndex).drawArrays(GL11.GL_QUADS);
            }
            OptifineTerrainRegionBridge.finish(region, accessor);
            drawCalls++;
        } catch (Throwable error) {
            failure = error;
        } finally {
            try { GlStateManager.popMatrix(); }
            catch (Throwable restoreError) { failure = appendFailure(failure, restoreError); }
        }
        if (failure != null) rethrow(failure);
        return end;
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
            bindArrayBuffer(nativeBuffer(record.range));
            setupArrayPointers(0L);
            int firstVertex = pageOffset(record.range) / STRIDE;
            lastSubmissionStarted = true;
            GL11.glDrawArrays(GL11.GL_QUADS, firstVertex, record.vertexCount);
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
            || !allocator.ownsLiveRange(record.range)) return null;
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
        Throwable failure = null;
        try { uploadStream.close(contextValid); }
        catch (Throwable error) { failure = error; }
        for (int index = 0; index < bufferPages.length; index++) {
            BufferPage page = bufferPages[index];
            if (page == null) continue;
            bufferPages[index] = null;
            if (contextValid) failure = retirePage(page, failure);
        }
        bufferId = 0;
        mappingPoisoned = false;
        mappingFailure = null;
        lastUploadPersistent = false;
        contextCapabilities = null;
        if (failure != null) rethrow(failure);
    }

    private void retireEmptyPages() {
        Throwable failure = null;
        int remainingId = 0;
        for (int index = 0; index < bufferPages.length; index++) {
            BufferPage page = bufferPages[index];
            if (page == null) continue;
            if (allocator.isPageFree(index * pageBytes)) {
                bufferPages[index] = null;
                failure = retirePage(page, failure);
            } else {
                remainingId = page.nativeId;
            }
        }
        bufferId = remainingId;
        if (failure != null) rethrow(failure);
    }

    /** Allocation-pressure callback, also usable after a terrain backend exit.
     * Only completed range retirements and genuinely empty pages are reclaimed;
     * a range tentatively allocated by the caller keeps its page live. */
    public long reclaimUnusedPages(long glContextGeneration) {
        guard.check();
        long before = budget.snapshot().getGpuUsed();
        collectRetired(retired.size(), true);
        retireEmptyPages();
        // Ledger containers are visited before their referenced buffer payloads.
        // Both passes are zero-timeout; a busy GPU simply retains ownership.
        resources.collect(glContextGeneration, Integer.MAX_VALUE);
        resources.collect(glContextGeneration, Integer.MAX_VALUE);
        return Math.max(0L, before - budget.snapshot().getGpuUsed());
    }

    private Throwable retirePage(BufferPage page, Throwable failure) {
        try {
            resources.retire(page.handle, LwjglRetirementFence.afterCurrentCommands(resources));
        } catch (Throwable error) { failure = appendFailure(failure, error); }
        return failure;
    }

    private int pageIndex(ArenaRange range) {
        return (int) (range.getOffset() / pageBytes);
    }

    private int pageOffset(ArenaRange range) {
        return localOffset(range, pageBytes);
    }

    private int nativeBuffer(ArenaRange range) {
        return bufferPages[pageIndex(range)].nativeId;
    }

    static int localOffset(ArenaRange range, long pageBytes) {
        long offset = range.getOffset() % pageBytes;
        if (offset < 0L || range.getLength() > pageBytes - offset) {
            throw new IllegalArgumentException("terrain mesh crosses buffer page");
        }
        return checkedInt(offset);
    }

    private void closeStaging() {
        Throwable failure = null;
        staging = null;
        CacheBudget.Reservation reservation = stagingReservation;
        stagingReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        faceStaging = null;
        reservation = faceStagingReservation;
        faceStagingReservation = null;
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

    static ByteBuffer normalizeIntoStaging(ByteBuffer source, int sourceBytes,
                                           int sourceStride, int bytes,
                                           BlockPos chunk, int regionX,
                                           int regionY, int regionZ,
                                           ByteBuffer staging) {
        return normalizeIntoStaging(source, sourceBytes, sourceStride, bytes,
            chunk.getX(), chunk.getY(), chunk.getZ(), MODEL_SCALE, MODEL_TRANSLATION,
            regionX, regionY, regionZ, staging);
    }

    static ByteBuffer normalizeIntoStaging(ByteBuffer source, int sourceBytes,
                                           int sourceStride, int bytes,
                                           int originX, int originY, int originZ,
                                           float scale, float translation,
                                           int regionX, int regionY, int regionZ,
                                           ByteBuffer staging) {
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
                input.limit(offset + STRIDE);
                input.position(offset);
                staging.put(input);
            }
        }
        ByteBuffer output = staging.duplicate().order(ByteOrder.nativeOrder());
        normalizePositions(output, bytes, originX, originY, originZ,
            scale, translation, regionX, regionY, regionZ);
        output.position(0);
        output.limit(bytes);
        return output;
    }

    static void normalizePositions(ByteBuffer output, int bytes,
                                    int originX, int originY, int originZ,
                                    float scale, float translation,
                                    int regionX, int regionY, int regionZ) {
        float dx = originX - regionX;
        float dy = originY - regionY;
        float dz = originZ - regionZ;
        for (int offset = 0; offset < bytes; offset += STRIDE) {
            output.putFloat(offset, output.getFloat(offset) * scale
                + translation + dx);
            output.putFloat(offset + 4, output.getFloat(offset + 4) * scale
                + translation + dy);
            output.putFloat(offset + 8, output.getFloat(offset + 8) * scale
                + translation + dz);
        }
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

    private static TerrainLayer layer(BlockRenderLayer layer) {
        if (layer == null) return null;
        int ordinal = layer.ordinal();
        return ordinal < TERRAIN_LAYERS.length ? TERRAIN_LAYERS[ordinal] : null;
    }

    private static int regionOrigin(int coordinate) {
        return Math.floorDiv(coordinate, REGION_SIZE) * REGION_SIZE;
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
        private final FrameStamp generation;
        private final ArenaRange range;
        private final TerrainMesh mesh;
        private final boolean retainLegacyCopy;
        private boolean legacyUploadExpected;

        private MeshRecord(RenderChunk chunk, int regionX, int regionY, int regionZ,
                           FrameStamp generation, TerrainMesh mesh,
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
            this.byteCount = Math.multiplyExact(vertexCount, STRIDE);
            this.generation = generation;
            this.range = mesh.getRange();
            this.mesh = mesh;
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

    private static final class BufferPage {
        private final int nativeId;
        private final RenderHandle handle;
        private final long capacity;
        private BufferPage(int nativeId, RenderHandle handle, long capacity) {
            this.nativeId = nativeId;
            this.handle = handle;
            this.capacity = capacity;
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

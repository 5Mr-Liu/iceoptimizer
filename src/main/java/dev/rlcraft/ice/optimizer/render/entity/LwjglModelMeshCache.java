package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Render-thread VBO cache for exact ModelRenderer display-list payloads.  It
 * never changes matrices, textures, programs or fixed-function state; one VBO
 * draw is emitted at the original call-list instruction.
 */
public final class LwjglModelMeshCache {
    private static final int MAX_ENTRIES = 8192;
    private static final int MIN_STAGING_BYTES = 4096;
    private static final int MAX_STAGING_BYTES = 16 * 1024 * 1024;
    private static final ModelArrayLayout MODEL_ARRAY_LAYOUT =
        ModelArrayLayout.capture(DefaultVertexFormats.POSITION_TEX_NORMAL);

    interface PublicationHook {
        void afterEntryPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterEntryPut() { }
        };

    private final RenderThreadGuard threadGuard;
    private final ResourceLedger resources;
    private final CacheBudget budget;
    private final PublicationHook publicationHook;
    private final ModelMeshStorage storage;
    private final LinkedHashMap<Integer, Entry> entries =
        new LinkedHashMap<Integer, Entry>(128, 0.75F, true);
    private ByteBuffer staging;
    private CacheBudget.Reservation stagingReservation;
    private boolean stateBatchActive;
    private int stateBatchPreviousBuffer;
    private int stateBatchBuffer;
    private long stateBatchBegins;
    private long stateBatchDraws;
    private long stateBatchRestores;
    private long stateBatchRejections;
    private long stateBatchBufferBinds;
    private long stateBatchPointerSets;

    public LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources) {
        this(threadGuard, resources, null, NO_PUBLICATION_HOOK);
    }

    public LwjglModelMeshCache(RenderThreadGuard threadGuard,
                               ResourceLedger resources,
                               CacheBudget budget) {
        this(threadGuard, resources, budget, NO_PUBLICATION_HOOK);
    }

    LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources,
                        PublicationHook publicationHook) {
        this(threadGuard, resources, null, publicationHook);
    }

    LwjglModelMeshCache(RenderThreadGuard threadGuard, ResourceLedger resources,
                        CacheBudget budget, PublicationHook publicationHook) {
        if (threadGuard == null || resources == null) {
            throw new IllegalArgumentException("model mesh cache dependencies");
        }
        if (publicationHook == null) {
            throw new IllegalArgumentException("model mesh publication hook");
        }
        this.threadGuard = threadGuard;
        this.resources = resources;
        this.budget = budget;
        this.publicationHook = publicationHook;
        this.storage = new ModelMeshStorage(resources, budget, MODEL_ARRAY_LAYOUT.stride);
    }

    public boolean accept(ModelMeshPayload payload, FrameStamp stamp) {
        threadGuard.check();
        if (payload == null || stamp == null
            || payload.getResourceGeneration() != stamp.getResourceGeneration()
            || payload.getContextGeneration() != stamp.getGlContextGeneration()
            || payload.getDrawMode() != GL11.GL_QUADS
            || !DefaultVertexFormats.POSITION_TEX_NORMAL.equals(payload.getFormat())) {
            return false;
        }
        Integer cacheKey = Integer.valueOf(payload.getDisplayList());
        Entry current = entries.get(cacheKey);
        if (current != null && resources.isLive(current.handle)
            && current.matches(payload, stamp)) return true;

        int previous = EarlyGlStateTracker.arrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        int vertexBytes = payload.getByteLength();
        if (!ensureStaging(vertexBytes)) return false;
        staging.clear();
        staging.limit(vertexBytes);
        payload.copyVerticesTo(staging);
        staging.flip();
        ModelMeshStorage.Allocation allocation = null;
        boolean accepted = false;
        Throwable failure = null;
        Entry replacement = null;
        boolean publicationComplete = false;
        try {
            allocation = storage.allocate(vertexBytes, stamp);
            if (allocation != null) {
                storage.upload(allocation, staging);
                replacement = new Entry(payload, allocation);
                publishReplacement(entries, cacheKey, current, replacement,
                    publicationHook);
                publicationComplete = true;
                allocation = null;
                if (current != null) retire(current);
                evictOverflow();
                accepted = true;
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean mapMayOwnRange = false;
            if (!publicationComplete && replacement != null && allocation != null) {
                try {
                    Entry mapped = entries.get(cacheKey);
                    if (mapped == replacement) {
                        if (current == null) {
                            Entry removed = entries.remove(cacheKey);
                            if (removed != replacement) {
                                throw new IllegalStateException(
                                    "model VBO publication rollback failed");
                            }
                        } else {
                            entries.put(cacheKey, current);
                            if (entries.get(cacheKey) != current) {
                                throw new IllegalStateException(
                                    "model VBO replacement rollback failed");
                            }
                        }
                    }
                } catch (Throwable error) {
                    mapMayOwnRange = true;
                    failure = appendFailure(failure, error);
                }
            }
            if (mapMayOwnRange) allocation = null;
            try { bindArrayBuffer(previous); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, error);
            }
            if (allocation != null) try { storage.cancel(allocation); }
            catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        if (failure != null) rethrow(failure);
        return accepted;
    }

    public Entry find(int displayList, FrameStamp stamp) {
        threadGuard.check();
        Entry entry = entries.get(Integer.valueOf(displayList));
        if (entry == null || stamp == null || !entry.handle.belongsTo(
            stamp.getResourceGeneration(), stamp.getGlContextGeneration())
            || !resources.isLive(entry.handle)) return null;
        return entry;
    }

    /** Synchronous only during the bounded OUTPUT_VALIDATE state. */
    public boolean validate(Entry entry) {
        threadGuard.check();
        if (entry == null || !entry.allocation.isLive()
            || !resources.isLive(entry.handle)) return false;
        int previous = EarlyGlStateTracker.arrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        Throwable failure = null;
        try {
            bindArrayBuffer(entry.handle.getNativeId());
            if (!ensureStaging(entry.byteLength)) return false;
            staging.clear();
            staging.limit(entry.byteLength);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,
                entry.allocation.range.getOffset(), staging);
            staging.position(0);
            staging.limit(entry.byteLength);
            entry.validated = Arrays.equals(entry.digest,
                ModelMeshPayload.sha256(staging));
        } catch (Throwable error) {
            entry.validated = false;
            failure = error;
        } finally {
            try { bindArrayBuffer(previous); }
            catch (Throwable restoreError) {
                EarlyGlStateTracker.invalidate();
                failure = appendFailure(failure, restoreError);
            }
        }
        if (failure != null) rethrow(failure);
        return entry.validated;
    }

    /**
     * Returns false only if no GL draw was issued, so the caller may execute
     * the original display list without risking a duplicate partial draw.
     */
    public boolean draw(Entry entry) {
        return draw(entry, null);
    }

    public boolean draw(Entry entry, DrawPacket packet) {
        threadGuard.check();
        if (entry == null || !entry.validated || !resources.isLive(entry.handle)) return false;
        EarlyGlStateTracker.Snapshot state = EarlyGlStateTracker.snapshot();
        if (state == null || !state.hasArrayBufferBinding()
            || !state.hasDrawState() || state.getProgram() != 0
            || state.getClientActiveTexture() != 0
            || packet == null || packet.getMeshHandle() != entry.getLogicalHandle()
            || !entry.handle.belongsTo(packet.getGeneration().getResourceGeneration(),
                packet.getGeneration().getGlContextGeneration())
            || !matches(packet.getState(), state)
            || !packet.matchesMaterial(state)
            || !EarlyMatrixStateTracker.matchesModelView(packet.rawPartMatrices())) return false;
        return emitImmediate(entry, state.getArrayBuffer());
    }

    /**
     * Allocation-free production path for a draw emitted at the exact
     * ModelRenderer call-list instruction.  No packet or matrix copy is
     * needed because no command is deferred or reordered.
     */
    public boolean drawCurrent(Entry entry, FrameStamp stamp) {
        threadGuard.check();
        if (entry == null || stamp == null || !entry.validated
            || !resources.isLive(entry.handle)
            || !entry.handle.belongsTo(stamp.getResourceGeneration(),
                stamp.getGlContextGeneration())
            || !EarlyMatrixStateTracker.isKnown()) return false;
        int previous = EarlyGlStateTracker
            .fixedFunctionModelArrayBufferBinding();
        return previous != Integer.MIN_VALUE && emitImmediate(entry, previous);
    }

    /** Admission for a preflighted, callback-free ModelRenderer tree. */
    public Entry findStateBatchEntry(int displayList, FrameStamp stamp) {
        Entry entry = find(displayList, stamp);
        return entry != null && entry.validated ? entry : null;
    }

    public boolean beginStateBatch() {
        threadGuard.check();
        if (stateBatchActive || !EarlyMatrixStateTracker.isKnown()) return false;
        int previous = EarlyGlStateTracker.fixedFunctionModelArrayBufferBinding();
        if (previous == Integer.MIN_VALUE) return false;
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        stateBatchPreviousBuffer = previous;
        stateBatchBuffer = 0;
        stateBatchActive = true;
        try {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            stateBatchBegins++;
            return true;
        } catch (Throwable failure) {
            try { endStateBatch(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            rethrow(failure);
            return false;
        }
    }

    /**
     * Requires an entry admitted by findStateBatchEntry in this synchronous,
     * callback-free scope. Resource lifetime, thread and fixed-function state
     * cannot change within it. Original matrix instructions can invalidate
     * their tracker, so that dynamic check remains at each draw.
     */
    public boolean drawInStateBatch(Entry entry) {
        if (!stateBatchActive || !EarlyMatrixStateTracker.isKnown()) return false;
        int buffer = entry.handle.getNativeId();
        if (stateBatchBuffer != buffer) {
            bindArrayBuffer(buffer);
            ModelArrayLayout layout = MODEL_ARRAY_LAYOUT;
            GL11.glVertexPointer(layout.positionCount, layout.positionType,
                layout.stride, layout.positionOffset);
            GL11.glTexCoordPointer(layout.textureCount, layout.textureType,
                layout.stride, layout.textureOffset);
            GL11.glNormalPointer(layout.normalType, layout.stride, layout.normalOffset);
            stateBatchBuffer = buffer;
            stateBatchBufferBinds++;
            stateBatchPointerSets++;
        }
        // An exception after this point must escape; legacy replay could draw twice.
        GL11.glDrawArrays(entry.drawMode, entry.allocation.firstVertex, entry.vertexCount);
        stateBatchDraws++;
        return true;
    }

    public void endStateBatch() {
        threadGuard.check();
        if (!stateBatchActive) return;
        stateBatchActive = false;
        try {
            GL11.glPopClientAttrib();
            restoreArrayBufferBinding(stateBatchPreviousBuffer, true);
            stateBatchRestores++;
        } catch (Throwable failure) {
            EarlyGlStateTracker.invalidate();
            rethrow(failure);
        }
    }

    public void rejectStateBatch() { stateBatchRejections++; }
    public long getStateBatchBegins() { return stateBatchBegins; }
    public long getStateBatchDraws() { return stateBatchDraws; }
    public long getStateBatchRestores() { return stateBatchRestores; }
    public long getStateBatchRejections() { return stateBatchRejections; }
    public long getStateBatchBufferBinds() { return stateBatchBufferBinds; }
    public long getStateBatchPointerSets() { return stateBatchPointerSets; }
    public int getSharedPageCount() { return storage.pageCount(); }

    private boolean emitImmediate(Entry entry, int previous) {
        // ModelRenderer's uploader mutates client-array state while the list
        // is compiled; callList itself preserves the caller's client state.
        // One push/pop therefore reproduces the legacy execution contract.
        // Do not also run the uploader-style disable sequence on success: pop
        // is the sole normal-path restore and the extra calls are redundant.
        boolean issued = false;
        boolean clientStatePushed = false;
        boolean clientStateRestored = false;
        Throwable failure = null;
        boolean cleanupFailed = false;
        try {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            clientStatePushed = true;
            bindArrayBuffer(entry.handle.getNativeId());
            prepareArrays();
            issued = true;
            GL11.glDrawArrays(entry.drawMode, entry.allocation.firstVertex, entry.vertexCount);
        } catch (Throwable error) {
            failure = error;
            cleanupFailed = error instanceof ClientArrayCleanupException;
        } finally {
            if (clientStatePushed) {
                try {
                    GL11.glPopClientAttrib();
                    clientStateRestored = true;
                }
                catch (Throwable error) {
                    EarlyGlStateTracker.invalidate();
                    cleanupFailed = true;
                    failure = appendFailure(failure, error);
                }
            }
            try { restoreArrayBufferBinding(previous, clientStateRestored); }
            catch (Throwable error) {
                EarlyGlStateTracker.invalidate();
                cleanupFailed = true;
                failure = appendFailure(failure, error);
            }
        }
        if (failure != null) {
            FatalErrors.rethrowIfFatal(failure);
            if (issued || cleanupFailed) rethrow(failure);
            return false;
        }
        return true;
    }

    /** The client vertex-array stack also restores ARRAY_BUFFER_BINDING. */
    static void restoreArrayBufferBinding(int previous, boolean clientStateRestored) {
        if (clientStateRestored) {
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        } else {
            bindArrayBuffer(previous);
        }
    }

    public void certifyCapturedEntry(Entry entry) {
        threadGuard.check();
        if (entry != null && entry.allocation.isLive()
            && resources.isLive(entry.handle)) entry.validated = true;
    }

    public void invalidate(int displayList) {
        threadGuard.check();
        Integer key = Integer.valueOf(displayList);
        Entry current = entries.get(key);
        if (current == null) return;
        // Keep the cache reference until the ledger accepts ownership.  A
        // retirement publication failure may leave the handle live.
        retire(current);
        Entry removed = entries.remove(key);
        if (removed != current) {
            throw new IllegalStateException(
                "model mesh invalidation removed an unexpected entry");
        }
    }

    public void reset(boolean contextValid) {
        threadGuard.check();
        Throwable failure = null;
        if (contextValid) {
            try { endStateBatch(); }
            catch (Throwable error) { failure = error; }
        } else {
            stateBatchActive = false;
        }
        for (Entry entry : entries.values()) entry.validated = false;
        entries.clear();
        try { storage.reset(contextValid); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public void close(boolean contextValid) {
        threadGuard.check();
        Throwable failure = null;
        try { reset(contextValid); }
        catch (Throwable error) { failure = error; }
        staging = null;
        CacheBudget.Reservation reservation = stagingReservation;
        stagingReservation = null;
        if (reservation != null) try { reservation.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure != null) rethrow(failure);
    }

    public int size() { threadGuard.check(); return entries.size(); }

    boolean ensureStaging(int required) {
        if (required <= 0 || required > MAX_STAGING_BYTES || budget == null) {
            return false;
        }
        if (staging != null && staging.capacity() >= required) return true;
        int target = MIN_STAGING_BYTES;
        while (target < required && target < MAX_STAGING_BYTES) target <<= 1;
        target = Math.max(required, Math.min(MAX_STAGING_BYTES, target));
        CacheBudget.Reservation reservation = budget.tryReserve(
            BudgetKind.DIRECT, target);
        if (reservation == null) return false;
        ByteBuffer replacement;
        try {
            replacement = BufferUtils.createByteBuffer(target)
                .order(ByteOrder.nativeOrder());
        } catch (Throwable error) {
            reservation.close();
            throw error;
        }
        CacheBudget.Reservation previous = stagingReservation;
        staging = replacement;
        stagingReservation = reservation;
        if (previous != null) previous.close();
        return true;
    }

    private void evictOverflow() {
        Iterator<Map.Entry<Integer, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > MAX_ENTRIES && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            retire(entry);
            iterator.remove();
        }
    }

    private void retire(Entry entry) {
        storage.retire(entry.allocation);
        entry.validated = false;
    }

    /**
     * Publishes one replacement transactionally.  Kept package-visible so a
     * CPU-only fault-injection test can exercise the exact map transaction
     * without requiring a live OpenGL context.
     */
    static <K, V> void publishReplacement(Map<K, V> target, K key, V current,
                                          V replacement,
                                          PublicationHook hook) {
        if (target == null || replacement == null || hook == null) {
            throw new IllegalArgumentException("model mesh publication");
        }
        try {
            target.put(key, replacement);
            hook.afterEntryPut();
        } catch (Throwable publicationFailure) {
            try {
                V mapped = target.get(key);
                if (mapped == replacement) {
                    if (current == null) {
                        V removed = target.remove(key);
                        if (removed != replacement || target.get(key) != null) {
                            throw new IllegalStateException(
                                "model VBO publication rollback failed");
                        }
                    } else {
                        target.put(key, current);
                        if (target.get(key) != current) {
                            throw new IllegalStateException(
                                "model VBO replacement rollback failed");
                        }
                    }
                } else if (mapped != current) {
                    throw new IllegalStateException(
                        "model VBO rollback found foreign entry");
                }
            } catch (Throwable rollbackFailure) {
                publicationFailure = appendFailure(publicationFailure,
                    rollbackFailure);
            }
            rethrow(publicationFailure);
        }
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
        throw new IllegalStateException("model mesh cache operation failed", failure);
    }

    private static int prepareArrays() {
        final int vertex = 1;
        final int normal = 1 << 1;
        final int texture = 1 << 2;
        int enabled = 0;
        ModelArrayLayout layout = MODEL_ARRAY_LAYOUT;
        try {
            GL11.glVertexPointer(layout.positionCount, layout.positionType,
                layout.stride, layout.positionOffset);
            enabled |= vertex;
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glTexCoordPointer(layout.textureCount, layout.textureType,
                layout.stride, layout.textureOffset);
            enabled |= texture;
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glNormalPointer(layout.normalType, layout.stride,
                layout.normalOffset);
            enabled |= normal;
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            return enabled;
        } catch (RuntimeException | Error error) {
            try { releaseArrays(enabled); }
            catch (Throwable cleanupFailure) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                if (cleanupFailure != error) error.addSuppressed(cleanupFailure);
                EarlyGlStateTracker.invalidate();
                throw new ClientArrayCleanupException(error);
            }
            throw error;
        }
    }

    private static void bindArrayBuffer(int nativeId) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
        EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, nativeId);
    }

    private static void releaseArrays(int enabled) {
        if ((enabled & (1 << 2)) != 0) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if ((enabled & (1 << 1)) != 0) {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        }
        if ((enabled & 1) != 0) GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    private static boolean matches(RenderStateKey expected,
                                   EarlyGlStateTracker.Snapshot actual) {
        return expected != null && expected.getProgram() == actual.getProgram()
            && expected.getTexture() == actual.getTexture0()
            && expected.getLightmapTexture() == actual.getTexture1()
            && expected.isBlend() == actual.isBlend()
            && expected.getBlendSource() == actual.getBlendSourceRgb()
            && expected.getBlendDestination() == actual.getBlendDestinationRgb()
            && expected.getBlendSourceAlpha() == actual.getBlendSourceAlpha()
            && expected.getBlendDestinationAlpha() == actual.getBlendDestinationAlpha()
            && expected.isDepthTest() == actual.isDepthTest()
            && expected.isDepthMask() == actual.isDepthMask()
            && expected.isCull() == actual.isCull()
            && expected.getColorMask() == actual.getColorMask();
    }

    /** Marks a pre-draw partial enable whose rollback could not be certified. */
    private static final class ClientArrayCleanupException
        extends RuntimeException {
        private ClientArrayCleanupException(Throwable cause) {
            super("model client-array cleanup failed", cause);
        }
    }

    private static final class ModelArrayLayout {
        private final int stride;
        private final int positionCount;
        private final int positionType;
        private final long positionOffset;
        private final int textureCount;
        private final int textureType;
        private final long textureOffset;
        private final int normalType;
        private final long normalOffset;

        private ModelArrayLayout(int stride, int positionCount,
                                 int positionType, long positionOffset,
                                 int textureCount, int textureType,
                                 long textureOffset, int normalType,
                                 long normalOffset) {
            this.stride = stride;
            this.positionCount = positionCount;
            this.positionType = positionType;
            this.positionOffset = positionOffset;
            this.textureCount = textureCount;
            this.textureType = textureType;
            this.textureOffset = textureOffset;
            this.normalType = normalType;
            this.normalOffset = normalOffset;
        }

        private static ModelArrayLayout capture(VertexFormat format) {
            int positionCount = 0;
            int positionType = 0;
            long positionOffset = -1L;
            int textureCount = 0;
            int textureType = 0;
            long textureOffset = -1L;
            int normalType = 0;
            long normalOffset = -1L;
            List<VertexFormatElement> elements = format.getElements();
            for (int i = 0; i < elements.size(); i++) {
                VertexFormatElement element = elements.get(i);
                switch (element.getUsage()) {
                    case POSITION:
                        if (positionOffset >= 0L) {
                            throw new IllegalStateException(
                                "duplicate model position element");
                        }
                        positionCount = element.getElementCount();
                        positionType = element.getType().getGlConstant();
                        positionOffset = format.getOffset(i);
                        break;
                    case UV:
                        if (element.getIndex() != 0 || textureOffset >= 0L) {
                            throw new IllegalStateException(
                                "unsupported model UV element");
                        }
                        textureCount = element.getElementCount();
                        textureType = element.getType().getGlConstant();
                        textureOffset = format.getOffset(i);
                        break;
                    case NORMAL:
                        if (element.getElementCount() != 3
                            || normalOffset >= 0L) {
                            throw new IllegalStateException(
                                "unsupported model normal element");
                        }
                        normalType = element.getType().getGlConstant();
                        normalOffset = format.getOffset(i);
                        break;
                    case PADDING:
                        break;
                    default:
                        throw new IllegalStateException(
                            "unsupported model element " + element.getUsage());
                }
            }
            if (positionOffset < 0L || textureOffset < 0L
                || normalOffset < 0L) {
                throw new IllegalStateException("incomplete model array layout");
            }
            return new ModelArrayLayout(format.getSize(), positionCount,
                positionType, positionOffset, textureCount, textureType,
                textureOffset, normalType, normalOffset);
        }
    }

    public static final class Entry {
        private final int displayList;
        private final RenderHandle handle;
        private final ModelMeshStorage.Allocation allocation;
        private final VertexFormat format;
        private final int drawMode;
        private final int vertexCount;
        private final int byteLength;
        private final byte[] digest;
        private boolean validated;

        private Entry(ModelMeshPayload payload, ModelMeshStorage.Allocation allocation) {
            this.displayList = payload.getDisplayList();
            this.allocation = allocation;
            this.handle = allocation.handle();
            this.format = payload.getFormat();
            this.drawMode = payload.getDrawMode();
            this.vertexCount = payload.getVertexCount();
            this.byteLength = payload.getByteLength();
            this.digest = payload.getDigest();
        }

        private boolean matches(ModelMeshPayload payload, FrameStamp stamp) {
            return displayList == payload.getDisplayList()
                && handle.belongsTo(stamp.getResourceGeneration(),
                    stamp.getGlContextGeneration())
                && drawMode == payload.getDrawMode()
                && vertexCount == payload.getVertexCount()
                && byteLength == payload.getByteLength()
                && format.equals(payload.getFormat())
                && Arrays.equals(digest, payload.getDigest());
        }

        public long getLogicalHandle() { return allocation.id; }
        public int getNativeBuffer() { return handle.getNativeId(); }
        public int getFirstVertex() { return allocation.firstVertex; }
        public boolean isValidated() { return validated; }
    }
}

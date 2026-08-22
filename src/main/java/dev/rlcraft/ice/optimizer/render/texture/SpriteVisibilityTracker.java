package dev.rlcraft.ice.optimizer.render.texture;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.memory.RetainedHeap;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded animation visibility state.  A frame is usable only after every
 * required source completed; unknown sources make the frame fail-open.  Pixel
 * arrays are copied before an upload is deferred and are never retained from
 * TextureAtlasSprite.
 */
public final class SpriteVisibilityTracker implements AutoCloseable {
    private static final long ENTRY_HEAP_BYTES = 256L;
    private static final long DEFERRED_UPLOAD_ENVELOPE_BYTES = 160L;
    interface PublicationHook {
        void afterIndexPut();
    }

    private static final PublicationHook NO_PUBLICATION_HOOK =
        new PublicationHook() {
            @Override public void afterIndexPut() { }
        };

    /** Legacy immutable-upload API retained for the public optimizer ABI. */
    public interface UploadSink {
        boolean offer(TextureUpload upload);
    }

    /** Render-thread catch-up sink. Returning false means "not bound yet". */
    public interface CatchUpSink {
        boolean upload(DeferredUpload upload) throws Exception;
    }

    /** Immutable, generation-qualified current-frame upload. */
    public static final class DeferredUpload {
        private final Object sprite;
        private final int spriteIndex;
        private final int textureId;
        private final int[][] pixels;
        private final int width;
        private final int height;
        private final int originX;
        private final int originY;
        private final boolean blur;
        private final boolean clamp;
        private final long resourceGeneration;
        private final long atlasGeneration;
        private final long sequence;
        private final long byteCount;
        private CacheBudget.Reservation reservation;

        private DeferredUpload(Object sprite, int spriteIndex, int textureId,
                               int[][] source, int width, int height,
                               int originX, int originY, boolean blur,
                               boolean clamp, long resourceGeneration,
                               long atlasGeneration, long sequence,
                               long byteCount,
                               CacheBudget.Reservation reservation) {
            if (reservation == null) throw new IllegalArgumentException(
                "deferred upload reservation");
            this.sprite = sprite;
            this.spriteIndex = spriteIndex;
            this.textureId = textureId;
            this.pixels = copyLevels(source, width, height);
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
            this.blur = blur;
            this.clamp = clamp;
            this.resourceGeneration = resourceGeneration;
            this.atlasGeneration = atlasGeneration;
            this.sequence = sequence;
            this.byteCount = byteCount;
            this.reservation = reservation;
        }

        public Object getSprite() { return sprite; }
        public int getSpriteIndex() { return spriteIndex; }
        public int getTextureId() { return textureId; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getOriginX() { return originX; }
        public int getOriginY() { return originY; }
        public boolean isBlur() { return blur; }
        public boolean isClamp() { return clamp; }
        public long getResourceGeneration() { return resourceGeneration; }
        public long getAtlasGeneration() { return atlasGeneration; }
        public long getSequence() { return sequence; }
        public long getByteCount() { return byteCount; }

        /** TextureUtil does not mutate this copy; callers must not retain it. */
        public int[][] copyPixels() {
            return copyLevels(pixels, width, height);
        }

        private void close() {
            CacheBudget.Reservation owned = reservation;
            reservation = null;
            if (owned != null) owned.close();
        }
    }

    private final int maximumSprites;
    private final long maximumPendingBytes;
    private final CatchUpSink catchUpSink;
    private final PublicationHook publicationHook;
    private final CacheBudget budget;
    private final IdentityHashMap<Object, Entry> byIdentity =
        new IdentityHashMap<Object, Entry>();
    private final LinkedHashMap<Integer, Entry> byIndex =
        new LinkedHashMap<Integer, Entry>();
    private final LinkedHashMap<Long, LegacyEntry> legacyEntries =
        new LinkedHashMap<Long, LegacyEntry>(16, 0.75F, true);
    private final UploadSink legacySink;
    private long openFrame = Long.MIN_VALUE;
    private long lastCompleteFrame = Long.MIN_VALUE;
    private long resourceGeneration = Long.MIN_VALUE;
    private long atlasGeneration = Long.MIN_VALUE;
    private long pendingBytes;
    private long sequence;
    private boolean frameUnknown;
    private boolean terrainSourceSupported;
    private boolean bufferSourceSupported;
    private boolean trusted = true;
    private Throwable lastFailure;
    private long evicted;
    private long deferred;
    private long caughtUp;
    private long deferredBytes;
    private long caughtUpBytes;
    private long immediateUploads;
    private long rejected;
    private long unknownFrames;

    /** Compatibility constructor used by the old immutable upload stream. */
    public SpriteVisibilityTracker(int maximumSprites, UploadSink sink) {
        if (sink == null) throw new IllegalArgumentException("sink");
        this.maximumSprites = Math.max(64, maximumSprites);
        this.maximumPendingBytes = 0L;
        this.catchUpSink = null;
        this.legacySink = sink;
        this.publicationHook = NO_PUBLICATION_HOOK;
        this.budget = null;
    }

    public SpriteVisibilityTracker(int maximumSprites, long maximumPendingBytes,
                                   CatchUpSink sink) {
        this(maximumSprites, maximumPendingBytes, sink,
            NO_PUBLICATION_HOOK, null);
    }

    public SpriteVisibilityTracker(int maximumSprites, long maximumPendingBytes,
                                   CatchUpSink sink, CacheBudget budget) {
        this(maximumSprites, maximumPendingBytes, sink,
            NO_PUBLICATION_HOOK, budget);
    }

    SpriteVisibilityTracker(int maximumSprites, long maximumPendingBytes,
                            CatchUpSink sink,
                            PublicationHook publicationHook) {
        this(maximumSprites, maximumPendingBytes, sink, publicationHook, null);
    }

    SpriteVisibilityTracker(int maximumSprites, long maximumPendingBytes,
                            CatchUpSink sink,
                            PublicationHook publicationHook,
                            CacheBudget budget) {
        if (sink == null) throw new IllegalArgumentException("catch-up sink");
        if (publicationHook == null) {
            throw new IllegalArgumentException("publication hook");
        }
        this.maximumSprites = Math.max(64, maximumSprites);
        this.maximumPendingBytes = Math.max(4096L, maximumPendingBytes);
        this.catchUpSink = sink;
        this.legacySink = null;
        this.publicationHook = publicationHook;
        this.budget = budget;
    }

    public synchronized void beginFrame(long frameId, long resources,
                                        long atlas) {
        if (catchUpSink == null || frameId <= 0L || resources <= 0L
            || atlas <= 0L) return;
        if (resourceGeneration != resources || atlasGeneration != atlas) {
            invalidateModern(resources, atlas);
        }
        boolean incompletePreviousFrame = openFrame != Long.MIN_VALUE;
        if (incompletePreviousFrame) {
            lastCompleteFrame = Long.MIN_VALUE;
        }
        openFrame = frameId;
        // A duplicate begin or a missing end is a completeness failure.  Do
        // not clear it merely because the next frame has now opened.
        frameUnknown = incompletePreviousFrame;
    }

    public synchronized void endFrame(long frameId, boolean completed) {
        if (catchUpSink == null || frameId != openFrame) return;
        if (completed && trusted && terrainSourceSupported
            && bufferSourceSupported && !frameUnknown) {
            lastCompleteFrame = frameId;
        } else {
            lastCompleteFrame = Long.MIN_VALUE;
            if (frameUnknown) unknownFrames++;
        }
        openFrame = Long.MIN_VALUE;
        frameUnknown = false;
    }

    public synchronized void abortFrame(long frameId) {
        if (frameId == openFrame) {
            openFrame = Long.MIN_VALUE;
            frameUnknown = false;
        }
        lastCompleteFrame = Long.MIN_VALUE;
    }

    public synchronized void observeTerrainSource(boolean supported) {
        terrainSourceSupported |= supported;
        if (!supported) markUnknownInternal();
    }

    public synchronized void observeBufferSource(boolean supported) {
        bufferSourceSupported |= supported;
        if (!supported) markUnknownInternal();
    }

    public synchronized boolean sourcesReady() {
        return trusted && terrainSourceSupported && bufferSourceSupported;
    }

    /** Registers an animated sprite without retaining any of its frame arrays. */
    public synchronized boolean register(Object sprite, int spriteIndex,
                                         long resources, long atlas) {
        if (catchUpSink == null || sprite == null || spriteIndex < 0
            || resources <= 0L || atlas <= 0L) return false;
        if (resourceGeneration != resources || atlasGeneration != atlas) {
            invalidateModern(resources, atlas);
        }
        Entry entry = byIdentity.get(sprite);
        if (entry != null) {
            if (entry.index != spriteIndex) {
                // An index move inside one atlas generation is unexpected.
                // Keep a pending frame associated with its original mapping
                // and let the caller execute the normal upload instead.
                if (entry.pending != null) return false;
                Entry collision = byIndex.get(Integer.valueOf(spriteIndex));
                if (collision != null && collision != entry) {
                    // Never overwrite the reverse mapping of another live
                    // sprite.  Doing so leaves it reachable by identity but
                    // invisible to BitSet catch-up and can later upload an
                    // obsolete animation frame.
                    rejected++;
                    return false;
                }
                int oldIndex = entry.index;
                byIndex.remove(Integer.valueOf(oldIndex));
                entry.index = spriteIndex;
                try {
                    byIndex.put(Integer.valueOf(spriteIndex), entry);
                    publicationHook.afterIndexPut();
                } catch (Throwable publicationFailure) {
                    entry.index = oldIndex;
                    try {
                        Integer newKey = Integer.valueOf(spriteIndex);
                        if (byIndex.get(newKey) == entry) {
                            Entry removed = byIndex.remove(newKey);
                            if (removed != entry) {
                                throw new IllegalStateException(
                                    "sprite index move rollback removal failed");
                            }
                        }
                        Integer oldKey = Integer.valueOf(oldIndex);
                        byIndex.put(oldKey, entry);
                        if (byIndex.get(oldKey) != entry
                            || byIndex.get(newKey) == entry) {
                            throw new IllegalStateException(
                                "sprite index move rollback verification failed");
                        }
                    } catch (Throwable rollbackFailure) {
                        addSuppressed(publicationFailure, rollbackFailure);
                        trusted = false;
                        lastCompleteFrame = Long.MIN_VALUE;
                        lastFailure = publicationFailure;
                        FatalErrors.rethrowIfFatal(rollbackFailure);
                    }
                    FatalErrors.rethrowIfFatal(publicationFailure);
                    throw publicationFailure;
                }
            }
            return true;
        }
        Entry indexed = byIndex.get(Integer.valueOf(spriteIndex));
        if (indexed != null && indexed.sprite != sprite) {
            if (indexed.pending != null) return false;
        }
        boolean replacingIndexed = indexed != null && indexed.sprite != sprite;
        if (!replacingIndexed && byIdentity.size() >= maximumSprites
            && !evictIdle()) {
            rejected++;
            return false;
        }
        CacheBudget.Reservation entryReservation = tryReserveHeap(
            ENTRY_HEAP_BYTES);
        if (entryReservation == null) {
            rejected++;
            return false;
        }
        entry = new Entry(sprite, spriteIndex, entryReservation);
        try {
            if (replacingIndexed) {
                Entry removedIdentity = byIdentity.remove(indexed.sprite);
                Entry removedIndex = byIndex.remove(Integer.valueOf(spriteIndex));
                if (removedIdentity != indexed || removedIndex != indexed) {
                    throw new IllegalStateException(
                        "sprite replacement removed an unexpected entry");
                }
            }
            byIdentity.put(sprite, entry);
            byIndex.put(Integer.valueOf(spriteIndex), entry);
            publicationHook.afterIndexPut();
        } catch (Throwable publicationFailure) {
            Throwable failure = publicationFailure;
            boolean rollbackCertain = true;
            try {
                Entry mapped = byIdentity.get(sprite);
                if (mapped == entry) {
                    Entry removed = byIdentity.remove(sprite);
                    if (removed != entry || byIdentity.get(sprite) != null) {
                        throw new IllegalStateException(
                            "sprite identity rollback removal failed");
                    }
                } else if (mapped != null) {
                    throw new IllegalStateException(
                        "sprite identity rollback found foreign entry");
                }
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = appendFailure(failure, rollbackFailure);
            }
            try {
                Integer indexKey = Integer.valueOf(spriteIndex);
                Entry mapped = byIndex.get(indexKey);
                if (mapped == entry) {
                    Entry removed = byIndex.remove(indexKey);
                    if (removed != entry || byIndex.get(indexKey) != null) {
                        throw new IllegalStateException(
                            "sprite reverse-index rollback removal failed");
                    }
                } else if (mapped != null && mapped != indexed) {
                    throw new IllegalStateException(
                        "sprite reverse-index rollback found foreign entry");
                }
            } catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = appendFailure(failure, rollbackFailure);
            }
            try { entry.close(); }
            catch (Throwable rollbackFailure) {
                rollbackCertain = false;
                failure = appendFailure(failure, rollbackFailure);
            }
            if (replacingIndexed) {
                try {
                    byIdentity.put(indexed.sprite, indexed);
                    byIndex.put(Integer.valueOf(spriteIndex), indexed);
                    if (byIdentity.get(indexed.sprite) != indexed
                        || byIndex.get(Integer.valueOf(spriteIndex)) != indexed) {
                        throw new IllegalStateException(
                            "sprite replacement rollback verification failed");
                    }
                } catch (Throwable rollbackFailure) {
                    rollbackCertain = false;
                    failure = appendFailure(failure, rollbackFailure);
                }
            }
            if (!rollbackCertain) {
                trusted = false;
                lastCompleteFrame = Long.MIN_VALUE;
                lastFailure = failure;
            }
            FatalErrors.rethrowIfFatal(failure);
            rethrow(failure);
            return false;
        }
        if (replacingIndexed) {
            indexed.close();
            evicted++;
        }
        return true;
    }

    /**
     * Returns true only when the original GPU upload was safely replaced by a
     * bounded deep-copied pending frame.
     */
    public synchronized boolean deferIfInvisible(Object sprite, int spriteIndex,
                                                 int textureId, int[][] pixels,
                                                 int width, int height,
                                                 int originX, int originY,
                                                 boolean blur, boolean clamp,
                                                 long visibilityFrame,
                                                 long resources, long atlas) {
        if (catchUpSink == null || !trusted || sprite == null || textureId <= 0
            || visibilityFrame <= 0L || resources != resourceGeneration
            || atlas != atlasGeneration || lastCompleteFrame != visibilityFrame
            || originX < 0 || originY < 0) return false;
        Entry entry = byIdentity.get(sprite);
        if (entry == null || entry.index != spriteIndex
            || entry.lastVisibleFrame >= visibilityFrame) return false;
        long bytes = AnimationTextureCommandQueue.validatedByteCount(
            pixels, width, height);
        if (bytes <= 0L) return false;
        long old = entry.pending == null ? 0L : entry.pending.byteCount;
        long retained = Math.max(0L, pendingBytes - old);
        if (bytes > maximumPendingBytes - retained) {
            rejected++;
            return false;
        }
        if (sequence == Long.MAX_VALUE) {
            rejected++;
            lastFailure = new IllegalStateException(
                "deferred texture sequence exhausted");
            trusted = false;
            return false;
        }
        DeferredUpload replacement;
        CacheBudget.Reservation reservation;
        try {
            reservation = tryReserveHeap(retainedPixelHeapBytes(pixels,
                width, height));
        } catch (RuntimeException invalid) {
            FatalErrors.rethrowIfFatal(invalid);
            rejected++;
            lastFailure = invalid;
            return false;
        }
        if (reservation == null) {
            rejected++;
            return false;
        }
        try {
            replacement = new DeferredUpload(sprite, spriteIndex, textureId,
                pixels, width, height, originX, originY, blur, clamp,
                resources, atlas, sequence + 1L, bytes, reservation);
            reservation = null;
        } catch (Throwable copyFailure) {
            Throwable failure = copyFailure;
            if (reservation != null) try { reservation.close(); }
            catch (Throwable cleanupFailure) {
                failure = appendFailure(failure, cleanupFailure);
            }
            FatalErrors.rethrowIfFatal(failure);
            rejected++;
            lastFailure = failure;
            return false;
        }
        sequence++;
        DeferredUpload previous = entry.pending;
        entry.pending = replacement;
        pendingBytes = retained + bytes;
        if (previous != null) previous.close();
        deferred++;
        deferredBytes = safeAdd(deferredBytes, bytes);
        return true;
    }

    /**
     * Records that the current frame will be uploaded by the normal ordered
     * TextureUtil/PBO path.  Any older deferred frame must be discarded first
     * or it could overwrite this newer frame when the sprite becomes visible.
     */
    public synchronized void immediateUpload(Object sprite, int spriteIndex,
                                             long resources, long atlas) {
        if (catchUpSink == null || sprite == null
            || resources != resourceGeneration || atlas != atlasGeneration) return;
        Entry entry = byIdentity.get(sprite);
        if (entry == null || entry.index != spriteIndex) return;
        clearPending(entry);
        immediateUploads++;
    }

    public synchronized boolean markVisible(Object sprite, int spriteIndex,
                                            long frameId, long resources,
                                            long atlas) {
        if (catchUpSink == null || sprite == null || frameId <= 0L) return false;
        if (resources != resourceGeneration || atlas != atlasGeneration) return false;
        Entry entry = byIdentity.get(sprite);
        if (entry == null && spriteIndex >= 0) {
            register(sprite, spriteIndex, resources, atlas);
            entry = byIdentity.get(sprite);
        }
        if (entry == null) return false;
        entry.lastVisibleFrame = Math.max(entry.lastVisibleFrame, frameId);
        return catchUp(entry);
    }

    public synchronized int markVisible(BitSet indices, long frameId,
                                        long resources, long atlas) {
        if (catchUpSink == null || indices == null || frameId <= 0L
            || resources != resourceGeneration || atlas != atlasGeneration) return 0;
        int marked = 0;
        for (int index = indices.nextSetBit(0); index >= 0;
             index = index == Integer.MAX_VALUE ? -1
                 : indices.nextSetBit(index + 1)) {
            Entry entry = byIndex.get(Integer.valueOf(index));
            if (entry == null) continue;
            entry.lastVisibleFrame = Math.max(entry.lastVisibleFrame, frameId);
            catchUp(entry);
            marked++;
        }
        return marked;
    }

    public synchronized boolean hasPending(Object sprite) {
        Entry entry = sprite == null ? null : byIdentity.get(sprite);
        return entry != null && entry.pending != null;
    }

    public synchronized int pendingVisible(BitSet indices) {
        if (indices == null) return 0;
        int pending = 0;
        for (int index = indices.nextSetBit(0); index >= 0;
             index = index == Integer.MAX_VALUE ? -1
                 : indices.nextSetBit(index + 1)) {
            Entry entry = byIndex.get(Integer.valueOf(index));
            if (entry != null && entry.pending != null) pending++;
        }
        return pending;
    }

    /** Unknown atlas draw: catch up everything and reject this visibility frame. */
    public synchronized void markUnknown(long frameId, long resources,
                                         long atlas) {
        if (catchUpSink == null || frameId <= 0L
            || resources != resourceGeneration
            || atlas != atlasGeneration) return;
        markUnknownInternal();
        for (Entry entry : byIdentity.values()) {
            entry.lastVisibleFrame = Math.max(entry.lastVisibleFrame, frameId);
            catchUp(entry);
        }
    }

    public synchronized void invalidate(long resources, long atlas) {
        if (catchUpSink != null) invalidateModern(resources, atlas);
        else legacyEntries.clear();
    }

    public synchronized void invalidate() {
        if (catchUpSink != null) invalidateModern(Long.MIN_VALUE, Long.MIN_VALUE);
        legacyEntries.clear();
    }

    @Override public synchronized void close() { invalidate(); }

    public synchronized Throwable consumeLastFailure() {
        Throwable result = lastFailure;
        lastFailure = null;
        return result;
    }

    public synchronized long getPendingBytes() { return pendingBytes; }
    public synchronized int getTrackedSprites() { return byIdentity.size(); }
    public synchronized long getRejected() { return rejected; }
    public synchronized long getUnknownFrames() { return unknownFrames; }
    public synchronized long getEvicted() { return evicted; }
    public synchronized long getDeferred() { return deferred; }
    public synchronized long getCaughtUp() { return caughtUp; }
    public synchronized long getDeferredBytes() { return deferredBytes; }
    public synchronized long getCaughtUpBytes() { return caughtUpBytes; }
    public synchronized long getImmediateUploads() { return immediateUploads; }
    public synchronized long getLastCompleteFrame() { return lastCompleteFrame; }

    /** Deterministic CPU gate for deep-copy and catch-up state semantics. */
    public static boolean selfTest() {
        final int[] uploaded = new int[1];
        SpriteVisibilityTracker tracker = new SpriteVisibilityTracker(64,
            4096L, new CatchUpSink() {
                @Override public boolean upload(DeferredUpload upload) {
                    int[][] pixels = upload.copyPixels();
                    uploaded[0] = pixels[0][0];
                    return true;
                }
            });
        Object sprite = new Object();
        tracker.beginFrame(1L, 1L, 1L);
        tracker.observeTerrainSource(true);
        tracker.observeBufferSource(true);
        tracker.endFrame(1L, true);
        int[][] source = new int[][] {{7}};
        if (!tracker.register(sprite, 3, 1L, 1L)
            || !tracker.deferIfInvisible(sprite, 3, 5, source, 1, 1,
                0, 0, false, false, 1L, 1L, 1L)) return false;
        source[0][0] = 99;
        tracker.beginFrame(2L, 1L, 1L);
        if (!tracker.markVisible(sprite, 3, 2L, 1L, 1L)) return false;
        tracker.endFrame(2L, true);
        return uploaded[0] == 7 && tracker.getPendingBytes() == 0L
            && tracker.getCaughtUp() == 1L;
    }

    // ---------------------------------------------------------------------
    // Previous immutable TextureUpload API.

    public synchronized boolean animationAdvanced(long spriteId, long frameId,
                                                   int animationFrame,
                                                   TextureUpload upload) {
        if (legacySink == null || spriteId <= 0L || frameId < 0L
            || animationFrame < 0 || upload == null
            || upload.getSpriteId() != spriteId) return false;
        LegacyEntry entry = legacyEntry(spriteId);
        entry.animationFrame = animationFrame;
        if (entry.lastVisibleFrame >= frameId - 1L) {
            entry.pending = null;
            return legacySink.offer(upload);
        }
        entry.pending = upload;
        deferred++;
        return true;
    }

    public synchronized boolean markVisible(long spriteId, long frameId) {
        if (legacySink == null || spriteId <= 0L || frameId < 0L) return false;
        LegacyEntry entry = legacyEntry(spriteId);
        entry.lastVisibleFrame = Math.max(entry.lastVisibleFrame, frameId);
        TextureUpload pending = entry.pending;
        if (pending == null) return true;
        if (!legacySink.offer(pending)) return false;
        entry.pending = null;
        caughtUp++;
        return true;
    }

    private boolean catchUp(Entry entry) {
        DeferredUpload pending = entry.pending;
        if (pending == null) return true;
        if (pending.resourceGeneration != resourceGeneration
            || pending.atlasGeneration != atlasGeneration) {
            clearPending(entry);
            return false;
        }
        try {
            if (!catchUpSink.upload(pending)) return false;
            clearPending(entry);
            caughtUp++;
            caughtUpBytes = safeAdd(caughtUpBytes, pending.byteCount);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            trusted = false;
            lastCompleteFrame = Long.MIN_VALUE;
            lastFailure = failure;
            return false;
        }
    }

    private void clearPending(Entry entry) {
        DeferredUpload pending = entry.pending;
        if (pending == null) return;
        pendingBytes = Math.max(0L, pendingBytes - pending.byteCount);
        entry.pending = null;
        pending.close();
    }

    private void markUnknownInternal() {
        frameUnknown = true;
        lastCompleteFrame = Long.MIN_VALUE;
    }

    private void invalidateModern(long resources, long atlas) {
        for (Entry entry : byIdentity.values()) entry.close();
        byIdentity.clear();
        byIndex.clear();
        pendingBytes = 0L;
        openFrame = Long.MIN_VALUE;
        lastCompleteFrame = Long.MIN_VALUE;
        resourceGeneration = resources;
        atlasGeneration = atlas;
        frameUnknown = false;
        terrainSourceSupported = false;
        bufferSourceSupported = false;
        trusted = true;
        lastFailure = null;
    }

    private boolean evictIdle() {
        Iterator<Map.Entry<Integer, Entry>> iterator =
            byIndex.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Entry> candidate = iterator.next();
            Entry value = candidate.getValue();
            if (value.pending != null) continue;
            iterator.remove();
            byIdentity.remove(value.sprite);
            value.close();
            evicted++;
            return true;
        }
        return false;
    }

    private LegacyEntry legacyEntry(long spriteId) {
        Long key = Long.valueOf(spriteId);
        LegacyEntry value = legacyEntries.get(key);
        if (value == null) {
            value = new LegacyEntry();
            legacyEntries.put(key, value);
            while (legacyEntries.size() > maximumSprites) {
                Map.Entry<Long, LegacyEntry> eldest =
                    legacyEntries.entrySet().iterator().next();
                legacyEntries.remove(eldest.getKey());
                evicted++;
            }
        }
        return value;
    }

    private static int[][] copyLevels(int[][] source, int width, int height) {
        int levels = AnimationTextureCommandQueue.usableMipLevels(source,
            width, height);
        int[][] copy = new int[levels][];
        for (int mip = 0; mip < levels; mip++) {
            int count = Math.multiplyExact(width >> mip, height >> mip);
            copy[mip] = new int[count];
            System.arraycopy(source[mip], 0, copy[mip], 0, count);
        }
        return copy;
    }

    private CacheBudget.Reservation tryReserveHeap(long bytes) {
        if (budget == null) return CacheBudget.Reservation.empty();
        return budget.tryReserve(BudgetKind.HEAP, bytes);
    }

    private static long retainedPixelHeapBytes(int[][] source, int width,
                                               int height) {
        int levels = AnimationTextureCommandQueue.usableMipLevels(source,
            width, height);
        long bytes = Math.addExact(DEFERRED_UPLOAD_ENVELOPE_BYTES,
            RetainedHeap.referenceArray(levels));
        for (int mip = 0; mip < levels; mip++) {
            int count = Math.multiplyExact(width >> mip, height >> mip);
            bytes = Math.addExact(bytes, RetainedHeap.intArray(count));
        }
        return bytes;
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
        addSuppressed(first, next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("sprite registry operation failed",
            failure);
    }

    private static void addSuppressed(Throwable first, Throwable next) {
        if (first == null || next == null || first == next) return;
        first.addSuppressed(next);
    }

    private static final class Entry {
        private final Object sprite;
        private int index;
        private long lastVisibleFrame = Long.MIN_VALUE;
        private DeferredUpload pending;
        private CacheBudget.Reservation reservation;
        private Entry(Object sprite, int index,
                      CacheBudget.Reservation reservation) {
            this.sprite = sprite;
            this.index = index;
            this.reservation = reservation;
        }
        private void close() {
            DeferredUpload upload = pending;
            pending = null;
            if (upload != null) upload.close();
            CacheBudget.Reservation owned = reservation;
            reservation = null;
            if (owned != null) owned.close();
        }
    }

    private static final class LegacyEntry {
        private long lastVisibleFrame = Long.MIN_VALUE;
        private int animationFrame;
        private TextureUpload pending;
    }
}

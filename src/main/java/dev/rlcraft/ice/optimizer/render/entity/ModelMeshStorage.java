package dev.rlcraft.ice.optimizer.render.entity;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.arena.ArenaRange;
import dev.rlcraft.ice.optimizer.render.arena.GpuArenaAllocator;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import org.lwjgl.opengl.GL15;

/** Shared, bounded model VBO pages. Allocation and Fence polling are cold paths. */
final class ModelMeshStorage {
    static final int PAGE_BYTES = 96 * 1024;
    private static final int MAX_PAGES = 256;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int COLLECTION_CHECKS = 64;

    interface Driver {
        int create();
        void allocate(int buffer, int bytes);
        void upload(int buffer, long offset, ByteBuffer vertices);
        void delete(int buffer);
        ResourceLedger.RetirementFence fence(ResourceLedger resources);
    }

    private final ResourceLedger resources;
    private final CacheBudget budget;
    private final Driver driver;
    private final int pageBytes;
    private final int stride;
    private final ArrayList<Page> pages = new ArrayList<Page>();
    private long nextAllocationId = 1L;

    ModelMeshStorage(ResourceLedger resources, CacheBudget budget, int stride) {
        this(resources, budget, stride, PAGE_BYTES, new LwjglDriver());
    }

    ModelMeshStorage(ResourceLedger resources, CacheBudget budget, int stride,
                     int pageBytes, Driver driver) {
        if (resources == null || driver == null || stride <= 0
            || stride % 8 != 0 || pageBytes <= 0 || pageBytes % stride != 0) {
            throw new IllegalArgumentException("model mesh storage");
        }
        this.resources = resources;
        this.budget = budget;
        this.stride = stride;
        this.pageBytes = pageBytes;
        this.driver = driver;
    }

    Allocation allocate(int bytes, FrameStamp stamp) {
        if (bytes <= 0 || bytes > MAX_PAYLOAD_BYTES || bytes % stride != 0
            || stamp == null) return null;
        collect(COLLECTION_CHECKS);
        for (Page page : pages) {
            if (page.closed || !page.handle.belongsTo(
                stamp.getResourceGeneration(), stamp.getGlContextGeneration())) continue;
            ArenaRange range = page.ranges.allocate(bytes);
            if (range != null) return allocation(page, range);
        }
        if (pages.size() >= MAX_PAGES) return null;
        int capacity = Math.max(pageBytes, bytes);
        Page page = createPage(capacity, stamp);
        if (page == null) return null;
        ArenaRange range = page.ranges.allocate(bytes);
        if (range != null) return allocation(page, range);
        retirePage(page);
        return null;
    }

    void upload(Allocation allocation, ByteBuffer vertices) {
        driver.upload(allocation.page.handle.getNativeId(),
            allocation.range.getOffset(), vertices);
    }

    /** An unpublished range has never been used by a draw and needs no Fence. */
    void cancel(Allocation allocation) {
        if (allocation == null || !allocation.live) return;
        Page page = allocation.page;
        if (page.liveRanges == 1) {
            retirePage(page);
        } else {
            page.ranges.free(allocation.range);
            page.liveRanges--;
        }
        allocation.live = false;
    }

    void retire(Allocation allocation) {
        if (allocation == null || !allocation.live) return;
        Page page = allocation.page;
        if (page.liveRanges == 1) {
            retirePage(page);
        } else {
            // Allocation itself is the queue node; publication requires no new object.
            allocation.fence = driver.fence(resources);
            allocation.next = page.pending;
            page.pending = allocation;
            page.liveRanges--;
        }
        allocation.live = false;
    }

    /** Zero-timeout range collection, never called by a draw. */
    int collect(int maximumChecks) {
        int checks = 0;
        int released = 0;
        for (Page page : pages) {
            if (page.closed) continue;
            Allocation previous = null;
            Allocation value = page.pending;
            while (value != null && checks < maximumChecks) {
                Allocation next = value.next;
                if (!value.failedFence) {
                    checks++;
                    boolean ready;
                    try { ready = value.fence == null || value.fence.isSignaled(); }
                    catch (Throwable failure) {
                        value.failedFence = true;
                        rethrow(failure);
                        return released;
                    }
                    if (ready) {
                        // Destroy before freeing: an uncertain Fence cleanup leaves
                        // the range retained, rather than enabling a second upload.
                        try { if (value.fence != null) value.fence.destroy(); }
                        catch (Throwable failure) {
                            value.failedFence = true;
                            rethrow(failure);
                        }
                        page.ranges.free(value.range);
                        if (previous == null) page.pending = next;
                        else previous.next = next;
                        value.fence = null;
                        value.next = null;
                        released++;
                        value = next;
                        continue;
                    }
                }
                previous = value;
                value = next;
            }
        }
        return released;
    }

    void reset(boolean contextValid) {
        Throwable failure = null;
        Iterator<Page> iterator = pages.iterator();
        while (iterator.hasNext()) {
            Page page = iterator.next();
            if (contextValid && !page.closed) {
                try { resources.retire(page.handle, driver.fence(resources)); }
                catch (Throwable error) {
                    failure = appendFailure(failure, error);
                    if (resources.isLive(page.handle)) continue;
                }
            }
            page.closed = true;
            try { releasePageMetadata(page, contextValid); }
            catch (Throwable error) {
                failure = appendFailure(failure, error);
                if (contextValid) continue;
            }
            iterator.remove();
        }
        if (failure != null) rethrow(failure);
    }

    int pageCount() { return pages.size(); }

    private Allocation allocation(Page page, ArenaRange range) {
        Allocation value;
        try {
            if (nextAllocationId == Long.MAX_VALUE) {
                throw new IllegalStateException("model mesh allocation id exhausted");
            }
            value = new Allocation(nextAllocationId++, page, range, stride);
        } catch (Throwable failure) {
            page.ranges.free(range);
            throw failure;
        }
        page.liveRanges++;
        return value;
    }

    private Page createPage(int capacity, FrameStamp stamp) {
        CacheBudget.Reservation reservation = resources.reserveGpu(capacity);
        if (reservation == null) return null;
        int buffer = 0;
        boolean creationReturned = false;
        RenderHandle handle = null;
        Throwable failure = null;
        try {
            buffer = driver.create();
            creationReturned = true;
            if (buffer <= 0) return null;
            driver.allocate(buffer, capacity);
            handle = resources.registerReserved(RenderResourceKind.BUFFER,
                buffer, capacity, stamp.getResourceGeneration(),
                stamp.getGlContextGeneration(), reservation);
            if (handle == null) return null;
            buffer = 0;
            reservation = null;
            Page page = new Page(handle, new GpuArenaAllocator(capacity,
                capacity, 8L, stamp.getGlContextGeneration(), budget));
            pages.add(page);
            handle = null;
            return page;
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (handle != null) try {
                resources.retire(handle, driver.fence(resources));
            } catch (Throwable error) { failure = appendFailure(failure, error); }
            boolean deleted = false;
            if (buffer > 0) try {
                driver.delete(buffer);
                deleted = true;
            } catch (Throwable error) { failure = appendFailure(failure, error); }
            if (reservation != null && (deleted || creationReturned && buffer <= 0)) {
                try { reservation.close(); }
                catch (Throwable error) { failure = appendFailure(failure, error); }
            }
            if (failure != null) rethrow(failure);
        }
        return null;
    }

    private void retirePage(Page page) {
        try { resources.retire(page.handle, driver.fence(resources)); }
        catch (Throwable failure) {
            // The ledger may have completed ownership transfer before throwing.
            if (!resources.isLive(page.handle)) page.closed = true;
            rethrow(failure);
        }
        page.closed = true;
        releasePageMetadata(page, true);
        pages.remove(page);
    }

    private static void releasePageMetadata(Page page, boolean contextValid) {
        Throwable failure = null;
        for (Allocation value = page.pending; value != null; value = value.next) {
            try {
                if (contextValid) {
                    if (value.fence != null) value.fence.destroy();
                } else LwjglRetirementFence.abandon(value.fence);
            } catch (Throwable error) { failure = appendFailure(failure, error); }
        }
        try { page.ranges.close(); }
        catch (Throwable error) { failure = appendFailure(failure, error); }
        if (failure == null) page.pending = null;
        if (failure != null) rethrow(failure);
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        Throwable fatal = FatalErrors.findFatal(next);
        if (fatal != null && FatalErrors.findFatal(first) == null) {
            if (fatal != first) fatal.addSuppressed(first);
            return fatal;
        }
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("model mesh storage operation failed", failure);
    }

    static final class Allocation {
        final long id;
        final Page page;
        final ArenaRange range;
        final int firstVertex;
        private boolean live = true;
        private ResourceLedger.RetirementFence fence;
        private Allocation next;
        private boolean failedFence;

        private Allocation(long id, Page page, ArenaRange range, int stride) {
            this.id = id;
            this.page = page;
            this.range = range;
            this.firstVertex = (int) (range.getOffset() / stride);
        }

        RenderHandle handle() { return page.handle; }
        boolean isLive() { return live && !page.closed; }
    }

    private static final class Page {
        private final RenderHandle handle;
        private final GpuArenaAllocator ranges;
        private int liveRanges;
        private Allocation pending;
        private boolean closed;

        private Page(RenderHandle handle, GpuArenaAllocator ranges) {
            this.handle = handle;
            this.ranges = ranges;
        }
    }

    private static final class LwjglDriver implements Driver {
        @Override public int create() { return GL15.glGenBuffers(); }
        @Override public void allocate(int buffer, int bytes) {
            bind(buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) bytes, GL15.GL_STATIC_DRAW);
        }
        @Override public void upload(int buffer, long offset, ByteBuffer vertices) {
            bind(buffer);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, vertices);
        }
        @Override public void delete(int buffer) { GL15.glDeleteBuffers(buffer); }
        @Override public ResourceLedger.RetirementFence fence(ResourceLedger ledger) {
            return LwjglRetirementFence.afterCurrentCommands(ledger);
        }
        private static void bind(int buffer) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            EarlyGlStateTracker.bindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        }
    }
}

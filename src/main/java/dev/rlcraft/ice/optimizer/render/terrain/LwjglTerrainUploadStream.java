package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyGlStateTracker;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.LwjglRetirementFence;
import dev.rlcraft.ice.optimizer.render.resource.RenderHandle;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.ARBCopyBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GLContext;

/**
 * CPU-visible upload storage is separate from the long-lived draw buffers.
 * A slot is append-only until sealed. Its fence covers every copy of that
 * prefix; a busy slot is never waited on or overwritten. GPU copies preserve
 * the normal GL ordering with both previous draws and replacement uploads.
 */
final class LwjglTerrainUploadStream {
    static final int SLOT_BYTES = 1024 * 1024;
    private final ResourceLedger ledger;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private Slot active;
    private int cursor;
    private boolean disabled;

    LwjglTerrainUploadStream(ResourceLedger ledger) { this.ledger = ledger; }

    boolean upload(RenderHandle target, int targetOffset, ByteBuffer source) {
        int bytes = source.remaining();
        if (disabled || bytes <= 0 || bytes > SLOT_BYTES) return false;
        int oldRead = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        int oldWrite = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER_BINDING);
        Throwable failure = null;
        boolean copied = false;
        try {
            if (active != null && !active.handle.belongsTo(target.getResourceGeneration(), target.getContextGeneration()))
                throw new IllegalStateException("active terrain upload generation mismatch");
            if (active != null && SLOT_BYTES - active.used < bytes) seal();
            if (active == null) active = acquire(target);
            if (active != null) {
                Slot slot = active;
                int offset = slot.used;
                // Claim the bytes before touching native state. An uncertain
                // copy must never permit a subsequent CPU write to this range.
                slot.used += (bytes + 15) & ~15;
                ByteBuffer destination = slot.mapped.duplicate().order(ByteOrder.nativeOrder());
                destination.position(offset).limit(offset + bytes);
                destination.put(source.duplicate());
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, slot.handle.getNativeId());
                GL30.glFlushMappedBufferRange(GL31.GL_COPY_READ_BUFFER, offset, bytes);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, target.getNativeId());
                if (GLContext.getCapabilities().OpenGL31)
                    GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                        offset, targetOffset, bytes);
                else ARBCopyBuffer.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                    offset, targetOffset, bytes);
                copied = true;
            }
        } catch (Throwable error) {
            failure = error;
            disabled = true;
            if (active != null) active.poisoned = true;
        } finally {
            try { GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldRead); }
            catch (Throwable error) { failure = append(failure, error); }
            try { GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, oldWrite); }
            catch (Throwable error) { failure = append(failure, error); }
        }
        if (failure != null) {
            disabled = true;
            EarlyGlStateTracker.invalidate();
            rethrow(failure);
        }
        return copied;
    }

    private Slot acquire(RenderHandle target) {
        for (int checked = 0; checked < 2; checked++) {
            Slot slot = slots[cursor];
            cursor = (cursor + 1) % slots.length;
            if (slot.poisoned) continue;
            if (slot.fence != null) {
                if (!slot.fence.isSignaled()) continue;
                ResourceLedger.RetirementFence fence = slot.fence;
                // Retain the reference and quarantine on a failed deletion.
                try { fence.destroy(); }
                catch (Throwable error) { slot.poisoned = true; throw error; }
                slot.fence = null;
                slot.used = 0;
            }
            if (slot.handle == null && !allocate(slot, target)) continue;
            if (!slot.handle.belongsTo(target.getResourceGeneration(), target.getContextGeneration()))
                throw new IllegalStateException("terrain upload generation mismatch");
            return slot;
        }
        return null;
    }

    private boolean allocate(Slot slot, RenderHandle target) {
        CacheBudget.Reservation reservation = ledger.reserveGpu(SLOT_BYTES);
        if (reservation == null) return false;
        int created = 0;
        ByteBuffer mapped = null;
        boolean allocationReturned = false;
        Throwable failure = null;
        try {
            created = GL15.glGenBuffers();
            allocationReturned = true;
            if (created <= 0) throw new IllegalStateException("terrain upload buffer name");
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, created);
            int flags = GL30.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
            ARBBufferStorage.glBufferStorage(GL31.GL_COPY_READ_BUFFER, SLOT_BYTES, flags);
            mapped = GL30.glMapBufferRange(GL31.GL_COPY_READ_BUFFER, 0, SLOT_BYTES,
                flags | GL30.GL_MAP_FLUSH_EXPLICIT_BIT, null);
            if (mapped == null) throw new IllegalStateException("terrain upload mapping unavailable");
            RenderHandle handle = ledger.registerReserved(RenderResourceKind.BUFFER,
                created, SLOT_BYTES, target.getResourceGeneration(), target.getContextGeneration(), reservation);
            if (handle != null) {
                slot.handle = handle;
                slot.mapped = mapped;
                return true;
            }
        } catch (Throwable error) { failure = error; }
        if (mapped != null) try {
            if (!GL15.glUnmapBuffer(GL31.GL_COPY_READ_BUFFER))
                throw new IllegalStateException("terrain upload cleanup unmap failed");
        } catch (Throwable error) { failure = append(failure, error); }
        boolean deleted = false;
        if (created > 0) try { GL15.glDeleteBuffers(created); deleted = true; }
        catch (Throwable error) { failure = append(failure, error); }
        // An unknown allocation/deletion outcome keeps its hard budget charge.
        if (deleted || allocationReturned && created <= 0) try { reservation.close(); }
        catch (Throwable error) { failure = append(failure, error); }
        if (failure != null) rethrow(failure);
        return false;
    }

    void endFrame() { seal(); }

    private void seal() {
        Slot slot = active;
        active = null;
        if (slot == null || slot.used == 0) return;
        // afterCurrentCommands supplies a permanently busy sentinel if fence
        // creation fails, retaining submitted bytes until a context transition.
        try { slot.fence = LwjglRetirementFence.afterCurrentCommands(ledger); }
        catch (Throwable error) { slot.poisoned = true; disabled = true; throw error; }
    }

    boolean isMapped() {
        for (Slot slot : slots) if (slot.mapped != null) return true;
        return false;
    }

    long allocatedBytes() {
        long bytes = 0L;
        for (Slot slot : slots) if (slot.handle != null) bytes += SLOT_BYTES;
        return bytes;
    }

    void close(boolean contextValid) {
        active = null;
        Throwable failure = null;
        int oldRead = Integer.MIN_VALUE;
        if (contextValid && isMapped()) try {
            oldRead = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER_BINDING);
        } catch (Throwable error) { failure = error; }
        for (Slot slot : slots) {
            if (!contextValid) {
                LwjglRetirementFence.abandon(slot.fence);
                slot.clear(); // The caller's ledger abandons the lost context.
                continue;
            }
            if (slot.handle == null) continue;
            if (oldRead != Integer.MIN_VALUE && slot.mapped != null) try {
                slot.mapped = null;
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, slot.handle.getNativeId());
                if (!GL15.glUnmapBuffer(GL31.GL_COPY_READ_BUFFER))
                    throw new IllegalStateException("terrain upload unmap failed");
            } catch (Throwable error) { failure = append(failure, error); }
            try {
                ResourceLedger.RetirementFence fence = slot.fence;
                slot.fence = null;
                if (fence == null && slot.used != 0)
                    fence = LwjglRetirementFence.afterCurrentCommands(ledger);
                ledger.retire(slot.handle, fence);
                slot.clear();
            } catch (Throwable error) { failure = append(failure, error); slot.poisoned = true; }
        }
        if (oldRead != Integer.MIN_VALUE) try {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldRead);
        } catch (Throwable error) { failure = append(failure, error); EarlyGlStateTracker.invalidate(); }
        disabled = failure != null;
        cursor = 0;
        if (failure != null) rethrow(failure);
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw new IllegalStateException("terrain upload stream", failure);
    }

    private static final class Slot {
        RenderHandle handle;
        ByteBuffer mapped;
        ResourceLedger.RetirementFence fence;
        int used;
        boolean poisoned;
        void clear() { handle = null; mapped = null; fence = null; used = 0; poisoned = false; }
    }
}

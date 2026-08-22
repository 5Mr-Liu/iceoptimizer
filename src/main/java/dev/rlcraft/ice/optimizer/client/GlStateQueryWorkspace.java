package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.memory.BudgetKind;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;

/**
 * Reusable, budgeted storage for the bounded GL state queries performed at
 * graph initialization and observable HUD barriers.
 */
final class GlStateQueryWorkspace implements AutoCloseable {
    // LWJGL 2's generated glGet*v wrappers require at least 16 remaining
    // elements even for fixed-size pnames such as GL_DEPTH_RANGE or
    // GL_VIEWPORT. Keep each reusable view independently large enough so a
    // query can never overlap the next state slot.
    static final int QUERY_ELEMENTS = 16;
    static final int DIRECT_BYTES = 464;
    static final int HEAP_BYTES = 256;

    private CacheBudget.Reservation directReservation;
    private CacheBudget.Reservation heapReservation;
    private ByteBuffer storage;
    private FloatBuffer depthRange;
    private FloatBuffer currentColor;
    private FloatBuffer modelView;
    private FloatBuffer projection;
    private FloatBuffer textureMatrix;
    private IntBuffer viewport;
    private IntBuffer scissorBox;
    private ByteBuffer colorMask;
    private int[] textures2d;
    private boolean[] texture2dEnabled;

    GlStateQueryWorkspace(CacheBudget budget) {
        if (budget == null) throw new IllegalArgumentException(
            "GL state query workspace budget");
        CacheBudget.Reservation direct = budget.tryReserve(BudgetKind.DIRECT,
            DIRECT_BYTES);
        if (direct == null) throw new IllegalStateException(
            "GL state query Direct budget exhausted");
        CacheBudget.Reservation heap = budget.tryReserve(BudgetKind.HEAP,
            HEAP_BYTES);
        if (heap == null) {
            direct.close();
            throw new IllegalStateException(
                "GL state query Heap budget exhausted");
        }
        try {
            ByteBuffer allocated = BufferUtils.createByteBuffer(DIRECT_BYTES)
                .order(ByteOrder.nativeOrder());
            storage = allocated;
            depthRange = floats(allocated, 0, QUERY_ELEMENTS);
            currentColor = floats(allocated, 64, QUERY_ELEMENTS);
            modelView = floats(allocated, 128, QUERY_ELEMENTS);
            projection = floats(allocated, 192, QUERY_ELEMENTS);
            textureMatrix = floats(allocated, 256, QUERY_ELEMENTS);
            viewport = integers(allocated, 320, QUERY_ELEMENTS);
            scissorBox = integers(allocated, 384, QUERY_ELEMENTS);
            colorMask = bytes(allocated, 448, QUERY_ELEMENTS);
            textures2d = new int[32];
            texture2dEnabled = new boolean[32];
            directReservation = direct;
            heapReservation = heap;
        } catch (Throwable failure) {
            try { heap.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            try { direct.close(); }
            catch (Throwable cleanup) {
                failure = appendFailure(failure, cleanup);
            }
            rethrow(failure);
        }
    }

    FloatBuffer depthRange() { return clear(depthRange, "depth range"); }
    FloatBuffer currentColor() { return clear(currentColor, "current color"); }
    FloatBuffer modelView() { return clear(modelView, "model-view matrix"); }
    FloatBuffer projection() { return clear(projection, "projection matrix"); }
    FloatBuffer textureMatrix() {
        return clear(textureMatrix, "texture matrix");
    }
    IntBuffer viewport() { return clear(viewport, "viewport"); }
    IntBuffer scissorBox() { return clear(scissorBox, "scissor box"); }
    ByteBuffer colorMask() { return clear(colorMask, "color mask"); }

    int[] textures2d() {
        checkOpen();
        Arrays.fill(textures2d, 0);
        return textures2d;
    }

    boolean[] texture2dEnabled() {
        checkOpen();
        Arrays.fill(texture2dEnabled, false);
        return texture2dEnabled;
    }

    boolean isClosed() { return storage == null; }

    @Override public void close() {
        CacheBudget.Reservation direct = directReservation;
        CacheBudget.Reservation heap = heapReservation;
        directReservation = null;
        heapReservation = null;
        storage = null;
        depthRange = null;
        currentColor = null;
        modelView = null;
        projection = null;
        textureMatrix = null;
        viewport = null;
        scissorBox = null;
        colorMask = null;
        textures2d = null;
        texture2dEnabled = null;
        Throwable failure = null;
        try { if (heap != null) heap.close(); }
        catch (Throwable error) { failure = error; }
        try { if (direct != null) direct.close(); }
        catch (Throwable error) {
            failure = appendFailure(failure, error);
        }
        if (failure != null) rethrow(failure);
    }

    private void checkOpen() {
        if (storage == null) throw new IllegalStateException(
            "GL state query workspace is closed");
    }

    private <T extends java.nio.Buffer> T clear(T buffer, String label) {
        checkOpen();
        if (buffer == null) throw new IllegalStateException(
            "missing GL state query " + label);
        buffer.clear();
        return buffer;
    }

    private static FloatBuffer floats(ByteBuffer source, int offset,
                                      int count) {
        return bytes(source, offset, Math.multiplyExact(count, 4))
            .asFloatBuffer();
    }

    private static IntBuffer integers(ByteBuffer source, int offset,
                                      int count) {
        return bytes(source, offset, Math.multiplyExact(count, 4))
            .asIntBuffer();
    }

    private static ByteBuffer bytes(ByteBuffer source, int offset,
                                    int count) {
        ByteBuffer view = source.duplicate().order(ByteOrder.nativeOrder());
        view.position(offset);
        view.limit(Math.addExact(offset, count));
        return view.slice().order(ByteOrder.nativeOrder());
    }

    private static void rethrow(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("GL state query workspace failed",
            failure);
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
}

package dev.rlcraft.ice.optimizer.compat.gl;

import static org.junit.Assert.*;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class EarlyModelMatrixDeferralTest {
    @Before public void seed() {
        EarlyModelBatchBarrier.leave();
        FloatBuffer identity = FloatBuffer.wrap(new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
        EarlyMatrixStateTracker.seed(5888, identity, identity);
    }
    @After public void cleanup() { EarlyModelBatchBarrier.leave(); }

    @Test public void materialBarriersPublishEverySavedParentAndLaterPopsRetractTheStack() {
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        assertTrue(EarlyModelBatchBarrier.deferTranslate(2F,0F,0F));
        assertTrue(EarlyModelBatchBarrier.deferPush());
        assertTrue(EarlyModelBatchBarrier.deferTranslate(3F,0F,0F));
        assertTrue(EarlyModelBatchBarrier.deferPush());
        assertTrue(EarlyModelBatchBarrier.deferTranslate(4F,0F,0F));
        EarlyModelBatchBarrier.barrier();
        assertEquals(1, publications.size());
        assertEquals(3, publications.get(0).length);
        assertEquals(2F, publications.get(0)[0][12], 0F);
        assertEquals(5F, publications.get(0)[1][12], 0F);
        assertEquals(9F, publications.get(0)[2][12], 0F);
        assertTrue(EarlyModelBatchBarrier.deferPop());
        EarlyModelBatchBarrier.barrier();
        assertEquals(2, publications.get(1).length);
        assertEquals(5F, publications.get(1)[1][12], 0F);
        // Previous snapshots are immutable even while later poses change.
        assertEquals(9F, publications.get(0)[2][12], 0F);
    }

    @Test public void overflowKeepsOldValidTopAvailableUntilTheInvalidationBarrierFinishes() {
        EarlyMatrixStateTracker.scale(Float.MAX_VALUE, 1F, 1F);
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        assertTrue(EarlyModelBatchBarrier.deferTranslate(0F,2F,0F));
        assertFalse(EarlyModelBatchBarrier.deferScale(2F,1F,1F));
        assertEquals(1, publications.size());
        assertEquals(Float.MAX_VALUE, publications.get(0)[0][0], 0F);
        assertEquals(2F, publications.get(0)[0][13], 0F);
        assertFalse(EarlyMatrixStateTracker.isKnown());
        assertFalse(EarlyModelBatchBarrier.deferTranslate(1F,0F,0F));
    }

    @Test public void projectionTransformsUseOrdinaryPathAndFlushModelPosesBeforeChangingProjection() {
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        assertTrue(EarlyModelBatchBarrier.deferTranslate(3F,0F,0F));
        EarlyMatrixStateTracker.matrixMode(5889);
        assertFalse(EarlyModelBatchBarrier.deferTranslate(1F,2F,3F));
        EarlyModelBatchBarrier.matrixBarrier();
        assertEquals(3F, publications.get(0)[0][12], 0F);
        EarlyMatrixStateTracker.translate(1F,2F,3F);
        assertEquals(1F, EarlyMatrixStateTracker.snapshot().getProjection()[12], 0F);
        assertEquals(3F, EarlyMatrixStateTracker.modelView()[12], 0F);
    }

    @Test public void directMatrixViewsAccumulateWithoutFlushingOrChangingBufferBounds() {
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        for (int offset : new int[] {0, 3, 9}) {
            FloatBuffer source = directMatrix(offset, ByteOrder.nativeOrder());
            source.put(offset + 12, 2F);
            source.mark();
            FloatBuffer view = offset == 3 ? source.asReadOnlyBuffer() : source;
            assertTrue(EarlyModelBatchBarrier.deferMultMatrix(view));
            assertEquals(offset, view.position());
            assertEquals(offset + 16, view.limit());
            source.reset();
        }
        assertEquals(6F, EarlyMatrixStateTracker.modelView()[12], 0F);
        assertTrue(publications.isEmpty());
        EarlyModelBatchBarrier.barrier();
        assertEquals(1, publications.size());
        assertEquals(6F, publications.get(0)[0][12], 0F);
    }

    @Test public void swappedDirectViewUsesNativeStorageValuesLikeOpenGl() {
        ByteOrder opposite = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer bytes = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        FloatBuffer nativeView = bytes.asFloatBuffer();
        for (int i = 0; i < 16; i++) nativeView.put(2 + i, i % 5 == 0 ? 1F : 0F);
        nativeView.put(14, 7F);
        FloatBuffer oppositeView = bytes.order(opposite).asFloatBuffer();
        oppositeView.position(2); oppositeView.limit(18);
        enter(new ArrayList<float[][]>());
        assertTrue(EarlyModelBatchBarrier.deferMultMatrix(oppositeView));
        assertEquals(7F, EarlyMatrixStateTracker.modelView()[12], 0F);
        assertEquals(2, oppositeView.position());
        assertEquals(18, oppositeView.limit());
    }

    @Test public void invalidBufferRetainsOldPoseUntilOrdinaryWrapperValidatesIt() {
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        assertTrue(EarlyModelBatchBarrier.deferTranslate(5F, 0F, 0F));
        FloatBuffer shortBuffer = directMatrix(0, ByteOrder.nativeOrder());
        shortBuffer.limit(15);
        for (FloatBuffer invalid : new FloatBuffer[] {null, FloatBuffer.allocate(16), shortBuffer}) {
            assertFalse(EarlyModelBatchBarrier.deferMultMatrix(invalid));
            assertTrue(EarlyMatrixStateTracker.isModelViewKnown());
            assertEquals(5F, EarlyMatrixStateTracker.modelView()[12], 0F);
        }
        assertTrue(publications.isEmpty());
        EarlyModelBatchBarrier.barrier();
        assertEquals(5F, publications.get(0)[0][12], 0F);
    }

    @Test public void nonFiniteMatrixMaterializesPriorPoseBeforeNativeFallback() {
        List<float[][]> publications = new ArrayList<float[][]>();
        enter(publications);
        assertTrue(EarlyModelBatchBarrier.deferTranslate(5F, 0F, 0F));
        FloatBuffer invalid = directMatrix(0, ByteOrder.nativeOrder());
        invalid.put(0, Float.NaN);
        assertFalse(EarlyModelBatchBarrier.deferMultMatrix(invalid));
        assertFalse(EarlyMatrixStateTracker.isModelViewKnown());
        assertEquals(5F, publications.get(0)[0][12], 0F);
    }

    private static FloatBuffer directMatrix(int offset, ByteOrder order) {
        FloatBuffer result = ByteBuffer.allocateDirect((offset + 20) * 4).order(order).asFloatBuffer();
        for (int i = 0; i < 16; i++) result.put(offset + i, i % 5 == 0 ? 1F : 0F);
        result.position(offset); result.limit(offset + 16);
        return result;
    }

    private static void enter(List<float[][]> publications) {
        EarlyModelBatchBarrier.enter(EarlyModelBatchBarrier::synchronize, () -> {
            int depth = EarlyMatrixStateTracker.modelViewDepth();
            float[][] snapshot = new float[depth + 1][16];
            for (int level = 0; level <= depth; level++)
                assertTrue(EarlyMatrixStateTracker.copyModelViewLevel(level, snapshot[level]));
            publications.add(snapshot);
        });
    }
}

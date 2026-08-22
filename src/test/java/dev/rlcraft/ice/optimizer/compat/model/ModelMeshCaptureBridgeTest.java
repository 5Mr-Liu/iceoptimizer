package dev.rlcraft.ice.optimizer.compat.model;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public final class ModelMeshCaptureBridgeTest {
    @After
    public void reset() {
        ModelMeshCaptureBridge.resetForTest();
    }

    @Test
    public void captureRecursionUsesAFixedStackAndBalancedOverflow() {
        Object[] owners = new Object[11];
        for (int index = 0; index < owners.length; index++) {
            owners[index] = new Object();
            ModelMeshCaptureBridge.begin(owners[index]);
        }
        assertEquals(8, ModelMeshCaptureBridge.captureDepthForTest());
        assertEquals(3, ModelMeshCaptureBridge.captureOverflowForTest());

        for (int index = owners.length - 1; index >= 0; index--) {
            ModelMeshCaptureBridge.finish(owners[index]);
        }
        assertEquals(0, ModelMeshCaptureBridge.captureDepthForTest());
        assertEquals(0, ModelMeshCaptureBridge.captureOverflowForTest());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void lifecycleResetDropsPendingPayloadsAndActiveCaptureOwners()
        throws Exception {
        Field pendingField = ModelMeshCaptureBridge.class
            .getDeclaredField("PENDING");
        pendingField.setAccessible(true);
        Map pending = (Map) pendingField.get(null);
        pending.put(Integer.valueOf(7), new Object());
        Field bytesField = ModelMeshCaptureBridge.class
            .getDeclaredField("pendingBytes");
        bytesField.setAccessible(true);
        bytesField.setLong(null, 4096L);
        ModelMeshCaptureBridge.begin(new Object());

        ModelMeshCaptureBridge.reset();

        assertEquals(0, pending.size());
        assertEquals(0L, bytesField.getLong(null));
        assertEquals(0, ModelMeshCaptureBridge.captureDepthForTest());
    }

    @Test
    public void deferredPublicationBackoffIsBoundedAndStartsNextFrame() {
        assertEquals(0L, ModelMeshCaptureBridge.publicationRetryDelay(0));
        assertEquals(1L, ModelMeshCaptureBridge.publicationRetryDelay(1));
        assertEquals(2L, ModelMeshCaptureBridge.publicationRetryDelay(2));
        assertEquals(4L, ModelMeshCaptureBridge.publicationRetryDelay(3));
        assertEquals(64L, ModelMeshCaptureBridge.publicationRetryDelay(7));
        assertEquals(64L, ModelMeshCaptureBridge.publicationRetryDelay(1000));
    }

    @Test
    public void unchangedGenerationPurgesPendingMapOnlyOnce() throws Exception {
        ModelMeshCaptureBridge.resetForTest();
        long before = ModelMeshCaptureBridge.diagnostics()
            .getPendingPurgeScans();
        Method purge = ModelMeshCaptureBridge.class.getDeclaredMethod(
            "purgeStalePending");
        purge.setAccessible(true);
        purge.invoke(null);
        long afterFirst = ModelMeshCaptureBridge.diagnostics()
            .getPendingPurgeScans();
        purge.invoke(null);
        long afterSecond = ModelMeshCaptureBridge.diagnostics()
            .getPendingPurgeScans();
        assertEquals(before + 1L, afterFirst);
        assertEquals(afterFirst, afterSecond);
    }
}

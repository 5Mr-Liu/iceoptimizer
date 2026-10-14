package dev.rlcraft.ice.optimizer.compat.model;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ModelMeshAdmissionQueueTest {
    @Test public void thousandsOfFailedPayloadsRemainAsleepUntilStorageActuallyChanges() {
        ModelMeshAdmissionQueue<Integer> queue = new ModelMeshAdmissionQueue<Integer>();
        queue.storageProgress(7);
        for (int i = 0; i < 3378; i++) queue.offer(i);
        for (int i = 0; i < 3378; i++) queue.awaitStorage(queue.poll());
        for (int frame = 0; frame < 10000; frame++) {
            queue.storageProgress(7);
            assertEquals(0, queue.readyCount());
        }
        queue.storageProgress(8);
        assertEquals(3378, queue.readyCount());
        for (int i = 0; i < 3378; i++) assertEquals(Integer.valueOf(i), queue.poll());
        assertNull(queue.poll());
    }

    @Test public void newCaptureCanRunWhileOldPayloadsWaitAndReplacementIsNotDuplicated() {
        ModelMeshAdmissionQueue<String> queue = new ModelMeshAdmissionQueue<String>();
        queue.storageProgress(1);
        queue.offer("old"); queue.awaitStorage(queue.poll());
        queue.offer("new");
        assertEquals("new", queue.poll());
        assertNull(queue.poll());
        queue.remove("old"); queue.offer("replacement");
        queue.storageProgress(2);
        assertEquals("replacement", queue.poll());
        assertNull(queue.poll());
    }

    @Test public void transientFailureOnlyReturnsOnceInTheCurrentBoundedDrain() {
        ModelMeshAdmissionQueue<Integer> queue = new ModelMeshAdmissionQueue<Integer>();
        for (int i = 0; i < 100; i++) queue.offer(i);
        int examined = Math.min(48, queue.readyCount());
        for (int i = 0; i < examined; i++) {
            Integer value = queue.poll();
            assertEquals(Integer.valueOf(i), value);
            queue.offer(value);
        }
        assertEquals(48, examined);
        assertEquals(Integer.valueOf(48), queue.poll());
    }

    @Test public void resetRemovesBothReadyAndWaitingPayloads() {
        ModelMeshAdmissionQueue<String> queue = new ModelMeshAdmissionQueue<String>();
        queue.offer("old"); queue.awaitStorage(queue.poll()); queue.offer("ready");
        queue.clear(); queue.storageProgress(100);
        assertEquals(0, queue.readyCount());
        assertNull(queue.poll());
    }
}

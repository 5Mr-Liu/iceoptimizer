package dev.rlcraft.ice.optimizer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BoundedRenderQueueTest {
    @Test
    public void dropsLateWorldResultAndExecutesCurrentResult() {
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        ClientEpochs epochs = new ClientEpochs();
        BoundedRenderQueue queue = new BoundedRenderQueue(epochs, 64);
        AtomicInteger value = new AtomicInteger();
        EpochToken stale = epochs.snapshot();
        assertTrue(queue.offer(OptimizationModule.RENDER_SUBMISSION, stale, EpochMask.WORLD, new Runnable() {
            @Override public void run() { value.incrementAndGet(); }
        }));
        epochs.invalidateWorld();
        assertEquals(0, queue.drain(1000000L, 64));
        EpochToken current = epochs.snapshot();
        assertTrue(queue.offer(OptimizationModule.RENDER_SUBMISSION, current, EpochMask.WORLD, new Runnable() {
            @Override public void run() { value.incrementAndGet(); }
        }));
        assertEquals(1, queue.drain(1000000L, 64));
        assertEquals(1, value.get());
        assertEquals(1L, queue.snapshot().getStale());
    }

    @Test
    public void closeRejectsEveryProducerAfterTheFinalDrain() {
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        ClientEpochs epochs = new ClientEpochs();
        BoundedRenderQueue queue = new BoundedRenderQueue(epochs, 64);
        EpochToken token = epochs.snapshot();
        assertTrue(queue.offer(OptimizationModule.RENDER_SUBMISSION, token,
            EpochMask.WORLD, new Runnable() {
                @Override public void run() { }
            }));
        assertEquals(1, queue.closeAndDiscard());
        assertTrue(queue.isClosed());
        assertFalse(queue.offer(OptimizationModule.RENDER_SUBMISSION, token,
            EpochMask.WORLD, new Runnable() {
                @Override public void run() { }
            }));
        assertEquals(0, queue.drain(1_000_000L, 64));
        assertEquals(0, queue.snapshot().getSize());
    }
}

package dev.rlcraft.ice.optimizer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;

public final class ClientWorkerRuntimeTest {
    @Test
    public void executesCurrentResultsAndConsumesStaleEpochsOffThread() {
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        ClientEpochs epochs = new ClientEpochs();
        BoundedRenderQueue render = new BoundedRenderQueue(epochs, 64);
        ClientWorkerRuntime workers = new ClientWorkerRuntime(epochs, render, 2, 64);
        AtomicInteger result = new AtomicInteger();
        try {
            EpochToken current = epochs.snapshot();
            assertTrue(workers.submit(OptimizationModule.RENDER_SUBMISSION, current,
                EpochMask.WORLD, new Callable<Integer>() {
                    @Override public Integer call() { return Integer.valueOf(7); }
                }, new Consumer<Integer>() {
                    @Override public void accept(Integer value) { result.set(value.intValue()); }
                }));
            awaitCompleted(workers, 1L);
            assertEquals(1, render.drain(1_000_000L, 64));
            assertEquals(7, result.get());

            EpochToken staleToken = epochs.snapshot();
            epochs.invalidateWorld();
            assertTrue(workers.submit(OptimizationModule.RENDER_SUBMISSION, staleToken,
                EpochMask.WORLD, new Callable<Integer>() {
                    @Override public Integer call() { return Integer.valueOf(9); }
                }, new Consumer<Integer>() {
                    @Override public void accept(Integer value) { result.set(value.intValue()); }
                }));
            awaitStale(workers, 1L);
            assertEquals(0, render.drain(1_000_000L, 64));
            assertEquals(7, result.get());
        } finally {
            workers.shutdown();
        }
    }

    @Test
    public void lateWorkerCannotPublishAfterShutdownDrain() throws Exception {
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        ClientEpochs epochs = new ClientEpochs();
        final BoundedRenderQueue render = new BoundedRenderQueue(epochs, 64);
        final ClientWorkerRuntime workers = new ClientWorkerRuntime(epochs,
            render, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(workers.submit(OptimizationModule.RENDER_SUBMISSION,
            epochs.snapshot(), EpochMask.WORLD, new Callable<Integer>() {
                @Override public Integer call() {
                    started.countDown();
                    boolean done = false;
                    while (!done) try {
                        done = release.await(10L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Deliberately emulate an uncooperative computation.
                    }
                    return Integer.valueOf(1);
                }
            }, new Consumer<Integer>() {
                @Override public void accept(Integer value) { }
            }));
        assertTrue(started.await(2L, TimeUnit.SECONDS));
        Thread shutdown = new Thread(new Runnable() {
            @Override public void run() { workers.shutdown(); }
        }, "worker-shutdown-test");
        shutdown.start();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!render.isClosed() && System.nanoTime() - deadline < 0L) {
            Thread.yield();
        }
        assertTrue(render.isClosed());
        release.countDown();
        shutdown.join(2_000L);
        assertFalse(shutdown.isAlive());
        assertEquals(0, render.snapshot().getSize());
        assertEquals(0, render.drain(1_000_000L, 64));
    }

    private static void awaitCompleted(ClientWorkerRuntime workers, long expected) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (workers.snapshot().getCompleted() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, workers.snapshot().getCompleted());
    }

    private static void awaitStale(ClientWorkerRuntime workers, long expected) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (workers.snapshot().getStale() < expected && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(expected, workers.snapshot().getStale());
    }
}

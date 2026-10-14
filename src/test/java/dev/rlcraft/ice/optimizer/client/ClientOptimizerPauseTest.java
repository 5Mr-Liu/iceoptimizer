package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.*;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.skull.SkullProfileBridge;
import dev.rlcraft.ice.optimizer.runtime.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ClientOptimizerPauseTest {
    @Test public void terrainHandoffDrainsBeforeSwitchAndRebuildsInTheNewMode() throws Exception {
        OptimizerRegistry.beginRuntime();
        ClientOptimizerConfig config = ClientOptimizerConfig.capture();
        OptimizerRegistry.configure(config);
        Constructor<ClientOptimizerRuntime> constructor = ClientOptimizerRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientOptimizerRuntime runtime = constructor.newInstance();
        set(runtime, "config", config);
        set(runtime, "epochs", new ClientEpochs());
        set(runtime, "initialized", true);
        java.util.List<String> calls = new java.util.ArrayList<>();
        set(runtime, "nativeTerrainPause", new NativeTerrainPauseLifecycle() {
            @Override void beforeChange() { calls.add("drain:" + OptimizerRegistry.isRuntimePaused()); }
            @Override void afterChange() { calls.add("rebuild:" + OptimizerRegistry.isRuntimePaused()); }
        });
        try {
            assertTrue(runtime.setRuntimePaused(true));
            assertTrue(runtime.setRuntimePaused(true)); // Repeated state must not rebuild again.
            assertTrue(runtime.setRuntimePaused(false));
            assertTrue(runtime.setRuntimePaused(true));
            assertEquals(java.util.Arrays.asList("drain:false", "rebuild:true", "drain:true",
                "rebuild:false", "drain:false", "rebuild:true"), calls);
        } finally {
            runtime.setRuntimePaused(true);
            SkullProfileBridge.shutdown();
            OptimizerRegistry.beginRuntime();
            OptimizerRegistry.configure(config);
        }
    }

    @Test public void resumedQueueRejectsLateResultsFromBeforePause() throws Exception {
        OptimizerRegistry.beginRuntime();
        ClientOptimizerConfig config = ClientOptimizerConfig.capture();
        OptimizerRegistry.configure(config);
        Constructor<ClientOptimizerRuntime> constructor = ClientOptimizerRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientOptimizerRuntime runtime = constructor.newInstance();
        ClientEpochs epochs = new ClientEpochs();
        BoundedRenderQueue oldQueue = new BoundedRenderQueue(epochs, 64);
        ClientWorkerRuntime oldWorkers = new ClientWorkerRuntime(epochs, oldQueue, 1, 64);
        set(runtime, "config", config);
        set(runtime, "epochs", epochs);
        set(runtime, "renderQueue", oldQueue);
        set(runtime, "workers", oldWorkers);
        set(runtime, "initialized", true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        try {
            assertTrue(runtime.submit(OptimizationModule.RENDER_SUBMISSION, epochs.snapshot(), EpochMask.WORLD,
                () -> {
                    started.countDown();
                    boolean done = false;
                    while (!done) try { done = release.await(10L, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { }
                    finished.countDown();
                    return 1;
                }, result::set));
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertTrue(runtime.setRuntimePaused(true));
            assertTrue(runtime.isRuntimePaused());
            assertNull(runtime.status().getWorkers());
            assertFalse(runtime.submit(OptimizationModule.RENDER_SUBMISSION,
                epochs.snapshot(), EpochMask.WORLD, () -> 2, result::set));
            assertTrue(runtime.setRuntimePaused(false));
            release.countDown();
            assertTrue(finished.await(2L, TimeUnit.SECONDS));
            oldWorkers.shutdown();
            assertEquals(0, oldQueue.snapshot().getSize());
            assertEquals(0, runtime.drainRenderQueue());
            assertEquals(0, result.get());
            assertTrue(runtime.submit(OptimizationModule.RENDER_SUBMISSION,
                epochs.snapshot(), EpochMask.WORLD, () -> 3, result::set));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (result.get() == 0 && System.nanoTime() < deadline) {
                runtime.drainRenderQueue();
                Thread.yield();
            }
            assertEquals(3, result.get());
        } finally {
            release.countDown();
            oldWorkers.shutdown();
            runtime.setRuntimePaused(true);
            SkullProfileBridge.shutdown();
            OptimizerRegistry.beginRuntime();
            OptimizerRegistry.configure(config);
        }
    }

    private static void set(ClientOptimizerRuntime runtime, String name, Object value) throws Exception {
        Field field = ClientOptimizerRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }
}

package dev.rlcraft.ice.optimizer.runtime;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;

/** Dedicated bounded MPMC runtime for pure client-side computations. */
public final class ClientWorkerRuntime {
    private static final long IDLE_PARK_NANOS = 250_000L;

    private final ClientEpochs epochs;
    private final BoundedRenderQueue renderQueue;
    private final ManyToManyConcurrentArrayQueue<EpochTask<?>> queue;
    private final Thread[] workerThreads;
    private final int queueCapacity;
    private final AtomicInteger signalCursor = new AtomicInteger();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private volatile boolean running = true;

    public ClientWorkerRuntime(ClientEpochs epochs, BoundedRenderQueue renderQueue,
                               int threads, int queueCapacity) {
        this.epochs = epochs;
        this.renderQueue = renderQueue;
        this.queueCapacity = Math.max(64, queueCapacity);
        this.queue = new ManyToManyConcurrentArrayQueue<EpochTask<?>>(this.queueCapacity);
        int workerCount = Math.max(1, threads);
        this.workerThreads = new Thread[workerCount];
        for (int i = 0; i < workerCount; i++) {
            Thread thread = new Thread(new WorkerLoop(), "ICE-Client-Worker-" + (i + 1));
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable error) {
                    OptimizerRegistry.breaker(OptimizationModule.CORE_RUNTIME).recordFailure(error);
                }
            });
            workerThreads[i] = thread;
            thread.start();
        }
    }

    public <T> boolean submit(OptimizationModule module, EpochToken token, int epochMask,
                              Callable<T> computation, Consumer<T> renderCompletion) {
        if (!running || module == null || computation == null || renderCompletion == null
            || !OptimizerRegistry.isOperational(module.ordinal())) return false;
        EpochTask<T> task = new EpochTask<T>(module, token, epochMask,
            computation, renderCompletion);
        if (!queue.offer(task)) {
            rejected.increment();
            OptimizerRegistry.breaker(module).recordRejected("CPU 工作队列已满，已回退原路径");
            return false;
        }
        submitted.increment();
        signalWorker();
        return true;
    }

    /**
     * Epoch invalidation is consumed by workers instead of traversing/removing
     * a concurrent queue on the render thread.
     */
    public int discardStaleQueuedTasks() {
        signalAllWorkers();
        return 0;
    }

    public void shutdown() {
        running = false;
        signalAllWorkers();
        for (Thread thread : workerThreads) thread.interrupt();
        for (Thread thread : workerThreads) {
            try {
                thread.join(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int discarded = 0;
        while (queue.poll() != null) discarded++;
        stale.add(discarded);
        renderQueue.discardAll();
    }

    public WorkerStatus snapshot() {
        return new WorkerStatus(workerThreads.length, queue.size(), queueCapacity,
            submitted.sum(), completed.sum(), rejected.sum(), stale.sum());
    }

    private void signalWorker() {
        int index = (signalCursor.getAndIncrement() & Integer.MAX_VALUE) % workerThreads.length;
        LockSupport.unpark(workerThreads[index]);
    }

    private void signalAllWorkers() {
        for (Thread thread : workerThreads) LockSupport.unpark(thread);
    }

    private final class WorkerLoop implements Runnable {
        @Override public void run() {
            while (running) {
                EpochTask<?> task = queue.poll();
                if (task != null) {
                    task.runTask();
                    continue;
                }
                LockSupport.parkNanos(ClientWorkerRuntime.this, IDLE_PARK_NANOS);
                if (Thread.interrupted() && !running) return;
            }
        }
    }

    private final class EpochTask<T> {
        private final OptimizationModule module;
        private final EpochToken token;
        private final int epochMask;
        private final Callable<T> computation;
        private final Consumer<T> completion;

        private EpochTask(OptimizationModule module, EpochToken token, int epochMask,
                          Callable<T> computation, Consumer<T> completion) {
            this.module = module;
            this.token = token;
            this.epochMask = epochMask;
            this.computation = computation;
            this.completion = completion;
        }

        private boolean isCurrent() {
            return epochs.isCurrent(token, epochMask);
        }

        private void runTask() {
            if (!isCurrent()) {
                stale.increment();
                return;
            }
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(module);
            if (breaker == null || !OptimizerRegistry.isOperational(module.ordinal())) return;
            try {
                final T value = computation.call();
                if (!isCurrent()) {
                    stale.increment();
                    return;
                }
                boolean queued = renderQueue.offer(module, token, epochMask, new Runnable() {
                    @Override public void run() { completion.accept(value); }
                });
                if (queued) {
                    completed.increment();
                    breaker.recordSuccess();
                }
            } catch (Throwable error) {
                breaker.recordFailure(error);
            }
        }
    }
}

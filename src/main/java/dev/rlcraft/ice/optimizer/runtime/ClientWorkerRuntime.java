package dev.rlcraft.ice.optimizer.runtime;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ClientWorkerRuntime {
    private final ClientEpochs epochs;
    private final BoundedRenderQueue renderQueue;
    private final ThreadPoolExecutor executor;
    private final int queueCapacity;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();

    public ClientWorkerRuntime(ClientEpochs epochs, BoundedRenderQueue renderQueue, int threads, int queueCapacity) {
        this.epochs = epochs;
        this.renderQueue = renderQueue;
        this.queueCapacity = Math.max(64, queueCapacity);
        int workerCount = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(this.queueCapacity), new OptimizerThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartAllCoreThreads();
    }

    public <T> boolean submit(OptimizationModule module, EpochToken token, int epochMask,
                              Callable<T> computation, Consumer<T> renderCompletion) {
        if (module == null || computation == null || renderCompletion == null || !OptimizerRegistry.isOperational(module)) return false;
        EpochTask<T> task = new EpochTask<T>(module, token, epochMask, computation, renderCompletion);
        try {
            executor.execute(task);
            submitted.incrementAndGet();
            return true;
        } catch (RejectedExecutionException full) {
            rejected.incrementAndGet();
            OptimizerRegistry.breaker(module).recordRejected("CPU 工作队列已满，已回退原路径");
            return false;
        }
    }

    public int discardStaleQueuedTasks() {
        int discarded = 0;
        Iterator<Runnable> iterator = executor.getQueue().iterator();
        while (iterator.hasNext()) {
            Runnable runnable = iterator.next();
            if (runnable instanceof EpochTask && !((EpochTask<?>) runnable).isCurrent()) {
                if (executor.getQueue().remove(runnable)) discarded++;
            }
        }
        stale.addAndGet(discarded);
        return discarded;
    }

    public void shutdown() {
        executor.shutdownNow();
        renderQueue.discardAll();
    }

    public WorkerStatus snapshot() {
        return new WorkerStatus(executor.getCorePoolSize(), executor.getQueue().size(), queueCapacity,
            submitted.get(), completed.get(), rejected.get(), stale.get());
    }

    private final class EpochTask<T> implements Runnable {
        private final OptimizationModule module;
        private final EpochToken token;
        private final int epochMask;
        private final Callable<T> computation;
        private final Consumer<T> completion;

        private EpochTask(OptimizationModule module, EpochToken token, int epochMask, Callable<T> computation, Consumer<T> completion) {
            this.module = module;
            this.token = token;
            this.epochMask = epochMask;
            this.computation = computation;
            this.completion = completion;
        }

        private boolean isCurrent() {
            return epochs.isCurrent(token, epochMask);
        }

        @Override
        public void run() {
            if (!isCurrent()) {
                stale.incrementAndGet();
                return;
            }
            try {
                final T value = computation.call();
                if (!isCurrent()) {
                    stale.incrementAndGet();
                    return;
                }
                boolean queued = renderQueue.offer(module, token, epochMask, new Runnable() {
                    @Override public void run() { completion.accept(value); }
                });
                if (queued) {
                    completed.incrementAndGet();
                    OptimizerRegistry.breaker(module).recordSuccess();
                }
            } catch (Throwable error) {
                OptimizerRegistry.breaker(module).recordFailure(error);
            }
        }
    }

    private static final class OptimizerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ICE-Client-Worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable error) {
                    OptimizerRegistry.breaker(OptimizationModule.CORE_RUNTIME).recordFailure(error);
                }
            });
            return thread;
        }
    }
}

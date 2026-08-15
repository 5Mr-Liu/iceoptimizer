package dev.rlcraft.ice.optimizer.runtime;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/** MPSC queue: optimizer workers submit; only the Minecraft render thread drains. */
public final class BoundedRenderQueue {
    private final ManyToOneConcurrentArrayQueue<RenderCommand> queue;
    private final ClientEpochs epochs;
    private final int configuredCapacity;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public BoundedRenderQueue(ClientEpochs epochs, int capacity) {
        this.epochs = epochs;
        this.configuredCapacity = Math.max(64, capacity);
        this.queue = new ManyToOneConcurrentArrayQueue<RenderCommand>(this.configuredCapacity);
    }

    public boolean offer(OptimizationModule module, EpochToken token, int epochMask, Runnable action) {
        if (module == null || action == null || !OptimizerRegistry.isOperational(module)) return false;
        RenderCommand command = new RenderCommand(module, token, epochMask, action);
        if (!queue.offer(command)) {
            rejected.incrementAndGet();
            OptimizerRegistry.breaker(module).recordRejected("渲染提交队列已满，已回退原路径");
            OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION).recordRejected("渲染提交队列已满");
            return false;
        }
        submitted.incrementAndGet();
        return true;
    }

    public int drain(long timeBudgetNanos, int maximumCommands) {
        long started = System.nanoTime();
        int completed = 0;
        int processed = 0;
        int processLimit = Math.max(1, maximumCommands);
        while (processed < processLimit) {
            if (processed > 0 && System.nanoTime() - started >= Math.max(1L, timeBudgetNanos)) break;
            RenderCommand command = queue.poll();
            if (command == null) break;
            processed++;
            if (!epochs.isCurrent(command.token, command.epochMask)) {
                stale.incrementAndGet();
                continue;
            }
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(command.module);
            if (!breaker.isOperational()) continue;
            try {
                command.action.run();
                executed.incrementAndGet();
                completed++;
                breaker.recordSuccess();
                breaker.activate("已执行已验证优化路径");
                if (command.module != OptimizationModule.RENDER_SUBMISSION) {
                    OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION).recordSuccess();
                }
            } catch (Throwable error) {
                failures.incrementAndGet();
                breaker.recordFailure(error);
                if (command.module != OptimizationModule.RENDER_SUBMISSION) {
                    OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION).recordFailure(error);
                }
            }
        }
        return completed;
    }

    public int discardAll() {
        int discarded = 0;
        while (queue.poll() != null) discarded++;
        stale.addAndGet(discarded);
        return discarded;
    }

    public RenderQueueStatus snapshot() {
        return new RenderQueueStatus(configuredCapacity, queue.size(), submitted.get(), executed.get(), rejected.get(), stale.get(), failures.get());
    }

    private static final class RenderCommand {
        private final OptimizationModule module;
        private final EpochToken token;
        private final int epochMask;
        private final Runnable action;

        private RenderCommand(OptimizationModule module, EpochToken token, int epochMask, Runnable action) {
            this.module = module;
            this.token = token;
            this.epochMask = epochMask;
            this.action = action;
        }
    }
}

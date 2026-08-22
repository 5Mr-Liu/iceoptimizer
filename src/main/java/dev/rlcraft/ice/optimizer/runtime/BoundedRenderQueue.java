package dev.rlcraft.ice.optimizer.runtime;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.concurrent.atomic.LongAdder;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/** MPSC queue: optimizer workers submit; only the Minecraft render thread drains. */
public final class BoundedRenderQueue {
    private static final int TIME_CHECK_MASK = 7;
    private final ManyToOneConcurrentArrayQueue<RenderCommand> queue;
    private final ClientEpochs epochs;
    private final int configuredCapacity;
    private final Object producerGate = new Object();
    private final Object consumerGate = new Object();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder executed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile boolean closed;

    public BoundedRenderQueue(ClientEpochs epochs, int capacity) {
        this.epochs = epochs;
        this.configuredCapacity = Math.max(64, capacity);
        this.queue = new ManyToOneConcurrentArrayQueue<RenderCommand>(this.configuredCapacity);
    }

    public boolean offer(OptimizationModule module, EpochToken token, int epochMask, Runnable action) {
        if (module == null || action == null
            || !OptimizerRegistry.isOperational(module.ordinal())) return false;
        RenderCommand command = new RenderCommand(module, token, epochMask, action);
        synchronized (producerGate) {
            if (closed) return false;
            if (!queue.offer(command)) {
                rejected.increment();
                OptimizerRegistry.breaker(module).recordRejected(
                    "渲染提交队列已满，已回退原路径");
                OptimizerRegistry.breaker(OptimizationModule.RENDER_SUBMISSION)
                    .recordRejected("渲染提交队列已满");
                return false;
            }
            submitted.increment();
            return true;
        }
    }

    public int drain(long timeBudgetNanos, int maximumCommands) {
        synchronized (consumerGate) {
            long started = System.nanoTime();
            long budget = Math.max(1L, timeBudgetNanos);
            int completed = 0;
            int processed = 0;
            int processLimit = Math.max(1, maximumCommands);
            while (processed < processLimit) {
                if (processed > 0 && (processed & TIME_CHECK_MASK) == 0
                    && System.nanoTime() - started >= budget) break;
                RenderCommand command = queue.poll();
                if (command == null) break;
                processed++;
                if (!epochs.isCurrent(command.token, command.epochMask)) {
                    stale.increment();
                    continue;
                }
                ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                    command.module);
                if (!OptimizerRegistry.isOperational(command.module.ordinal())) {
                    continue;
                }
                try {
                    command.action.run();
                    executed.increment();
                    completed++;
                    breaker.recordSuccess();
                    breaker.activate("已执行已验证优化路径");
                    if (command.module != OptimizationModule.RENDER_SUBMISSION) {
                        OptimizerRegistry.breaker(
                            OptimizationModule.RENDER_SUBMISSION).recordSuccess();
                    }
                } catch (ThreadDeath fatal) {
                    throw fatal;
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable error) {
                    failures.increment();
                    breaker.recordFailure(error);
                    if (command.module != OptimizationModule.RENDER_SUBMISSION) {
                        OptimizerRegistry.breaker(
                            OptimizationModule.RENDER_SUBMISSION)
                            .recordFailure(error);
                    }
                }
            }
            return completed;
        }
    }

    public int discardAll() {
        synchronized (consumerGate) {
            return discardAllLocked();
        }
    }

    /** Permanently rejects producers before consuming the final queue tail. */
    public int closeAndDiscard() {
        synchronized (producerGate) {
            closed = true;
        }
        synchronized (consumerGate) {
            return discardAllLocked();
        }
    }

    boolean isClosed() { return closed; }

    private int discardAllLocked() {
        int discarded = 0;
        while (queue.poll() != null) discarded++;
        stale.add(discarded);
        return discarded;
    }

    public RenderQueueStatus snapshot() {
        return new RenderQueueStatus(configuredCapacity, queue.size(), submitted.sum(), executed.sum(),
            rejected.sum(), stale.sum(), failures.sum());
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

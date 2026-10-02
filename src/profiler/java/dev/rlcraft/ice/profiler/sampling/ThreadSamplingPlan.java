package dev.rlcraft.ice.profiler.sampling;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, allocation-free sampling plan reused between worker discovery
 * passes. Deep mode always samples both game main threads and rotates a small
 * worker group instead of suspending every known worker every 10 ms.
 */
final class ThreadSamplingPlan {
    static final int DEEP_WORKERS_PER_BATCH = 4;
    private static final Batch EMPTY_BATCH = new Batch(
        new ThreadDescriptor[0], new long[0]);
    private static final ThreadSamplingPlan EMPTY = new ThreadSamplingPlan(
        EMPTY_BATCH, new Batch[] { EMPTY_BATCH });

    private final Batch full;
    private final Batch[] deep;

    private ThreadSamplingPlan(Batch full, Batch[] deep) {
        this.full = full;
        this.deep = deep;
    }

    static ThreadSamplingPlan empty() {
        return EMPTY;
    }

    static ThreadSamplingPlan create(List<ThreadDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) return EMPTY;
        ThreadDescriptor[] all = descriptors.toArray(
            new ThreadDescriptor[descriptors.size()]);
        Batch full = batch(all);
        List<ThreadDescriptor> main = new ArrayList<ThreadDescriptor>(2);
        List<ThreadDescriptor> workers = new ArrayList<ThreadDescriptor>(all.length);
        for (ThreadDescriptor descriptor : all) {
            if (isMain(descriptor.getRole())) main.add(descriptor);
            else workers.add(descriptor);
        }
        int groups = Math.max(1,
            (workers.size() + DEEP_WORKERS_PER_BATCH - 1) / DEEP_WORKERS_PER_BATCH);
        Batch[] deep = new Batch[groups];
        for (int group = 0; group < groups; group++) {
            int workerStart = group * DEEP_WORKERS_PER_BATCH;
            int workerEnd = Math.min(workers.size(), workerStart + DEEP_WORKERS_PER_BATCH);
            ThreadDescriptor[] selected = new ThreadDescriptor[
                main.size() + Math.max(0, workerEnd - workerStart)];
            int index = 0;
            for (ThreadDescriptor descriptor : main) selected[index++] = descriptor;
            for (int worker = workerStart; worker < workerEnd; worker++) {
                selected[index++] = workers.get(worker);
            }
            // A registry without a discovered main thread still needs useful
            // deep samples, so worker-only batches are valid.
            deep[group] = selected.length == 0 ? EMPTY_BATCH : batch(selected);
        }
        return new ThreadSamplingPlan(full, deep);
    }

    Batch fullBatch() {
        return full;
    }

    Batch deepBatch(int cursor) {
        int index = cursor % deep.length;
        if (index < 0) index += deep.length;
        return deep[index];
    }

    int deepBatchCount() {
        return deep.length;
    }

    private static Batch batch(ThreadDescriptor[] descriptors) {
        long[] ids = new long[descriptors.length];
        for (int i = 0; i < descriptors.length; i++) ids[i] = descriptors[i].getId();
        return new Batch(descriptors, ids);
    }

    private static boolean isMain(ThreadRole role) {
        return role == ThreadRole.CLIENT_MAIN || role == ThreadRole.SERVER_MAIN;
    }

    static final class Batch {
        private final ThreadDescriptor[] descriptors;
        private final long[] ids;

        private Batch(ThreadDescriptor[] descriptors, long[] ids) {
            this.descriptors = descriptors;
            this.ids = ids;
        }

        ThreadDescriptor[] descriptors() {
            return descriptors;
        }

        long[] ids() {
            return ids;
        }

        int size() {
            return ids.length;
        }
    }
}

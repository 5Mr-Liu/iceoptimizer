package dev.rlcraft.ice.profiler.sampling;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ThreadRegistry {
    private final ThreadMXBean threadBean;
    private final Map<Long, ThreadDescriptor> explicit = new HashMap<Long, ThreadDescriptor>();
    private volatile List<ThreadDescriptor> discovered = Collections.emptyList();
    private volatile ThreadSamplingPlan samplingPlan = ThreadSamplingPlan.empty();

    public ThreadRegistry(ThreadMXBean threadBean) {
        this.threadBean = threadBean;
    }

    public synchronized void registerCurrent(ThreadRole role) {
        Thread thread = Thread.currentThread();
        explicit.put(Long.valueOf(thread.getId()), new ThreadDescriptor(thread.getId(), thread.getName(), role));
    }

    public synchronized void unregisterCurrent() {
        explicit.remove(Long.valueOf(Thread.currentThread().getId()));
    }

    public void discoverWorkers(boolean includeWorkers) {
        long[] ids = threadBean.getAllThreadIds();
        ThreadInfo[] infos = threadBean.getThreadInfo(ids, 0);
        List<ThreadDescriptor> next = new ArrayList<ThreadDescriptor>();
        List<ThreadDescriptor> previous = discovered;
        Map<Long, ThreadDescriptor> explicitlyRegistered;
        synchronized (this) {
            explicitlyRegistered = new HashMap<Long, ThreadDescriptor>(explicit);
        }
        for (ThreadDescriptor descriptor : explicitlyRegistered.values()) {
            next.add(descriptor);
        }
        for (ThreadInfo info : infos) {
            if (info == null || explicitlyRegistered.containsKey(Long.valueOf(info.getThreadId()))) continue;
            ThreadRole role = inferRole(info.getThreadName());
            if (role == null || (!includeWorkers && role != ThreadRole.CLIENT_MAIN && role != ThreadRole.SERVER_MAIN)) continue;
            next.add(reuse(previous, info.getThreadId(), info.getThreadName(), role));
        }
        Collections.sort(next, new Comparator<ThreadDescriptor>() {
            @Override public int compare(ThreadDescriptor left, ThreadDescriptor right) {
                int role = Integer.compare(left.getRole().ordinal(), right.getRole().ordinal());
                return role != 0 ? role : Long.compare(left.getId(), right.getId());
            }
        });
        if (same(previous, next)) return;
        List<ThreadDescriptor> published = Collections.unmodifiableList(next);
        discovered = published;
        samplingPlan = ThreadSamplingPlan.create(published);
    }

    public Collection<ThreadDescriptor> snapshot() {
        return discovered;
    }

    ThreadSamplingPlan samplingPlan() {
        return samplingPlan;
    }

    private static ThreadDescriptor reuse(List<ThreadDescriptor> previous, long id,
                                          String name, ThreadRole role) {
        for (ThreadDescriptor descriptor : previous) {
            if (descriptor.getId() == id && descriptor.getRole() == role
                && descriptor.getName().equals(name == null ? "unknown" : name)) {
                return descriptor;
            }
        }
        return new ThreadDescriptor(id, name, role);
    }

    private static boolean same(List<ThreadDescriptor> left, List<ThreadDescriptor> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            ThreadDescriptor a = left.get(i);
            ThreadDescriptor b = right.get(i);
            if (a.getId() != b.getId() || a.getRole() != b.getRole()
                || !a.getName().equals(b.getName())) return false;
        }
        return true;
    }

    static ThreadRole inferRole(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.equals("client thread") || lower.contains("render thread")) return ThreadRole.CLIENT_MAIN;
        if (lower.equals("server thread")) return ThreadRole.SERVER_MAIN;
        if (lower.contains("chunk batcher") || lower.contains("chunk render")) return ThreadRole.CHUNK_WORKER;
        if (lower.contains("chunk i/o") || lower.contains("chunkio") || lower.contains("chunk io")) return ThreadRole.CHUNK_IO;
        if (lower.contains("netty") || lower.contains("nioeventloop")) return ThreadRole.NETWORK;
        if (lower.contains("file io") || lower.contains("file i/o") || lower.contains("region")) return ThreadRole.FILE_IO;
        if (lower.contains("forkjoinpool") || lower.contains("worker") || lower.contains("pool-")) return ThreadRole.WORKER;
        return null;
    }
}

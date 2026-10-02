package dev.rlcraft.ice.profiler.sampling;

import java.util.Locale;

/** Shared report/analyzer rules for distinguishing idle worker waits from work. */
public final class StackSampleFilter {
    private StackSampleFilter() {
    }

    public static boolean isIdleWorkerWait(StackSample sample, StackTraceElement[] trace) {
        if (sample == null || trace == null) return false;
        ThreadRole role = sample.getRole();
        if (role == ThreadRole.CLIENT_MAIN || role == ThreadRole.SERVER_MAIN) return false;
        Thread.State state = sample.getState();

        StringBuilder joined = new StringBuilder(trace.length * 40);
        for (StackTraceElement frame : trace) {
            joined.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(' ');
        }
        String value = joined.toString().toLowerCase(Locale.ROOT);
        if (containsAny(value,
            "threadedfileiobase") && containsAny(value, "java.lang.thread.sleep", "thread.sleep")) {
            return true;
        }
        if ((role == ThreadRole.NETWORK || value.contains("nioeventloop")) && containsAny(value,
            "selector.select",
            "selectorimpl.select",
            "selectorimpl.doselect",
            "windowselectorimpl$subselector.poll0",
            "windowselectorimpl.doselect",
            "epollselectorimpl.doselect",
            "kqueueselectorimpl.doselect",
            "nioeventloop.select")) {
            return true;
        }
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
        return containsAny(value,
            "sun.misc.unsafe.park",
            "jdk.internal.misc.unsafe.park",
            "locksupport.park",
            "object.wait",
            "blockingqueue.take",
            "blockingqueue.poll",
            "linkedblockingqueue.take",
            "linkedblockingqueue.poll",
            "arrayblockingqueue.take",
            "arrayblockingqueue.poll",
            "priorityblockingqueue.take",
            "priorityblockingqueue.poll",
            "threadpoolexecutor.gettask",
            "forkjoinpool.awaitwork",
            "forkjoinpool.awaitjoin",
            "java.lang.thread.sleep",
            "chunkrenderdispatcher.func_178511_d",
            "chunkrenderdispatcher.getnextchunkupdatetocompile",
            "selector.select",
            "selectorimpl.select",
            "nioeventloop.select");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}

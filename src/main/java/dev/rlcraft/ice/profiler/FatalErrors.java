package dev.rlcraft.ice.profiler;

/** Fatal JVM/thread termination signals must never become profiler fallback. */
public final class FatalErrors {
    private FatalErrors() {
    }

    public static boolean isFatal(Throwable error) {
        return error instanceof ThreadDeath || error instanceof VirtualMachineError;
    }

    public static Throwable findFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (isFatal(current)) return current;
            Throwable next = current.getCause();
            if (next == current) return null;
            current = next;
        }
        return null;
    }

    public static void rethrowIfFatal(Throwable error) {
        Throwable fatal = findFatal(error);
        if (fatal instanceof ThreadDeath) throw (ThreadDeath) fatal;
        if (fatal instanceof VirtualMachineError) {
            throw (VirtualMachineError) fatal;
        }
    }
}

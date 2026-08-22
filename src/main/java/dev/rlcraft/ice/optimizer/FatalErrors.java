package dev.rlcraft.ice.optimizer;

/** Fatal JVM/thread termination signals must never be converted into a fallback. */
public final class FatalErrors {
    private FatalErrors() {}

    public static boolean isFatal(Throwable error) {
        return error instanceof ThreadDeath || error instanceof VirtualMachineError;
    }

    public static void rethrowIfFatal(Throwable error) {
        Throwable fatal = findFatal(error);
        if (fatal instanceof ThreadDeath) throw (ThreadDeath) fatal;
        if (fatal instanceof VirtualMachineError) {
            throw (VirtualMachineError) fatal;
        }
    }

    public static Throwable findFatal(Throwable error) {
        // Reflection and executor boundaries commonly wrap the original
        // failure.  Keep this allocation-free and bounded so it is itself
        // safe while handling an OutOfMemoryError or a malformed cause cycle.
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (isFatal(current)) return current;
            Throwable next = current.getCause();
            if (next == current) return null;
            current = next;
        }
        return null;
    }
}

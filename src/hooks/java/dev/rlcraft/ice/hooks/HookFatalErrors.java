package dev.rlcraft.ice.hooks;

/** Fatal boundary kept inside the early Core JAR with no main-JAR linkage. */
final class HookFatalErrors {
    private HookFatalErrors() {
    }

    static void rethrowIfFatal(Throwable error) {
        Throwable fatal = findFatal(error);
        if (fatal instanceof ThreadDeath) throw (ThreadDeath) fatal;
        if (fatal instanceof VirtualMachineError) {
            throw (VirtualMachineError) fatal;
        }
    }

    static Throwable findFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (current instanceof ThreadDeath
                || current instanceof VirtualMachineError) return current;
            Throwable next = current.getCause();
            if (next == current) return null;
            current = next;
        }
        return null;
    }
}

package dev.rlcraft.ice.optimizer.bridge;

/**
 * Marks a delegate failure after native work may already have been submitted
 * or after its one permitted Legacy replay failed.  Early Core trampolines
 * recognize this class by name, avoiding a static main-JAR dependency while
 * ensuring the transformed call site never executes the original operation a
 * second time.
 */
public final class UnsafeLegacyReplayException extends RuntimeException {
    public UnsafeLegacyReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}

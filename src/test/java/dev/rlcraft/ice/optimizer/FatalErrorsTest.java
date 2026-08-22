package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FatalErrorsTest {
    @Test
    public void fallbackBoundariesRethrowJvmAndThreadTermination() {
        OutOfMemoryError oom = new OutOfMemoryError("test");
        assertTrue(FatalErrors.isFatal(oom));
        try {
            FatalErrors.rethrowIfFatal(oom);
            throw new AssertionError("expected OOME");
        } catch (OutOfMemoryError expected) {
            assertSame(oom, expected);
        }

        ThreadDeath death = new ThreadDeath();
        assertTrue(FatalErrors.isFatal(death));
        try {
            FatalErrors.rethrowIfFatal(death);
            throw new AssertionError("expected ThreadDeath");
        } catch (ThreadDeath expected) {
            assertSame(death, expected);
        }

        FatalErrors.rethrowIfFatal(new AssertionError("recoverable test"));
        FatalErrors.rethrowIfFatal(null);
    }

    @Test
    public void reflectionWrappersCannotHideFatalFailures() {
        OutOfMemoryError fatal = new OutOfMemoryError("wrapped");
        RuntimeException wrapper = new RuntimeException("reflection", fatal);
        try {
            FatalErrors.rethrowIfFatal(wrapper);
            throw new AssertionError("expected wrapped OOME");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
    }
}

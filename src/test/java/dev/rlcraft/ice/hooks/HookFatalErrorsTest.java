package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class HookFatalErrorsTest {
    @Test
    public void directOutOfMemoryErrorEscapes() {
        OutOfMemoryError fatal = new OutOfMemoryError("direct OOME");
        assertRethrown(fatal, fatal);
    }

    @Test
    public void wrappedOutOfMemoryErrorEscapesAsTheFatalCause() {
        OutOfMemoryError fatal = new OutOfMemoryError("wrapped OOME");
        assertRethrown(new IllegalStateException("reflection wrapper", fatal),
            fatal);
    }

    @Test
    public void directThreadDeathEscapes() {
        ThreadDeath fatal = new ThreadDeath();
        assertRethrown(fatal, fatal);
    }

    @Test
    public void wrappedThreadDeathEscapesAsTheFatalCause() {
        ThreadDeath fatal = new ThreadDeath();
        assertRethrown(new RuntimeException("method-handle wrapper", fatal),
            fatal);
    }

    @Test
    public void ordinaryFailureIsNotPromoted() {
        RuntimeException ordinary = new RuntimeException("recoverable");
        HookFatalErrors.rethrowIfFatal(ordinary);
        assertNull(HookFatalErrors.findFatal(ordinary));
        HookFatalErrors.rethrowIfFatal(null);
    }

    private static void assertRethrown(Throwable boundary, Throwable expected) {
        try {
            HookFatalErrors.rethrowIfFatal(boundary);
            fail("fatal boundary was swallowed");
        } catch (ThreadDeath actual) {
            assertSame(expected, actual);
        } catch (VirtualMachineError actual) {
            assertSame(expected, actual);
        }
    }
}

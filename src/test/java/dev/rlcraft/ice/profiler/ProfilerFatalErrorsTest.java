package dev.rlcraft.ice.profiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ProfilerFatalErrorsTest {
    @Test
    public void findsDirectAndWrappedFatalSignals() {
        OutOfMemoryError memory = new OutOfMemoryError("memory");
        ThreadDeath death = new ThreadDeath();
        assertEquals(memory, FatalErrors.findFatal(memory));
        assertEquals(memory, FatalErrors.findFatal(new Exception(memory)));
        assertEquals(death, FatalErrors.findFatal(death));
        assertEquals(death, FatalErrors.findFatal(new Exception(death)));
        assertNull(FatalErrors.findFatal(new IllegalStateException("ordinary")));
    }

    @Test(expected = OutOfMemoryError.class)
    public void rethrowsWrappedVirtualMachineError() {
        FatalErrors.rethrowIfFatal(new Exception(
            new OutOfMemoryError("wrapped")));
    }

    @Test(expected = ThreadDeath.class)
    public void rethrowsWrappedThreadDeath() {
        FatalErrors.rethrowIfFatal(new Exception(new ThreadDeath()));
    }
}

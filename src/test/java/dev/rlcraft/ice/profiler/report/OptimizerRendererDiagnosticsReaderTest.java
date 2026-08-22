package dev.rlcraft.ice.profiler.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OptimizerRendererDiagnosticsReaderTest {
    @Test
    public void readsOnlyTheTwoImmutableStringMethods() {
        OptimizerRendererDiagnosticsReader.Snapshot snapshot =
            OptimizerRendererDiagnosticsReader.read(
                getClass().getClassLoader(), Valid.class.getName());
        assertEquals("renderer-report", snapshot.getReport());
        assertEquals("terrain-summary", snapshot.getSummary());
    }

    @Test
    public void rejectsMissingOrWrongAbiWithoutLinkingTheOptimizer() {
        OptimizerRendererDiagnosticsReader.Snapshot missing =
            OptimizerRendererDiagnosticsReader.read(
                getClass().getClassLoader(), "missing.optimizer.Diagnostics");
        OptimizerRendererDiagnosticsReader.Snapshot wrong =
            OptimizerRendererDiagnosticsReader.read(
                getClass().getClassLoader(), WrongAbi.class.getName());
        assertTrue(missing.isEmpty());
        assertTrue(wrong.isEmpty());
    }

    @Test(expected = OutOfMemoryError.class)
    public void promotesWrappedVirtualMachineErrors() {
        OptimizerRendererDiagnosticsReader.read(getClass().getClassLoader(),
            WrappedFatal.class.getName());
    }

    public static final class Valid {
        public static String report() { return "renderer-report"; }
        public static String summary() { return "terrain-summary"; }
    }

    public static final class WrongAbi {
        public String report() { return "instance"; }
        public static int summary() { return 1; }
    }

    public static final class WrappedFatal {
        public static String report() throws Exception {
            throw new Exception(new OutOfMemoryError("fatal"));
        }
        public static String summary() { return "unreachable"; }
    }
}

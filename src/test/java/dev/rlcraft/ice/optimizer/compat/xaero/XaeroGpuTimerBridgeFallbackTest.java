package dev.rlcraft.ice.optimizer.compat.xaero;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import org.junit.Test;

public class XaeroGpuTimerBridgeFallbackTest {
    @Test
    public void disabledModuleDelegatesToTheOriginalBenchmark() {
        boolean previousEnabled = OptimizerConfig.settings.enabled;
        try {
            OptimizerConfig.settings.enabled = false;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            FakeBenchmark benchmark = new FakeBenchmark();
            assertFalse(XaeroGpuTimerBridge.isFinished(benchmark, 3));
            assertEquals(3456L, XaeroGpuTimerBridge.getAverage(benchmark, 3));
            XaeroGpuTimerBridge.begin(benchmark, 3);
            XaeroGpuTimerBridge.end(benchmark, 3);
            assertEquals(1, benchmark.preCalls);
            assertEquals(1, benchmark.postCalls);
            assertEquals(3, benchmark.lastType);
        } finally {
            OptimizerConfig.settings.enabled = previousEnabled;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        }
    }

    public static final class FakeBenchmark {
        private int preCalls;
        private int postCalls;
        private int lastType = -1;

        public boolean isFinished(int type) {
            lastType = type;
            return false;
        }

        public long getAverage(int type) {
            lastType = type;
            return 3456L;
        }

        public void pre() {
            preCalls++;
        }

        public void post(int type) {
            postCalls++;
            lastType = type;
        }
    }
}

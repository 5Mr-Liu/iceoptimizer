package dev.rlcraft.ice.profiler.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.config.IceConfig;
import java.util.List;
import org.junit.Test;

public class ProbeBridgeTest {
    @Test
    public void aggregatesSpansWithoutRetainingSubjects() {
        boolean old = IceConfig.probes.deepProfiling;
        try {
            IceConfig.probes.deepProfiling = true;
            ProbeBridge.setEnabled(true);
            ProbeBridge.drain();
            long token = ProbeBridge.enter(ProbeIds.ENTITY_TICK, this);
            assertTrue(token != 0L);
            ProbeBridge.exit(token);
            List<ProbeMetric> values = ProbeBridge.drain();
            assertFalse(values.isEmpty());
            ProbeMetric metric = values.get(0);
            assertEquals(ProbeIds.ENTITY_TICK, metric.getProbeId());
            assertEquals(getClass().getName(), metric.getSubjectClass());
            assertEquals(1L, metric.getCalls());
        } finally {
            IceConfig.probes.deepProfiling = old;
            ProbeBridge.setEnabled(false);
            ProbeBridge.drain();
        }
    }
}

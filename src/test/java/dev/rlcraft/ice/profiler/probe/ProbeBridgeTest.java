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

    @Test
    public void rareItemFinishScopeCapturesNestedPotionListenersWithoutDeepMode() {
        boolean old = IceConfig.probes.deepProfiling;
        try {
            IceConfig.probes.deepProfiling = false;
            ProbeBridge.setEnabled(true);
            ProbeBridge.drain();

            assertEquals(0L, ProbeBridge.enterNamed(
                ProbeIds.EVENT_HANDLER, "outside"));
            long outer = ProbeBridge.enterRareNamed(
                ProbeIds.ITEM_USE_FINISH, "minecraft:potion");
            assertTrue(outer != 0L);
            long vanilla = ProbeBridge.enterNamed(
                ProbeIds.POTION_ITEM_FINISH, "vanilla_item_potion");
            assertTrue(vanilla != 0L);
            ProbeBridge.exit(vanilla);
            long listener = ProbeBridge.enterNamed(
                ProbeIds.EVENT_HANDLER, "trinkets.finish");
            assertTrue(listener != 0L);
            ProbeBridge.exit(listener);
            ProbeBridge.exit(outer);

            assertEquals(0L, ProbeBridge.enterNamed(
                ProbeIds.EVENT_HANDLER, "outside-after"));
            List<ProbeMetric> values = ProbeBridge.drain();
            assertTrue(contains(values, ProbeIds.ITEM_USE_FINISH,
                "minecraft:potion"));
            assertTrue(contains(values, ProbeIds.POTION_ITEM_FINISH,
                "vanilla_item_potion"));
            assertTrue(contains(values, ProbeIds.EVENT_HANDLER,
                "trinkets.finish"));
        } finally {
            IceConfig.probes.deepProfiling = old;
            ProbeBridge.setEnabled(false);
            ProbeBridge.drain();
        }
    }

    private static boolean contains(List<ProbeMetric> values, int id,
                                    String subject) {
        for (ProbeMetric value : values) {
            if (value.getProbeId() == id
                && subject.equals(value.getSubjectClass())) return true;
        }
        return false;
    }
}

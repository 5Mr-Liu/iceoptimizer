package dev.rlcraft.ice.profiler.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.analysis.RootCause;
import dev.rlcraft.ice.profiler.analysis.RootCauseAnalyzer;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class HitchClustererTest {
    @Test
    public void mergesRepeatedGenerationHitchesAndKeepsWorstRepresentatives() {
        StackTraceRepository stacks = new StackTraceRepository(32, 16);
        int stack = stacks.intern(new StackTraceElement[] {
            new StackTraceElement("example.mod.WorldGenerator", "generateChunk", "WorldGenerator.java", 42),
            new StackTraceElement("net.minecraft.world.gen.ChunkProviderServer", "provideChunk", "ChunkProviderServer.java", 1)
        });
        HitchClusterer clusterer = new HitchClusterer(new RootCauseAnalyzer(stacks, new ModResolver()), 8, 2, 100);
        for (int i = 1; i <= 3; i++) {
            long now = System.nanoTime();
            HitchCapture capture = new HitchCapture(i, now, now);
            capture.addTrigger(new HitchTrigger(TriggerType.SERVER_TICK, now, System.currentTimeMillis(), TimeUnit.MILLISECONDS.toNanos(100L * i), TimeUnit.MILLISECONDS.toNanos(75), "test"));
            capture.addSample(new StackSample(now, 1L, "Server thread", ThreadRole.SERVER_MAIN, stack, 1L, 0L, Thread.State.RUNNABLE), 10);
            clusterer.add(capture);
        }
        assertEquals(1, clusterer.snapshot().size());
        HitchCluster cluster = clusterer.snapshot().get(0);
        assertEquals(3L, cluster.getOccurrences());
        assertEquals(2, cluster.getRepresentatives().size());
        assertEquals(300.0D, cluster.getMaximumDurationMs(), 0.01D);
        assertEquals(RootCause.WORLD_GENERATION, cluster.getDiagnosis().getRootCause());
        assertTrue(cluster.getDiagnosis().getHotMethod().contains("WorldGenerator.generateChunk"));
    }
}

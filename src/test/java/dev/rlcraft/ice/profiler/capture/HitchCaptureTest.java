package dev.rlcraft.ice.profiler.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.profiler.analysis.ModResolver;
import dev.rlcraft.ice.profiler.analysis.RootCause;
import dev.rlcraft.ice.profiler.analysis.RootCauseAnalyzer;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HitchCaptureTest {
    @Test
    public void keepsNewestPreSamplesAndReservesPostTriggerCapacity() {
        HitchCapture capture = new HitchCapture(1L, 0L, 100L, 200L);
        List<StackSample> pre = new ArrayList<StackSample>();
        for (int i = 1; i <= 10; i++) pre.add(sample(i * 10L, ThreadRole.CHUNK_WORKER, i));
        capture.addPreSamples(pre, 10);
        for (int i = 1; i <= 10; i++) capture.addSample(sample(100L + i * 10L, ThreadRole.CHUNK_WORKER, 100 + i), 10);
        capture.seal();

        assertEquals(4, capture.getPreSampleCount());
        assertEquals(6, capture.getPostSampleCount());
        assertEquals(70L, capture.getFirstPreSampleNanos());
        assertEquals(100L, capture.getLastPreSampleNanos());
        assertEquals(150L, capture.getFirstPostSampleNanos());
        assertEquals(200L, capture.getLastPostSampleNanos());
        assertEquals(6L, capture.getDroppedPreSampleCount());
        assertEquals(4L, capture.getDroppedPostSampleCount());
        assertTrue(capture.isSampleLimitReached());
    }

    @Test
    public void workerFloodCannotEvictClientAndServerMainSamples() {
        HitchCapture capture = new HitchCapture(1L, 0L, 0L, 1000L);
        for (int i = 0; i < 100; i++) capture.addSample(sample(i, ThreadRole.CHUNK_WORKER, i), 10);
        capture.addSample(sample(100L, ThreadRole.CLIENT_MAIN, 1000), 10);
        capture.addSample(sample(101L, ThreadRole.SERVER_MAIN, 1001), 10);
        capture.seal();

        boolean client = false;
        boolean server = false;
        for (StackSample sample : capture.getSamples()) {
            client |= sample.getRole() == ThreadRole.CLIENT_MAIN;
            server |= sample.getRole() == ThreadRole.SERVER_MAIN;
        }
        assertTrue(client);
        assertTrue(server);
        assertEquals(10, capture.getSamples().size());
    }

    @Test
    public void idleChunkWorkersDoNotOverrideRunnableServerRootCause() {
        StackTraceRepository stacks = new StackTraceRepository(32, 16);
        int idle = stacks.intern(new StackTraceElement[] {
            new StackTraceElement("sun.misc.Unsafe", "park", "Unsafe.java", -2),
            new StackTraceElement("net.minecraft.client.renderer.chunk.ChunkRenderDispatcher", "func_178511_d", "ChunkRenderDispatcher.java", 223)
        });
        int generation = stacks.intern(new StackTraceElement[] {
            new StackTraceElement("example.world.BetterCavesGenerator", "generateChunk", "BetterCavesGenerator.java", 42)
        });
        HitchCapture capture = new HitchCapture(1L, 0L, 0L, 1000L);
        for (int i = 0; i < 40; i++) {
            capture.addSample(new StackSample(i, 10L + i, "Chunk Batcher " + i, ThreadRole.CHUNK_WORKER,
                idle, 0L, 0L, Thread.State.WAITING), 100);
        }
        for (int i = 0; i < 3; i++) {
            capture.addSample(new StackSample(100L + i, 1L, "Server thread", ThreadRole.SERVER_MAIN,
                generation, 1L, 0L, Thread.State.RUNNABLE), 100);
        }
        capture.seal();

        assertEquals(RootCause.WORLD_GENERATION,
            new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture).getRootCause());
    }

    private static StackSample sample(long timestamp, ThreadRole role, int stack) {
        return new StackSample(timestamp, role.ordinal() + 1L, role.name(), role, stack,
            1L, 0L, Thread.State.RUNNABLE);
    }
}

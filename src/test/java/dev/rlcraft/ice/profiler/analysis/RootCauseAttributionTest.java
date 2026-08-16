package dev.rlcraft.ice.profiler.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackSampleFilter;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import org.junit.Test;

public final class RootCauseAttributionTest {
    @Test
    public void serverTickUsesItsActualIntervalAndIgnoresIdleFileAndNettyThreads() {
        StackTraceRepository stacks = new StackTraceRepository(32, 16);
        int save = stacks.intern(trace(
            frame("net.minecraft.world.chunk.storage.AnvilChunkLoader", "writeChunkToNBT"),
            frame("net.minecraft.world.gen.ChunkProviderServer", "func_186027_a")));
        int netty = stacks.intern(trace(
            frame("sun.nio.ch.WindowsSelectorImpl$SubSelector", "poll0"),
            frame("io.netty.channel.nio.NioEventLoop", "select")));
        int file = stacks.intern(trace(
            frame("java.lang.Thread", "sleep"),
            frame("net.minecraft.world.storage.ThreadedFileIOBase", "func_75736_b")));
        int unrelated = stacks.intern(trace(
            frame("example.world.NoisyGenerator", "generateChunk")));

        HitchCapture capture = new HitchCapture(1L, 0L, 1_000L, 2_000L);
        capture.addTrigger(new HitchTrigger(TriggerType.SERVER_TICK, 1_000L, 0L,
            500L, 100L, "server tick"));
        for (int i = 0; i < 30; i++) {
            capture.addSample(sample(600L + i, 20L + i, ThreadRole.NETWORK,
                netty, Thread.State.RUNNABLE), 256);
            capture.addSample(sample(600L + i, 60L + i, ThreadRole.FILE_IO,
                file, Thread.State.TIMED_WAITING), 256);
        }
        for (int i = 0; i < 5; i++) {
            capture.addSample(sample(700L + i * 40L, 1L, ThreadRole.SERVER_MAIN,
                save, Thread.State.RUNNABLE), 256);
        }
        for (int i = 0; i < 12; i++) {
            capture.addSample(sample(1_200L + i, 2L, ThreadRole.CLIENT_MAIN,
                unrelated, Thread.State.RUNNABLE), 256);
        }
        capture.seal();

        Diagnosis diagnosis = new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture);
        assertEquals(RootCause.CHUNK_SAVING, diagnosis.getRootCause());
        assertTrue(diagnosis.getHotMethod().contains("AnvilChunkLoader.writeChunkToNBT"));
    }

    @Test
    public void clientFramePrefersClientMainOverServerAndIdleNetworkEvidence() {
        StackTraceRepository stacks = new StackTraceRepository(16, 16);
        int render = stacks.intern(trace(
            frame("net.minecraft.client.renderer.EntityRenderer", "updateCameraAndRender")));
        int generation = stacks.intern(trace(
            frame("example.world.NoisyGenerator", "generateChunk")));
        int netty = stacks.intern(trace(
            frame("sun.nio.ch.WindowsSelectorImpl$SubSelector", "poll0"),
            frame("io.netty.channel.nio.NioEventLoop", "select")));

        HitchCapture capture = new HitchCapture(2L, 0L, 2_000L, 3_000L);
        capture.addTrigger(new HitchTrigger(TriggerType.CLIENT_FRAME, 2_000L, 0L,
            400L, 100L, "render frame"));
        for (int i = 0; i < 4; i++) {
            capture.addSample(sample(1_650L + i * 80L, 1L, ThreadRole.CLIENT_MAIN,
                render, Thread.State.RUNNABLE), 128);
        }
        for (int i = 0; i < 20; i++) {
            capture.addSample(sample(1_650L + i, 2L, ThreadRole.SERVER_MAIN,
                generation, Thread.State.RUNNABLE), 128);
            capture.addSample(sample(1_650L + i, 30L + i, ThreadRole.NETWORK,
                netty, Thread.State.RUNNABLE), 128);
        }
        capture.seal();

        assertEquals(RootCause.CLIENT_RENDER,
            new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture).getRootCause());
    }

    @Test
    public void selectorPollAndThreadedFileSleepAreRecognizedAsIdle() {
        StackSample network = sample(1L, 1L, ThreadRole.NETWORK, 1, Thread.State.RUNNABLE);
        assertTrue(StackSampleFilter.isIdleWorkerWait(network, trace(
            frame("sun.nio.ch.WindowsSelectorImpl$SubSelector", "poll0"),
            frame("io.netty.channel.nio.NioEventLoop", "select"))));
        StackSample file = sample(1L, 2L, ThreadRole.FILE_IO, 2, Thread.State.TIMED_WAITING);
        assertTrue(StackSampleFilter.isIdleWorkerWait(file, trace(
            frame("java.lang.Thread", "sleep"),
            frame("net.minecraft.world.storage.ThreadedFileIOBase", "func_75736_b"))));
    }

    @Test
    public void separatesIceGpuLimiterAndLifecycleStacks() {
        assertEquals(RootCause.ICE_RUNTIME, RootCauseAnalyzer.classifyForTest(
            ThreadRole.CLIENT_MAIN, Thread.State.RUNNABLE, trace(
                frame("dev.rlcraft.ice.optimizer.compat.foamfix.FoamFixUploadBridge", "fence"),
                frame("org.lwjgl.opengl.GL32", "glFenceSync"))));
        assertEquals(RootCause.GPU_DRIVER, RootCauseAnalyzer.classifyForTest(
            ThreadRole.CLIENT_MAIN, Thread.State.RUNNABLE, trace(
                frame("org.lwjgl.opengl.WindowsContextImplementation", "swapBuffers"))));
        assertEquals(RootCause.FRAME_LIMITER_WAIT, RootCauseAnalyzer.classifyForTest(
            ThreadRole.CLIENT_MAIN, Thread.State.TIMED_WAITING, trace(
                frame("java.lang.Thread", "sleep"),
                frame("net.minecraft.client.Minecraft", "runGameLoop"))));
        assertEquals(RootCause.GAME_LIFECYCLE, RootCauseAnalyzer.classifyForTest(
            ThreadRole.SERVER_MAIN, Thread.State.RUNNABLE, trace(
                frame("net.minecraft.server.integrated.IntegratedServer", "func_71260_j"),
                frame("net.minecraft.world.WorldServer", "flush"))));
    }

    private static StackSample sample(long timestamp, long threadId, ThreadRole role,
                                      int stack, Thread.State state) {
        return new StackSample(timestamp, threadId, role.name(), role, stack,
            1L, 0L, state);
    }

    private static StackTraceElement[] trace(StackTraceElement... frames) {
        return frames;
    }

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, owner + ".java", 1);
    }
}

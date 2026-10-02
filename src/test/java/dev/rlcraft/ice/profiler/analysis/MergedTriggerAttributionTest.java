package dev.rlcraft.ice.profiler.analysis;

import static org.junit.Assert.*;

import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchCluster;
import dev.rlcraft.ice.profiler.capture.HitchClusterer;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class MergedTriggerAttributionTest {
    @Test public void longestMissingPeakIsNotExplainedByOtherIntervalsInTheGroup() {
        StackTraceRepository stacks = new StackTraceRepository(8, 8);
        int save = stacks.intern(trace("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a"));
        HitchCapture capture = new HitchCapture(1L, 0L, 2_000L, 5_000L);
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 2_000L, 1_500L));
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 4_000L, 100L));
        capture.addSample(sample(3_950L, ThreadRole.SERVER_MAIN, save), 20);
        Diagnosis result = new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture);
        assertEquals(RootCause.CHUNK_SAVING, result.getRootCause());
        assertTrue(result.getEvidence().toString().contains("当前归因来自其他触发区间"));
        assertTrue(result.getEvidence().toString().contains("不能解释该峰值"));
    }

    @Test public void identicalHotMethodOnTwoRolesStillHasSeparateClusters() {
        StackTraceRepository stacks = new StackTraceRepository(8, 8);
        int work = stacks.intern(trace("dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge", "parseSettings"));
        HitchCapture capture = new HitchCapture(1L, 0L, 1_000L, 3_000L);
        capture.addTrigger(trigger(TriggerType.CLIENT_TICK, 1_000L, 400L));
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 2_000L, 200L));
        capture.addSample(sample(800L, ThreadRole.CLIENT_MAIN, work), 20);
        capture.addSample(sample(1_900L, ThreadRole.SERVER_MAIN, work), 20);
        HitchClusterer clusterer = new HitchClusterer(new RootCauseAnalyzer(stacks, new ModResolver()), 8, 1, 20);
        clusterer.add(capture);
        assertEquals(2, clusterer.snapshot().size());
        for (HitchCluster cluster : clusterer.snapshot()) {
            assertEquals(RootCause.ICE_RUNTIME, cluster.getDiagnosis().getRootCause());
            assertEquals(1, cluster.getTriggerCounts().size());
        }
    }

    @Test public void clientMaximumCannotHideServerUnionAndOverlapsDoNotDoubleCount() {
        StackTraceRepository stacks = new StackTraceRepository(32, 16);
        int render = stacks.intern(trace("net.minecraft.client.renderer.EntityRenderer", "renderWorld"));
        int save = stacks.intern(trace("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a"));
        int noise = stacks.intern(trace("example.world.Generator", "generateChunk"));
        HitchCapture capture = new HitchCapture(1L, 0L, 1_000L, 4_000L);
        capture.addTrigger(trigger(TriggerType.CLIENT_FRAME, 1_000L, 900L));
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 2_000L, 300L));
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 2_100L, 300L));
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 3_000L, 300L));
        for (int i = 0; i < 5; i++) capture.addSample(sample(200L + i, ThreadRole.CLIENT_MAIN, render), 100);
        capture.addSample(sample(1_850L, ThreadRole.SERVER_MAIN, save), 100);
        capture.addSample(sample(1_950L, ThreadRole.SERVER_MAIN, save), 100);
        capture.addSample(sample(2_900L, ThreadRole.SERVER_MAIN, save), 100);
        for (int i = 0; i < 8; i++) capture.addSample(sample(3_500L + i, ThreadRole.SERVER_MAIN, noise), 100);
        HitchClusterer clusterer = new HitchClusterer(new RootCauseAnalyzer(stacks, new ModResolver()), 8, 2, 100);
        clusterer.add(capture);
        List<HitchCluster> clusters = clusterer.snapshot();
        assertEquals(2, clusters.size());
        int represented = 0;
        Set<Long> sequences = new HashSet<Long>();
        boolean client = false, server = false;
        for (HitchCluster cluster : clusters) {
            HitchCapture view = cluster.getRepresentatives().get(0);
            assertTrue(sequences.add(view.getSequence()));
            represented += view.getSamples().size();
            if (cluster.getDiagnosis().getRootCause() == RootCause.CHUNK_SAVING) {
                server = true;
                assertEquals(3, view.getTriggers().size());
                assertEquals(2_000L, view.getTriggerNanos());
                assertTrue(cluster.getDiagnosis().getEvidence().toString().contains("归因使用 3 个"));
            }
            if (cluster.getDiagnosis().getRootCause() == RootCause.CLIENT_RENDER) client = true;
        }
        assertTrue(client && server);
        assertEquals(capture.getSamples().size(), represented);
        assertEquals(represented, clusterer.getDetailedSamples());
    }

    @Test public void absentIntervalDoesNotBorrowNearbyServerStackOrClientRenderPrior() {
        StackTraceRepository stacks = new StackTraceRepository(8, 8);
        int save = stacks.intern(trace("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a"));
        HitchCapture capture = new HitchCapture(2L, 0L, 1_000L, 2_000L);
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 1_000L, 200L));
        capture.addSample(sample(1_500L, ThreadRole.SERVER_MAIN, save), 10);
        Diagnosis result = new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture);
        assertEquals(RootCause.UNKNOWN, result.getRootCause());
        assertEquals(0.0D, result.getConfidence(), 0.0D);
        assertEquals("未识别", result.getHotMethod());
        assertTrue(result.getEvidence().toString().contains("缺少 1 个"));
        HitchCapture frame = new HitchCapture(3L, 0L, 1_000L, 2_000L);
        frame.addTrigger(trigger(TriggerType.CLIENT_FRAME, 1_000L, 200L));
        assertEquals(RootCause.UNKNOWN, new RootCauseAnalyzer(stacks, new ModResolver()).analyze(frame).getRootCause());
    }

    @Test public void nativeFileBelowIceCacheIsOriginalOtgPopulationNotIceComputation() {
        StackTraceElement[] trace = {
            new StackTraceElement("sun.nio.fs.WindowsNativeDispatcher", "CreateFile0", null, -2),
            frame("java.nio.file.Files", "newInputStream"),
            frame("dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge", "loadSettingsEntry"),
            frame("dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge", "lambda$readSettings$0"),
            frame("dev.rlcraft.ice.shadow.caffeine.cache.BoundedLocalCache", "computeIfAbsent"),
            frame("dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge", "readSettings"),
            frame("com.pg85.otg.configuration.io.FileSettingsReaderOTGPlus", "readSettings"),
            frame("com.pg85.otg.customobjects.bo3.BO3", "onEnable"),
            frame("com.pg85.otg.generator.resource.TreeGen", "spawnInChunk"),
            frame("com.pg85.otg.generator.ObjectSpawner", "populate")
        };
        Diagnosis result = diagnose(trace);
        assertEquals(RootCause.WORLD_GENERATION, result.getRootCause());
        assertFalse(result.getMod() == ModIdentity.ICE);
        assertEquals("com.pg85.otg.configuration.io.FileSettingsReaderOTGPlus.readSettings", result.getHotMethod());
        assertTrue(result.getEvidence().toString().contains("原生被调用者"));
    }

    @Test public void actualIceLeafStillHasIceAttributionAndNativeGlDoesNot() {
        Diagnosis result = diagnose(trace("dev.rlcraft.ice.optimizer.compat.otg.OtgSynchronousIoBridge", "parseSettings"));
        assertEquals(RootCause.ICE_RUNTIME, result.getRootCause());
        assertEquals(ModIdentity.ICE, result.getMod());
        assertTrue(result.getHotMethod().endsWith(".parseSettings"));
        assertEquals(RootCause.GPU_DRIVER, RootCauseAnalyzer.classifyForTest(ThreadRole.CLIENT_MAIN, Thread.State.RUNNABLE,
            new StackTraceElement[] {new StackTraceElement("org.lwjgl.opengl.GL32", "nglClientWaitSync", null, -2),
                frame("dev.rlcraft.ice.optimizer.compat.foamfix.FoamFixUploadBridge", "fence")}));
    }

    @Test public void realSrgNbtSaveWinsOverChunkProviderCallerAndCompressionIsSaving() {
        assertEquals(RootCause.CHUNK_SAVING, diagnose(new StackTraceElement[] {
            frame("java.util.IdentityHashMap", "get"),
            frame("net.minecraftforge.registries.GameData$BlockCallbacks$1", "get"),
            frame("net.minecraft.world.chunk.BlockStateContainer", "func_186017_a"),
            frame("net.minecraft.world.chunk.storage.AnvilChunkLoader", "func_75820_a"),
            frame("net.minecraft.world.gen.ChunkProviderServer", "provideChunk")}).getRootCause());
        assertEquals(RootCause.CHUNK_SAVING, diagnose(new StackTraceElement[] {
            new StackTraceElement("java.util.zip.Deflater", "deflateBytes", null, -2),
            frame("com.pg85.otg.util.CompressionUtils", "compress"),
            frame("com.pg85.otg.customobjects.structures.CustomStructureFileManager", "saveStructuresRegionFile")}).getRootCause());
    }

    private static Diagnosis diagnose(StackTraceElement[] trace) {
        StackTraceRepository stacks = new StackTraceRepository(8, 32);
        int stack = stacks.intern(trace);
        HitchCapture capture = new HitchCapture(1L, 0L, 1_000L, 2_000L);
        capture.addTrigger(trigger(TriggerType.SERVER_TICK, 1_000L, 300L));
        for (int i = 0; i < 4; i++) capture.addSample(sample(800L + i, ThreadRole.SERVER_MAIN, stack), 20);
        return new RootCauseAnalyzer(stacks, new ModResolver()).analyze(capture);
    }
    private static HitchTrigger trigger(TriggerType type, long end, long duration) {
        return new HitchTrigger(type, end, end / 1_000_000L, duration, 1L, "test");
    }
    private static StackSample sample(long time, ThreadRole role, int stack) {
        return new StackSample(time, role.ordinal() + 1L, role.name(), role, stack, 1L, 0L, Thread.State.RUNNABLE);
    }
    private static StackTraceElement[] trace(String owner, String method) { return new StackTraceElement[] {frame(owner, method)}; }
    private static StackTraceElement frame(String owner, String method) { return new StackTraceElement(owner, method, "Test.java", 1); }
}

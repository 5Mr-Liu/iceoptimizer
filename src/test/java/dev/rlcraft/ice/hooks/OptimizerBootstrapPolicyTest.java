package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OptimizerBootstrapPolicyTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyExplicitScopedFalseDisablesRenderer() throws Exception {
        assertFalse(read(config("B:modernRenderer=false")));
        assertFalse(read("\uFEFF\"general\" {\n \"settings\" {\n B:modernRenderer = false\n}\n}\n"));
        assertTrue(read(config("B:modernRenderer=true")));
        assertTrue(read(config("# B:modernRenderer=false")));
        assertTrue(read("other {\nsettings {\nB:modernRenderer=false\n}\n}\n"));
        assertTrue(read(config("S:example <\nB:modernRenderer=false\n>")));
        assertTrue(read(""));
    }

    @Test public void ambiguousOrIncompleteConfigurationKeepsExistingBehavior() throws Exception {
        assertTrue(read(config("B:modernRenderer=unknown")));
        assertTrue(read(config("B:modernRenderer=false\nB:modernRenderer=true")));
        assertTrue(read(config("B:modernRenderer=false\nB:modernRenderer=false")));
        assertTrue(read("general {\nsettings {\nB:modernRenderer=false\n}"));
        assertTrue(read(config("B:modernRenderer=false\nS:unfinished <")));
    }

    @Test public void independentAndSharedCapabilitiesRemainInstalled() {
        assertFalse(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream"), false));
        assertFalse(OptimizerBootstrapPolicy.shouldInstall(
            target("legacy-gl-island,modern-visibility-hzb,modern-hud-stream"), false));
        assertFalse(OptimizerBootstrapPolicy.shouldInstall(target("fbp-particle-adapter"), false));
        assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("fbp-cube-submission"), false));
        assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream,orelib-gl-state"), false));
        assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("modern-unknown"), false));
        assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream"), true));
    }

    @Test public void nativeOwnerRespectsGlobalDisableAtStartup() throws Exception {
        File directory = temporary.newFolder();
        File config = new File(directory, "config/ice-optimizer.cfg");
        assertTrue(config.getParentFile().mkdir());
        try {
            Files.write(config.toPath(), config("B:enabled=false\nB:nativeTerrainRenderer=true")
                .getBytes(StandardCharsets.UTF_8));
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertFalse(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
            Files.write(config.toPath(), config("B:enabled=true\nB:nativeTerrainRenderer=true")
                .getBytes(StandardCharsets.UTF_8));
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertTrue(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
            assertFalse(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream")));
        } finally {
            OptimizerBootstrapPolicy.initialize(null);
        }
    }

    @Test public void firstLaunchSelectsNativeTerrainWithoutCreatingConfig() throws Exception {
        File directory = temporary.newFolder();
        try {
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertTrue(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
            assertFalse(new File(directory, "config/ice-optimizer.cfg").exists());
            assertFalse(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream")));
        } finally { OptimizerBootstrapPolicy.initialize(null); }
    }

    @Test public void oldConfigDefaultsToNativeButExplicitDisablesAreRespected() throws Exception {
        File directory = temporary.newFolder();
        File file = new File(directory, "config/ice-optimizer.cfg");
        assertTrue(file.getParentFile().mkdir());
        try {
            Files.write(file.toPath(), config("B:enabled=true\nB:modernRenderer=true")
                .getBytes(StandardCharsets.UTF_8));
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertTrue(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
            Files.write(file.toPath(), config("B:enabled=false").getBytes(StandardCharsets.UTF_8));
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertFalse(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
            Files.write(file.toPath(), config("B:nativeTerrainRenderer=false")
                .getBytes(StandardCharsets.UTF_8));
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertFalse(dev.rlcraft.ice.early.EarlyRendererOwnership.hasNativeTerrainRenderer());
        } finally { OptimizerBootstrapPolicy.initialize(null); }
    }

    @Test public void disabledTargetReturnsOriginalBytesWithoutRunningAdapter() throws Exception {
        File directory = temporary.newFolder();
        File config = new File(directory, "config/ice-optimizer.cfg");
        assertTrue(config.getParentFile().mkdir());
        Files.write(config.toPath(), config("B:modernRenderer=false").getBytes(StandardCharsets.UTF_8));
        try {
            OptimizerBootstrapPolicy.initialize(Collections.<String, Object>singletonMap("mcLocation", directory));
            assertFalse(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream")));
            String name = GlStateTrackingAdapter.GL_STATE_MANAGER;
            for (TargetSpec entry : OptimizerTargetCatalog.findAll(name)) {
                assertFalse("all GL tracker targets must be renderer-only", OptimizerBootstrapPolicy.shouldInstall(entry));
            }
            byte[] original = GlStateTrackingAdapterTest.syntheticManager(name);
            assertSame(original, new IceOptimizerTransformer().transform(name, name, original));
            assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("vanilla-chunk-compression")));
        } finally {
            OptimizerBootstrapPolicy.initialize(null);
        }
        assertTrue(OptimizerBootstrapPolicy.shouldInstall(target("modern-hud-stream")));
    }

    private static boolean read(String text) throws Exception {
        return OptimizerBootstrapPolicy.readRendererEnabled(new StringReader(text));
    }
    private static String config(String setting) {
        return "general {\nsettings {\n" + setting + "\n}\n}\n";
    }
    private static TargetSpec target(String modules) {
        return new TargetSpec("example.Target", modules, "unused", Collections.<String>emptySet());
    }
}

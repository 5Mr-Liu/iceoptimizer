package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import dev.rlcraft.ice.early.EarlyRendererOwnership;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.jar.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ExternalTerrainRendererDiscoveryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void detectsRenamedArchiveAndVersionDirectoryButNotDisabledFiles() throws Exception {
        File game = temporary.newFolder();
        File jar = new File(game, "mods/1.12.2/renamed-renderer.jar");
        write(jar, true, true);
        assertTrue(ExternalTerrainRendererDiscovery.present(game));
        Files.move(jar.toPath(), new File(jar.getParentFile(), "renamed-renderer.jar.disabled").toPath());
        assertFalse(ExternalTerrainRendererDiscovery.present(game));
    }

    @Test public void filenamesAndIncompleteArchivesAreNotOwnershipEvidence() throws Exception {
        File game = temporary.newFolder();
        File jar = new File(game, "mods/Nothirium.jar");
        write(jar, false, true);
        assertFalse(ExternalTerrainRendererDiscovery.present(game));
        write(jar, true, false);
        assertFalse(ExternalTerrainRendererDiscovery.present(game));
        Files.write(jar.toPath(), new byte[]{1,2,3});
        assertFalse(ExternalTerrainRendererDiscovery.present(game));
    }

    @Test public void bootstrapAndRuntimeAgreeWithoutRewritingUserSettings() throws Exception {
        File game = temporary.newFolder();
        write(new File(game, "mods/external.jar"), true, true);
        OptimizerConfig.Settings previous = OptimizerConfig.settings;
        try {
            OptimizerConfig.settings = new OptimizerConfig.Settings();
            ClientOptimizerConfig before = ClientOptimizerConfig.capture();
            OptimizerBootstrapPolicy.initialize(Collections.<String,Object>singletonMap("mcLocation", game));
            assertTrue(EarlyRendererOwnership.hasExternalTerrainRenderer());
            ClientOptimizerConfig external = ClientOptimizerConfig.capture();
            assertTrue(external.usesExternalTerrainRenderer());
            assertTrue(OptimizerConfig.settings.modernRenderer);
            for (OptimizationModule module : OptimizationModule.values()) {
                if (module.isRuntimeManagedRenderer() || EarlyRendererOwnership.isRendererModule(module.getId())) {
                    assertFalse(module.getId(), external.enabled(module));
                } else assertEquals(module.getId(), before.enabled(module), external.enabled(module));
            }
            String gl = GlStateTrackingAdapter.GL_STATE_MANAGER;
            byte[] original = GlStateTrackingAdapterTest.syntheticManager(gl);
            assertSame(original, new IceOptimizerTransformer().transform(gl, gl, original));
            OptimizerBootstrapPolicy.initialize(null);
            assertFalse(ClientOptimizerConfig.capture().usesExternalTerrainRenderer());
            assertTrue("captured decision stays immutable", external.usesExternalTerrainRenderer());
            assertTrue(ClientOptimizerConfig.capture().enabled(OptimizationModule.MODERN_TERRAIN_BACKEND));
        } finally {
            OptimizerConfig.settings = previous;
            OptimizerBootstrapPolicy.initialize(null);
        }
    }

    private static void write(File file, boolean manifestMatches, boolean classesPresent) throws Exception {
        file.getParentFile().mkdirs();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLCorePlugin", manifestMatches
            ? "meldexun.nothirium.mc.asm.NothiriumPlugin" : "example.UnrelatedPlugin");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file), manifest)) {
            if (classesPresent) for (String path : new String[]{
                "meldexun/nothirium/mc/asm/NothiriumPlugin.class",
                "meldexun/nothirium/mc/renderer/ChunkRenderManager.class"}) {
                out.putNextEntry(new JarEntry(path)); out.write(0); out.closeEntry();
            }
        }
    }
}

package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OptimizerDiscoveryTest {
    private static final String OUTPUT_PROPERTY = "ice.optimizer.developmentDiskOutput";
    private static final String LEGACY_PROPERTY = "ice.optimizer.discoveryDump";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultModeDoesNotCreateOptimizerOutputDirectories() throws Exception {
        File gameDirectory = temporary.newFolder("zero-write-game");
        withPropertiesCleared(new CheckedRunnable() {
            @Override public void run() {
                OptimizerDiscovery.initialize(data(gameDirectory));
                assertFalse(OptimizerDiscovery.isEnabled());
                assertFalse(OptimizerDiscovery.dump(target(), repeat('a', 64), new byte[] { 1, 2, 3 }));
                assertFalse(new File(gameDirectory, "ice-optimizer").exists());
            }
        });
    }

    @Test
    public void explicitDevelopmentPropertyAllowsBoundedDiscoveryArtifacts() throws Exception {
        final File gameDirectory = temporary.newFolder("property-game");
        withPropertiesCleared(new CheckedRunnable() {
            @Override public void run() {
                System.setProperty(OUTPUT_PROPERTY, "true");
                OptimizerDiscovery.initialize(data(gameDirectory));
                assertTrue(OptimizerDiscovery.isEnabled());
                assertTrue(OptimizerDiscovery.dump(target(), repeat('b', 64), new byte[] { 1, 2, 3 }));
                File[] files = new File(gameDirectory, "ice-optimizer/discovery").listFiles();
                assertTrue(files != null && files.length == 2);
            }
        });
    }

    @Test
    public void forgeConfigOptInIsReadBeforeNormalModInitialization() throws Exception {
        final File gameDirectory = temporary.newFolder("config-game");
        File configDirectory = new File(gameDirectory, "config");
        assertTrue(configDirectory.mkdirs());
        Files.write(new File(configDirectory, "ice-optimizer.cfg").toPath(),
            Collections.singletonList("    B:developmentDiskOutput=true"), StandardCharsets.UTF_8);
        withPropertiesCleared(new CheckedRunnable() {
            @Override public void run() {
                OptimizerDiscovery.initialize(data(gameDirectory));
                assertTrue(OptimizerDiscovery.isEnabled());
            }
        });
    }

    private static Map<String, Object> data(File gameDirectory) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("mcLocation", gameDirectory);
        return result;
    }

    private static TargetSpec target() {
        return new TargetSpec("example.Target", "core-runtime", "none", Collections.<String>emptySet());
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void withPropertiesCleared(CheckedRunnable action) throws Exception {
        String output = System.getProperty(OUTPUT_PROPERTY);
        String legacy = System.getProperty(LEGACY_PROPERTY);
        try {
            System.clearProperty(OUTPUT_PROPERTY);
            System.clearProperty(LEGACY_PROPERTY);
            action.run();
        } finally {
            restore(OUTPUT_PROPERTY, output);
            restore(LEGACY_PROPERTY, legacy);
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}

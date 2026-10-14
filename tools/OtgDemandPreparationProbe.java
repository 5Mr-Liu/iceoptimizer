package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline configuration-dependency check; all fixture files stay in this audit directory. */
public final class OtgDemandPreparationProbe {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        Map<String, File> registered = new LinkedHashMap<String, File>();
        for (int i = 0; i < 64; i++) {
            Path file = root.resolve("unused-" + i + ".bo3");
            Files.write(file, Arrays.asList("Author: audit", "Block(0,0,0,STONE)"), StandardCharsets.UTF_8);
            registered.put("unused-" + i, file.toFile());
        }
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.otgObjectPreparation = true;
        OptimizerConfig.settings.otgSynchronousFileCache = false;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.OTG_OBJECT_PREPARATION).patchInstalled("audit", "audit");
        if (!OptimizerRegistry.isOperational(OptimizationModule.OTG_OBJECT_PREPARATION)
                || OptimizerRegistry.isOperational(OptimizationModule.OTG_SYNC_FILE_CACHE))
            throw new AssertionError("unexpected module configuration");
        long before = OtgSynchronousIoBridge.settingsCacheEntriesForTest();
        long started = System.nanoTime();
        OtgObjectPreparationBridge.prepare(registered);
        long elapsed = System.nanoTime() - started;
        long after = OtgSynchronousIoBridge.settingsCacheEntriesForTest();
        if (after - before != (Boolean.getBoolean("ice.baseline") ? 64 : 0)) throw new AssertionError("unexpected speculative preparation count: " + after);
        System.out.println("PASS registered=64 requested_objects=0 file_cache_enabled=false prepared_settings=" + (after - before) + " elapsed_ms=" + elapsed / 1000000.0);
        OtgSynchronousIoBridge.advanceConfigurationGeneration();
    }
}

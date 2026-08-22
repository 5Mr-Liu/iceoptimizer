package dev.rlcraft.ice.optimizer.common;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.OptimizerRuntimeConfig;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import dev.rlcraft.ice.optimizer.lock.RlcPackLock;
import java.io.File;
import net.minecraftforge.fml.common.Loader;

/** Shared bootstrap for physical clients and dedicated servers. */
public final class CommonOptimizerBootstrap {
    private CommonOptimizerBootstrap() {
    }

    public static OptimizerBootstrapResult initialize(File gameDirectory, OptimizerRuntimeConfig config) {
        OptimizerRegistry.beginRuntime();
        OptimizerRegistry.configure(config);
        boolean coreModPresent = replayCorePatchJournal();
        PackLockStatus packLock = new RlcPackLock().inspect(Loader.instance().getActiveModList(), gameDirectory,
            config.getRuntimeSide(), config.isStrictPackLock(), config.isDevelopmentDiskOutput());
        OptimizerRegistry.enforcePackLock(packLock);
        return new OptimizerBootstrapResult(packLock, coreModPresent);
    }

    private static boolean replayCorePatchJournal() {
        try {
            Class<?> journal = Class.forName("dev.rlcraft.ice.hooks.OptimizerPatchJournal");
            journal.getMethod("replay").invoke(null);
            return true;
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // The main mod remains safe without the CoreMod, but no bytecode optimization can activate.
            return false;
        }
    }
}

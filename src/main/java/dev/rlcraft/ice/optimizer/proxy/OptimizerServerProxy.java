package dev.rlcraft.ice.optimizer.proxy;

import dev.rlcraft.ice.optimizer.server.ServerOptimizerRuntime;
import java.io.File;

/** Dedicated-server lifecycle adapter with no reference to Minecraft client classes. */
public final class OptimizerServerProxy extends OptimizerCommonProxy {
    @Override
    public void preInit(File gameDirectory) {
        ServerOptimizerRuntime.INSTANCE.initialize(gameDirectory);
    }

    @Override
    public void serverStopped() {
        ServerOptimizerRuntime.INSTANCE.shutdown();
    }
}

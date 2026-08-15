package dev.rlcraft.ice.optimizer.proxy;

import dev.rlcraft.ice.optimizer.client.ClientOptimizerController;
import java.io.File;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;

public final class OptimizerClientProxy extends OptimizerCommonProxy {
    @Override
    public void preInit(File gameDirectory) {
        ClientOptimizerController.INSTANCE.preInit(gameDirectory);
        FMLCommonHandler.instance().bus().register(ClientOptimizerController.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ClientOptimizerController.INSTANCE);
    }

    @Override
    public void init() {
        ClientOptimizerController.INSTANCE.init();
    }
}

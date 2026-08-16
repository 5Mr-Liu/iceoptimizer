package dev.rlcraft.ice.optimizer.proxy;

import dev.rlcraft.ice.optimizer.client.ClientOptimizerController;
import dev.rlcraft.ice.optimizer.compat.save.ChunkSaveCompressionBridge;
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

    @Override
    public void serverStopped() {
        ChunkSaveCompressionBridge.reset();
    }
}

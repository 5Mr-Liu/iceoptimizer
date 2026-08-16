package dev.rlcraft.ice.proxy;

import dev.rlcraft.ice.server.ServerProfilerController;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import java.io.File;

public class CommonProxy {
    public void preInit(File gameDirectory) {
        FMLCommonHandler.instance().bus().register(ServerProfilerController.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ServerProfilerController.INSTANCE);
    }

    public void init() {
    }
}

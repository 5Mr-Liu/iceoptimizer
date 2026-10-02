package dev.rlcraft.ice.proxy;

import dev.rlcraft.ice.client.ClientProfilerController;
import dev.rlcraft.ice.command.CommandIceClient;
import java.io.File;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;

public final class ClientProxy extends CommonProxy {
    @Override
    public void preInit(File gameDirectory) {
        super.preInit(gameDirectory);
        FMLCommonHandler.instance().bus().register(ClientProfilerController.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ClientProfilerController.INSTANCE);
    }

    @Override
    public void init() {
        ClientProfilerController.INSTANCE.registerKeys();
        ClientCommandHandler.instance.registerCommand(new CommandIceClient());
    }
}

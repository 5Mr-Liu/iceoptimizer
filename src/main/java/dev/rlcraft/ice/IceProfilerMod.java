package dev.rlcraft.ice;

import dev.rlcraft.ice.command.CommandIce;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.proxy.CommonProxy;
import dev.rlcraft.ice.server.ServerProfilerController;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Standalone bounded hitch recorder and report generator. */
@Mod(
    modid = IceProfilerMod.MOD_ID,
    name = IceProfilerMod.NAME,
    version = IceProfilerMod.VERSION,
    acceptableRemoteVersions = "*"
)
public final class IceProfilerMod {
    public static final String MOD_ID = "iceprofiler";
    public static final String NAME = "ICE Performance Recorder";
    public static final String VERSION = "0.10.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @SidedProxy(
        clientSide = "dev.rlcraft.ice.proxy.ClientProxy",
        serverSide = "dev.rlcraft.ice.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            ProfilerRuntime.INSTANCE.initialize(event.getModConfigurationDirectory().getParentFile());
        } catch (Throwable error) {
            if (!dev.rlcraft.ice.config.IceConfig.compatibility.failOpen) {
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw new RuntimeException(error);
            }
            LOGGER.error("ICE Recorder 初始化失败，采集器已停用，游戏将继续启动", error);
        }
        proxy.preInit(event.getModConfigurationDirectory().getParentFile());
        LOGGER.info("ICE Recorder {} 启动；诊断与报告功能已从优化器完全拆分", VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ServerProfilerController.INSTANCE.onServerStarting(event.getServer());
        event.registerServerCommand(new CommandIce());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        ServerProfilerController.INSTANCE.onServerStopped();
        if (FMLCommonHandler.instance().getSide() == Side.SERVER) ProfilerRuntime.INSTANCE.shutdown();
    }
}

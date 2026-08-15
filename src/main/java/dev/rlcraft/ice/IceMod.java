package dev.rlcraft.ice;

import dev.rlcraft.ice.optimizer.proxy.OptimizerCommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Required-on-both-sides RLCraft optimizer. Diagnostics live in the separate profiler mod. */
@Mod(
    modid = IceMod.MOD_ID,
    name = IceMod.NAME,
    version = IceMod.VERSION,
    acceptableRemoteVersions = IceMod.REMOTE_VERSION_RANGE
)
public final class IceMod {
    public static final String MOD_ID = "iceoptimizer";
    public static final String NAME = "ICE RLCraft Optimizer";
    public static final String VERSION = "0.8.0";
    public static final String REMOTE_VERSION_RANGE = "[0.8.0]";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @SidedProxy(
        clientSide = "dev.rlcraft.ice.optimizer.proxy.OptimizerClientProxy",
        serverSide = "dev.rlcraft.ice.optimizer.proxy.OptimizerServerProxy"
    )
    public static OptimizerCommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event.getModConfigurationDirectory().getParentFile());
        LOGGER.info("ICE Optimizer {} 启动；客户端与服务端必须安装同一版本", VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped();
    }
}

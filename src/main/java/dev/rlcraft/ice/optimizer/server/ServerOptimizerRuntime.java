package dev.rlcraft.ice.optimizer.server;

import dev.rlcraft.ice.IceMod;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.ServerOptimizerConfig;
import dev.rlcraft.ice.optimizer.common.CommonOptimizerBootstrap;
import dev.rlcraft.ice.optimizer.common.OptimizerBootstrapResult;
import dev.rlcraft.ice.optimizer.lock.PackLockState;
import dev.rlcraft.ice.optimizer.lock.PackLockStatus;
import java.io.File;
import java.util.Collections;

/** Physical dedicated-server runtime. It owns no sampler and starts no background recorder. */
public final class ServerOptimizerRuntime {
    public static final ServerOptimizerRuntime INSTANCE = new ServerOptimizerRuntime();

    private volatile boolean initialized;
    private ServerOptimizerConfig config;
    private PackLockStatus packLock = new PackLockStatus(PackLockState.DISCOVERY,
        "尚未初始化", Collections.emptyList(), null);
    private boolean coreModPresent;

    private ServerOptimizerRuntime() {
    }

    public synchronized void initialize(File gameDirectory) {
        if (initialized) return;
        config = ServerOptimizerConfig.capture();
        OptimizerBootstrapResult bootstrap = CommonOptimizerBootstrap.initialize(gameDirectory, config);
        packLock = bootstrap.getPackLock();
        coreModPresent = bootstrap.isCoreModPresent();
        initialized = true;

        if (!config.isEnabled()) {
            IceMod.LOGGER.info("ICE 专用服务端优化运行时已由配置关闭");
            return;
        }

        OptimizerRegistry.breaker(OptimizationModule.CORE_RUNTIME).activate(
            "专用服务端安全模块注册表与独立熔断已启动");
        int enabledModules = 0;
        for (OptimizationModule module : OptimizationModule.values()) {
            if (config.enabled(module)) enabledModules++;
        }
        IceMod.LOGGER.info("ICE RLCraft 专用服务端优化运行时启动：已允许 {} 个服务端模块，兼容策略 {}",
            enabledModules, packLock.getState());
        if (!coreModPresent) {
            IceMod.LOGGER.error("ICE Optimizer Core JAR 未加载；服务端将安全运行，但字节码优化不会生效");
        }
        if (!packLock.permitsPatches()) {
            IceMod.LOGGER.warn("ICE 服务端外部优化补丁保持关闭：{}", packLock.getDetail());
        }
    }

    public synchronized void shutdown() {
        if (!initialized) return;
        OptimizerRegistry.shutdown("专用服务端已停止");
        initialized = false;
        config = null;
        coreModPresent = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isCoreModPresent() {
        return coreModPresent;
    }

    public PackLockStatus getPackLock() {
        return packLock;
    }
}

package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.compat.foamfix.FoamFixUploadBridge;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineShaderLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifinePassLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineRegionBridge;
import dev.rlcraft.ice.optimizer.compat.optifine.OptifineShaderSourceBridge;
import dev.rlcraft.ice.optimizer.compat.portal.WorldPortalBridge;
import dev.rlcraft.ice.optimizer.compat.render.RenderPassLifecycleBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureUploadBridge;
import dev.rlcraft.ice.optimizer.compat.texture.AnimatedTextureVisibilityBridge;
import java.io.File;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/** Forge lifecycle adapter.  It does not move game-state work off the client thread. */
public final class ClientOptimizerController implements IResourceManagerReloadListener {
    public static final ClientOptimizerController INSTANCE = new ClientOptimizerController();
    private final Minecraft minecraft = Minecraft.getMinecraft();
    private Object lastWorld;
    private boolean reloadListenerRegistered;
    private boolean missingCoreWarningSent;
    private final RenderLifecycleMonitor renderLifecycle = new RenderLifecycleMonitor();
    private int shaderPollTicks;

    private ClientOptimizerController() {
    }

    public void preInit(File gameDirectory) {
        ClientOptimizerRuntime.INSTANCE.initialize(gameDirectory);
        FoamFixUploadBridge.installCoreBridge();
        AnimatedTextureUploadBridge.installCoreBridge();
        AnimatedTextureVisibilityBridge.installCoreBridge();
        HudRenderBridge.installCoreBridge();
        RenderPassLifecycleBridge.installCoreBridge();
        OptifinePassLifecycleBridge.installCoreBridge();
        OptifineShaderLifecycleBridge.installCoreBridge();
        OptifineRegionBridge.installCoreBridge();
        OptifineShaderSourceBridge.installCoreBridge();
        WorldPortalBridge.installCoreBridge();
    }

    public void init() {
        IResourceManager resources = minecraft.getResourceManager();
        if (!reloadListenerRegistered && resources instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) resources).registerReloadListener(this);
            reloadListenerRegistered = true;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        ClientOptimizerRuntime.INSTANCE.beginClientTick();
        if (++shaderPollTicks >= 20) {
            shaderPollTicks = 0;
            renderLifecycle.pollShaderPack(ClientOptimizerRuntime.INSTANCE);
        }
        if (!missingCoreWarningSent && minecraft.player != null
            && !ClientOptimizerRuntime.INSTANCE.status().isCoreModPresent()) {
            missingCoreWarningSent = true;
            minecraft.player.sendMessage(new TextComponentString(
                "§c[ICE] Optimizer Core JAR 未加载 / missing；字节码优化当前没有生效。"));
        }
        Object world = minecraft.world;
        if (world != lastWorld) {
            lastWorld = world;
            ClientOptimizerRuntime.INSTANCE.worldChanged();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            renderLifecycle.beforeFrame(minecraft, ClientOptimizerRuntime.INSTANCE);
            ClientOptimizerRuntime.INSTANCE.beginFrame();
            ClientOptimizerRuntime.INSTANCE.drainRenderQueue();
        } else {
            ClientOptimizerRuntime.INSTANCE.endFrame();
        }
    }

    @SubscribeEvent
    public void onDebugOverlay(RenderGameOverlayEvent.Text event) {
        if (!F3OptimizerSummary.shouldRender(OptimizerConfig.display.showF3Summary,
            minecraft.gameSettings.showDebugInfo)) return;
        List<String> lines = F3OptimizerSummary.format(ClientOptimizerRuntime.INSTANCE.status());
        event.getRight().addAll(lines);
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        ClientOptimizerRuntime.INSTANCE.resourcesReloaded();
    }

    @SubscribeEvent
    public void onTextureStitch(TextureStitchEvent.Post event) {
        ClientOptimizerRuntime.INSTANCE.atlasChanged();
    }

    @SubscribeEvent
    public void onModelBake(ModelBakeEvent event) {
        ClientOptimizerRuntime.INSTANCE.modelsChanged();
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (lastWorld != null) {
            lastWorld = null;
            ClientOptimizerRuntime.INSTANCE.worldChanged();
        }
    }
}

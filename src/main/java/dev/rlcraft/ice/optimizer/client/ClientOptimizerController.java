package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizerConfig;
import java.io.File;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Forge lifecycle adapter.  It does not move game-state work off the client thread. */
public final class ClientOptimizerController implements IResourceManagerReloadListener {
    public static final ClientOptimizerController INSTANCE = new ClientOptimizerController();
    private final Minecraft minecraft = Minecraft.getMinecraft();
    private Object lastWorld;
    private boolean reloadListenerRegistered;

    private ClientOptimizerController() {
    }

    public void preInit(File gameDirectory) {
        ClientOptimizerRuntime.INSTANCE.initialize(gameDirectory);
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
        Object world = minecraft.world;
        if (world != lastWorld) {
            lastWorld = world;
            ClientOptimizerRuntime.INSTANCE.worldChanged();
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        ClientOptimizerRuntime.INSTANCE.beginFrame();
        ClientOptimizerRuntime.INSTANCE.drainRenderQueue();
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
}

package dev.rlcraft.ice.client;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.core.ProfilerStatus;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.network.NetworkObserver;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.Locale;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

public final class ClientProfilerController {
    public static final ClientProfilerController INSTANCE = new ClientProfilerController();
    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final RenderQueueMonitor renderQueues = new RenderQueueMonitor();
    private final GpuTimer gpuTimer = new GpuTimer();
    private KeyBinding markerKey;
    private KeyBinding recordingKey;
    private KeyBinding dashboardKey;
    private long clientTickStarted;
    private long previousFrameEnd;
    private long lastWorldStats;
    private long knownCompletionSequence;
    private boolean clientThreadRegistered;
    private boolean worldActive;
    private Field loadedChunksField;
    private boolean chunkCountUnavailable;
    private int clientGaugeDimension = Integer.MIN_VALUE;
    private boolean disconnecting;

    private ClientProfilerController() {
    }

    public void registerKeys() {
        markerKey = new KeyBinding("key.ice.marker", Keyboard.KEY_F8, "key.categories.ice");
        recordingKey = new KeyBinding("key.ice.recording", Keyboard.KEY_F9, "key.categories.ice");
        dashboardKey = new KeyBinding("key.ice.dashboard", Keyboard.KEY_F10, "key.categories.ice");
        ClientRegistry.registerKeyBinding(markerKey);
        ClientRegistry.registerKeyBinding(recordingKey);
        ClientRegistry.registerKeyBinding(dashboardKey);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            clientTickStarted = System.nanoTime();
            if (!clientThreadRegistered) {
                ProfilerRuntime.INSTANCE.registerCurrentThread(ThreadRole.CLIENT_MAIN);
                clientThreadRegistered = true;
            }
            updateWorldState();
            return;
        }
        if (clientTickStarted != 0L) ProfilerRuntime.INSTANCE.recordClientTick(System.nanoTime() - clientTickStarted);
        updateWorldMetrics();
        checkNotification();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            gpuTimer.begin();
            return;
        }
        gpuTimer.end();
        ProfilerRuntime.INSTANCE.metrics().setGpuFrameMillis(gpuTimer.getLatestMillis());
        long now = System.nanoTime();
        if (previousFrameEnd != 0L) ProfilerRuntime.INSTANCE.recordClientFrame(now - previousFrameEnd);
        previousFrameEnd = now;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (markerKey != null && markerKey.isPressed()) {
            ProfilerRuntime.INSTANCE.mark("F8 用户标记");
            chat("§b[ICE] 已标记并捕获当前卡顿窗口。");
        }
        if (recordingKey != null && recordingKey.isPressed()) {
            ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
            if (status.isManualSession()) {
                ProfilerRuntime.INSTANCE.stopManual(true);
                chat("§b[ICE] 手动录制已停止，报告正在后台生成。");
            } else {
                ProfilerRuntime.INSTANCE.startManual("F9 手动录制");
                chat("§b[ICE] 已开始手动录制；再次按 F9 停止并导出。");
            }
        }
        if (dashboardKey != null && dashboardKey.isPressed()) minecraft.displayGuiScreen(new ProfilerDashboardScreen(minecraft.currentScreen));
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        if (!IceConfig.client.hudEnabled) return;
        ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
        if (!status.isRecording() && ProfilerRuntime.INSTANCE.getLastCompletedDiagnosis().isEmpty()) return;
        TimelinePoint point = status.getLatest();
        StringBuilder line = new StringBuilder("§b[ICE] ");
        line.append(status.isRecording() ? (status.isActiveCapture() ? "§cCAPTURE" : "§aREC") : "§7PASSIVE");
        if (point != null) line.append(String.format(Locale.ROOT, " §f帧P95 %.1fms  MSPT P95 %.1fms  GC %dms",
            point.getClientFrames().getP95Ms(), point.getServerTicks().getP95Ms(), point.getJvm().getGcPauseMillisDelta()));
        event.getLeft().add(line.toString());
        String diagnosis = ProfilerRuntime.INSTANCE.getLastCompletedDiagnosis();
        if (!diagnosis.isEmpty()) event.getLeft().add("§b[ICE] §e" + trim(diagnosis, 90));
    }

    private void updateWorldState() {
        boolean nowActive = minecraft.world != null;
        if (!nowActive) disconnecting = false;
        if (disconnecting && nowActive) return;
        if (nowActive && !worldActive) {
            worldActive = true;
            ProfilerRuntime.INSTANCE.activityStarted("客户端世界");
        } else if (!nowActive && worldActive) {
            worldActive = false;
            previousFrameEnd = 0L;
            if (clientGaugeDimension != Integer.MIN_VALUE) ProfilerRuntime.INSTANCE.metrics().removeWorld(clientGaugeDimension);
            clientGaugeDimension = Integer.MIN_VALUE;
            ProfilerRuntime.INSTANCE.activityStopped();
        }
        if (minecraft.getConnection() != null) NetworkObserver.attach(minecraft.getConnection().getNetworkManager());
    }

    private void updateWorldMetrics() {
        long now = System.currentTimeMillis();
        if (now - lastWorldStats < IceConfig.client.worldStatsIntervalSeconds * 1000L) return;
        lastWorldStats = now;
        if (minecraft.world != null && !minecraft.isSingleplayer()) {
            int dimension = minecraft.world.provider.getDimension();
            if (clientGaugeDimension != Integer.MIN_VALUE && clientGaugeDimension != dimension) ProfilerRuntime.INSTANCE.metrics().removeWorld(clientGaugeDimension);
            clientGaugeDimension = dimension;
            ProfilerRuntime.INSTANCE.metrics().updateWorld(dimension,
                readClientChunkCount(), minecraft.world.loadedEntityList.size(), minecraft.world.loadedTileEntityList.size());
        }
        if (IceConfig.client.renderQueueMetrics) {
            int[] values = renderQueues.read(minecraft);
            ProfilerRuntime.INSTANCE.metrics().setRenderQueues(values[0], values[1]);
        }
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (!worldActive) return;
        disconnecting = true;
        worldActive = false;
        previousFrameEnd = 0L;
        if (clientGaugeDimension != Integer.MIN_VALUE) ProfilerRuntime.INSTANCE.metrics().removeWorld(clientGaugeDimension);
        clientGaugeDimension = Integer.MIN_VALUE;
        ProfilerRuntime.INSTANCE.activityStopped();
    }

    @SubscribeEvent
    public void onClientChunkLoad(ChunkEvent.Load event) {
        if (IceConfig.client.chunkEventCounters && event.getWorld().isRemote && !minecraft.isSingleplayer()) {
            ProfilerRuntime.INSTANCE.metrics().chunkLoaded();
        }
    }

    @SubscribeEvent
    public void onClientChunkUnload(ChunkEvent.Unload event) {
        if (IceConfig.client.chunkEventCounters && event.getWorld().isRemote && !minecraft.isSingleplayer()) {
            ProfilerRuntime.INSTANCE.metrics().chunkUnloaded();
        }
    }

    private int readClientChunkCount() {
        if (chunkCountUnavailable || minecraft.world == null) return 0;
        try {
            if (loadedChunksField == null) {
                loadedChunksField = ReflectionHelper.findField(ChunkProviderClient.class, "loadedChunks", "field_73236_b");
                loadedChunksField.setAccessible(true);
            }
            Object value = loadedChunksField.get(minecraft.world.getChunkProvider());
            return value instanceof Map ? ((Map<?, ?>) value).size() : 0;
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            chunkCountUnavailable = true;
            return 0;
        }
    }

    private void checkNotification() {
        long sequence = ProfilerRuntime.INSTANCE.getCompletionSequence();
        if (sequence == knownCompletionSequence) return;
        knownCompletionSequence = sequence;
        if (!IceConfig.client.silentAutomaticRecording && IceConfig.client.hitchNotifications) {
            chat("§e[ICE] 卡顿捕获完成：" + trim(ProfilerRuntime.INSTANCE.getLastCompletedDiagnosis(), 120));
        }
    }

    private void chat(String text) {
        if (minecraft.player != null) minecraft.player.sendMessage(new TextComponentString(text));
    }

    private static String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…";
    }
}

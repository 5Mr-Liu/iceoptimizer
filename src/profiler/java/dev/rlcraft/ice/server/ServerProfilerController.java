package dev.rlcraft.ice.server;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.network.NetworkObserver;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ServerProfilerController {
    public static final ServerProfilerController INSTANCE = new ServerProfilerController();
    private MinecraftServer server;
    private long tickStartedNanos;
    private long ticks;
    private long lastWorldScanMillis;
    private boolean threadRegistered;

    private ServerProfilerController() {
    }

    public void onServerStarting(MinecraftServer server) {
        this.server = server;
        this.ticks = 0L;
        ProfilerRuntime.INSTANCE.activityStarted(server.isDedicatedServer() ? "专用服务器" : "集成服务器");
    }

    public void onServerStopped() {
        scanWorlds(true);
        server = null;
        threadRegistered = false;
        ProfilerRuntime.INSTANCE.activityStopped();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartedNanos = System.nanoTime();
            if (!threadRegistered) {
                ProfilerRuntime.INSTANCE.registerCurrentThread(ThreadRole.SERVER_MAIN);
                threadRegistered = true;
            }
            return;
        }
        if (tickStartedNanos != 0L) ProfilerRuntime.INSTANCE.recordServerTick(System.nanoTime() - tickStartedNanos);
        ticks++;
        scanWorlds(false);
        if ((ticks % 20L) == 0L && server != null && server.getPlayerList() != null) {
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                if (player.connection != null) NetworkObserver.attach(player.connection.netManager);
            }
        }
    }

    private void scanWorlds(boolean remove) {
        if (server == null || !IceConfig.server.worldGauges) return;
        long now = System.currentTimeMillis();
        if (!remove && now - lastWorldScanMillis < IceConfig.server.worldStatsIntervalSeconds * 1000L) return;
        lastWorldScanMillis = now;
        for (WorldServer world : server.worlds) {
            if (world == null) continue;
            int dimension = world.provider.getDimension();
            if (remove) ProfilerRuntime.INSTANCE.metrics().removeServerWorld(dimension);
            else ProfilerRuntime.INSTANCE.metrics().updateWorld(dimension, world.getChunkProvider().getLoadedChunkCount(),
                world.loadedEntityList.size(), world.loadedTileEntityList.size());
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!IceConfig.server.chunkEventCounters || event.getWorld().isRemote) return;
        Chunk chunk = event.getChunk();
        ProfilerRuntime.INSTANCE.metrics().serverChunkLoaded(
            event.getWorld().provider.getDimension(), chunk.x, chunk.z, chunk);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!IceConfig.server.chunkEventCounters || event.getWorld().isRemote) return;
        Chunk chunk = event.getChunk();
        ProfilerRuntime.INSTANCE.metrics().serverChunkUnloaded(
            event.getWorld().provider.getDimension(), chunk.x, chunk.z);
    }

    @SubscribeEvent
    public void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!IceConfig.server.chunkEventCounters || event.getWorld().isRemote) return;
        Chunk chunk = event.getChunk();
        ProfilerRuntime.INSTANCE.metrics().serverChunkDataLoaded(
            event.getWorld().provider.getDimension(), chunk.x, chunk.z, chunk);
    }

    @SubscribeEvent
    public void onChunkDataSave(ChunkDataEvent.Save event) {
        if (IceConfig.server.chunkEventCounters && !event.getWorld().isRemote) ProfilerRuntime.INSTANCE.metrics().chunkDataSaved();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!world.isRemote) {
            ProfilerRuntime.INSTANCE.metrics().removeServerWorld(world.provider.getDimension());
        }
    }
}

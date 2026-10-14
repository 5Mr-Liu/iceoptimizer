package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.chunk.Chunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.util.math.ChunkPos;

/** Spreads the scheduled server autosave's chunk snapshots over at most 100 saving ticks. */
public final class AutosaveBridge {
    private static final int MODULE = OptimizationModule.VANILLA_AUTOSAVE_SLICES.ordinal();
    static final int COMPLETION_TICKS = 100;
    static final int CHUNKS_PER_TICK = 24;
    private static final ThreadLocal<Object> AUTOMATIC = new ThreadLocal<Object>();
    private AutosaveBridge() { }
    public static boolean isLoaded(Long2ObjectMap<Chunk> chunks, Chunk chunk) {
        return chunks.get(ChunkPos.asLong(chunk.x, chunk.z)) == chunk;
    }

    /** Only the 900-tick call site uses this entry; explicit save calls keep their original path. */
    public static void automaticSave(MinecraftServer server, boolean silent) {
        Object previous = AUTOMATIC.get();
        AUTOMATIC.set(server);
        try { server.saveAllWorlds(silent); }
        finally { restore(previous); }
    }

    /** Consumed at method entry, before saveLevel / event / loader callbacks can explicitly save. */
    public static boolean takeAutomatic(Object owner) {
        if (AUTOMATIC.get() != owner) return false;
        AUTOMATIC.remove();
        return true;
    }

    public static void saveWorld(WorldServer world, boolean all, IProgressUpdate progress, boolean automatic)
            throws MinecraftException {
        Object previous = AUTOMATIC.get();
        if (automatic) AUTOMATIC.set(world); else AUTOMATIC.remove();
        try { world.saveAllChunks(all, progress); }
        finally { restore(previous); }
    }

    /** Only WorldServer's own saveChunks call receives the invocation's automatic-save intent. */
    public static boolean saveWorldChunks(ChunkProviderServer provider, boolean all, boolean automatic) {
        Object previous = AUTOMATIC.get();
        if (automatic) AUTOMATIC.set(provider); else AUTOMATIC.remove();
        try { return provider.saveChunks(all); }
        finally { restore(previous); }
    }

    private static void restore(Object previous) {
        if (previous == null) AUTOMATIC.remove(); else AUTOMATIC.set(previous);
    }

    public static boolean save(AutosaveAccess provider, boolean all) {
        boolean automatic = takeAutomatic(provider);
        if (all && automatic && OptimizerBridge.isEnabled(MODULE)) {
            // A new scheduled pass cannot discard work accepted by an earlier pass.
            finish(provider);
            provider.ice$autosaveCursor(new Cursor(new ArrayList<Chunk>(provider.ice$loadedChunks())));
            return true;
        }
        if (all) {
            // The original complete save visits the current live set itself, including newer changes.
            boolean complete = provider.ice$originalSaveChunks(true);
            provider.ice$autosaveCursor(null);
            return complete;
        }
        finish(provider);
        return provider.ice$originalSaveChunks(false);
    }

    public static void tick(AutosaveAccess provider) {
        Cursor cursor = (Cursor) provider.ice$autosaveCursor();
        if (cursor == null || !provider.ice$canSave()) return;
        if (!OptimizerBridge.isEnabled(MODULE)) { finish(provider); return; }
        int remaining = cursor.chunks.size() - cursor.next;
        int ticksLeft = COMPLETION_TICKS - cursor.ticks;
        int count = Math.max(CHUNKS_PER_TICK, (remaining + ticksLeft - 1) / ticksLeft);
        advance(provider, cursor, count);
        cursor.ticks++;
    }

    /** Flush / explicit incremental saves must finish every accepted snapshot before returning. */
    public static void finish(AutosaveAccess provider) {
        Cursor cursor = (Cursor) provider.ice$autosaveCursor();
        if (cursor != null) advance(provider, cursor, cursor.chunks.size() - cursor.next);
    }

    private static void advance(AutosaveAccess provider, Cursor cursor, int count) {
        int end = Math.min(cursor.chunks.size(), cursor.next + count);
        while (cursor.next < end) {
            Chunk chunk = cursor.chunks.get(cursor.next);
            // An unloaded/replaced chunk was fully saved by vanilla's unload path.
            if (provider.ice$isLoaded(chunk)) {
                provider.ice$saveChunkExtraData(chunk);
                if (chunk.needsSaving(true)) {
                    provider.ice$saveChunkData(chunk);
                    chunk.setModified(false);
                }
            }
            cursor.chunks.set(cursor.next++, null);
        }
        if (cursor.next == cursor.chunks.size()) provider.ice$autosaveCursor(null);
    }

    static final class Cursor {
        final ArrayList<Chunk> chunks;
        int next, ticks;
        Cursor(ArrayList<Chunk> chunks) { this.chunks = chunks; }
    }
}

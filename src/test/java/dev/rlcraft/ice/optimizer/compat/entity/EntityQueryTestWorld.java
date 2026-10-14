package dev.rlcraft.ice.optimizer.compat.entity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import sun.misc.Unsafe;

/** Lightweight, live-mutation fixture: no Minecraft server or graphics context is started. */
public final class EntityQueryTestWorld extends World {
    public Map<Long, TestChunk> chunks;
    public int reads;
    private EntityQueryTestWorld() { super(null, null, null, null, false); }

    public static EntityQueryTestWorld create() throws Exception {
        EntityQueryTestWorld world = allocate(EntityQueryTestWorld.class);
        world.chunks = new HashMap<Long, TestChunk>();
        return world;
    }

    @Override public Chunk getChunk(int x, int z) {
        long key = (long) x << 32 | z & 0xffffffffL;
        TestChunk value = chunks.get(key);
        if (value == null) {
            try { value = allocate(TestChunk.class); }
            catch (Exception error) { throw new AssertionError(error); }
            value.storages = new ExtendedBlockStorage[16];
            @SuppressWarnings("unchecked") ClassInheritanceMultiMap<Entity>[] lists = new ClassInheritanceMultiMap[16];
            value.entities = lists;
            for (int i = 0; i < lists.length; i++) lists[i] = new ClassInheritanceMultiMap<Entity>(Entity.class);
            chunks.put(key, value);
        }
        return value;
    }
    @Override public Chunk getChunk(BlockPos pos) { return getChunk(pos.getX() >> 4, pos.getZ() >> 4); }
    @Override public IBlockState getBlockState(BlockPos pos) {
        reads++;
        if (pos.getY() < 0 || pos.getY() > 255) return Blocks.AIR.getDefaultState();
        ExtendedBlockStorage section = getChunk(pos).getBlockStorageArray()[pos.getY() >> 4];
        return section == null ? Blocks.AIR.getDefaultState() : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }
    @Override public IChunkProvider getChunkProvider() {
        return new IChunkProvider() {
            @Override public Chunk getLoadedChunk(int x, int z) { return chunks.get((long) x << 32 | z & 0xffffffffL); }
            @Override public Chunk provideChunk(int x, int z) { return getChunk(x, z); }
            @Override public boolean tick() { return false; }
            @Override public String makeString() { return "fixture"; }
            @Override public boolean isChunkGeneratedAt(int x, int z) { return getLoadedChunk(x, z) != null; }
        };
    }
    @Override protected IChunkProvider createChunkProvider() { return null; }
    @Override protected boolean isChunkLoaded(int x, int z, boolean empty) { return getChunkProvider().getLoadedChunk(x, z) != null; }

    public static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    public static final class TestChunk extends Chunk {
        ExtendedBlockStorage[] storages;
        ClassInheritanceMultiMap<Entity>[] entities;
        TestChunk() { super(null, 0, 0); }
        @Override public ExtendedBlockStorage[] getBlockStorageArray() { return storages; }
        @Override public ClassInheritanceMultiMap<Entity>[] getEntityLists() { return entities; }
    }
}

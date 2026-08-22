package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * A mutation-versioned, read-only scheduled-tick index scoped to one synchronous
 * ChunkProviderServer save or unload pass. It never removes entries or changes save order.
 */
public final class SaveTickIndexBridge {
    private static final String MODULE = "vanilla-save-tick-index";
    private static final int MAX_ASSIGNMENTS = 1 << 20;
    private static final ThreadLocal<ScopeStack> SCOPES = new ThreadLocal<ScopeStack>() {
        @Override protected ScopeStack initialValue() { return new ScopeStack(); }
    };
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;
    private static volatile boolean missingAccessorReported;

    private SaveTickIndexBridge() {
    }

    /** Called at the exact entry of ChunkProviderServer.saveChunks(boolean) or tick(). */
    public static long begin(Object provider, Object world, boolean all) {
        try {
            ScopeStack stack = SCOPES.get();
            boolean masksOuterScope = stack.hasScope();
            boolean enabled = OptimizerBridge.isEnabled(MODULE);
            boolean active = enabled && world instanceof PendingTickAccessor;
            if (enabled && !active && !missingAccessorReported) {
                missingAccessorReported = true;
                OptimizerBridge.incompatible(MODULE,
                    "WorldServer 计划刻访问器未安装；同步保存保持原始逐区块扫描");
            }
            if (!active && !masksOuterScope) return 0L;
            return stack.push(provider, world, active);
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return 0L;
        }
    }

    /** Called from a catch-all finally path around the original save method. */
    public static void end(long token) {
        if (token == 0L) return;
        try {
            if (!SCOPES.get().pop(token)) {
                OptimizerBridge.incompatible(MODULE,
                    "同步保存作用域发生非预期重入；计划刻索引已停用");
            }
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    /** Replacement for WorldServer.getPendingBlockUpdates(Chunk, false). */
    public static List<NextTickListEntry> pendingBlockUpdates(PendingTickAccessor world,
                                                               Chunk chunk, boolean remove) {
        if (world == null || chunk == null || remove || !OptimizerBridge.isEnabled(MODULE)) {
            return world == null ? null : world.ice$originalPendingBlockUpdates(chunk, remove);
        }
        Scope scope = SCOPES.get().current(world);
        if (scope == null || !scope.active) {
            return world.ice$originalPendingBlockUpdates(chunk, false);
        }
        if (scope.deferFirstQuery()) {
            return world.ice$originalPendingBlockUpdates(chunk, false);
        }

        List<NextTickListEntry> result;
        try {
            result = scope.pendingFor(world, chunk.x, chunk.z);
        } catch (Throwable error) {
            scope.disable();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return world.ice$originalPendingBlockUpdates(chunk, false);
        }
        activate();
        return result;
    }

    static List<NextTickListEntry> pendingForChunk(PendingTickAccessor world, int chunkX, int chunkZ) {
        Scope scope = SCOPES.get().current(world);
        if (scope == null || !scope.active) return null;
        if (scope.deferFirstQuery()) return null;
        return scope.pendingFor(world, chunkX, chunkZ);
    }

    private static void activate() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE,
            "计划刻按原 TreeSet/List 顺序为同步增量、全量与卸载保存建立临时只读索引");
    }

    private static long chunkKey(int x, int z) {
        return (long) x & 0xffffffffL | ((long) z & 0xffffffffL) << 32;
    }

    private static final class ScopeStack {
        private Scope[] scopes = new Scope[2];
        private int depth;
        private long nextToken = 1L;

        private boolean hasScope() {
            return depth > 0;
        }

        private long push(Object provider, Object world, boolean active) {
            if (depth == scopes.length) {
                Scope[] grown = new Scope[scopes.length << 1];
                System.arraycopy(scopes, 0, grown, 0, scopes.length);
                scopes = grown;
            }
            Scope scope = scopes[depth];
            if (scope == null) scopes[depth] = scope = new Scope();
            long token = nextToken++;
            if (token == 0L) token = nextToken++;
            scope.reset(token, provider, world, active);
            depth++;
            return token;
        }

        private boolean pop(long token) {
            if (depth == 0 || scopes[depth - 1].token != token) {
                while (depth > 0) scopes[--depth].clear();
                return false;
            }
            scopes[--depth].clear();
            return true;
        }

        private Scope current(Object world) {
            if (depth == 0) return null;
            Scope scope = scopes[depth - 1];
            return scope.world == world ? scope : null;
        }
    }

    private static final class Scope {
        private long token;
        private Object provider;
        private Object world;
        private boolean active;
        private int queryCount;
        private long indexedVersion = Long.MIN_VALUE;
        private Long2ObjectHashMap<ArrayList<NextTickListEntry>> index;
        private int assignments;

        private void reset(long value, Object saveProvider, Object saveWorld, boolean enabled) {
            token = value;
            provider = saveProvider;
            world = saveWorld;
            active = enabled;
            queryCount = 0;
            indexedVersion = Long.MIN_VALUE;
            assignments = 0;
            if (index != null) index.clear();
        }

        private boolean deferFirstQuery() {
            return queryCount++ == 0;
        }

        private List<NextTickListEntry> pendingFor(PendingTickAccessor accessor, int chunkX, int chunkZ) {
            long version = accessor.ice$pendingTickVersion();
            if (index == null || indexedVersion != version) rebuild(accessor, version);
            ArrayList<NextTickListEntry> values = index.get(chunkKey(chunkX, chunkZ));
            List<NextTickListEntry> result = values == null
                ? null : new ArrayList<NextTickListEntry>(values);
            if (accessor.ice$pendingTickVersion() != indexedVersion) {
                throw new ConcurrentModificationException("计划刻在索引查询期间发生变化");
            }
            return result;
        }

        private void rebuild(PendingTickAccessor accessor, long expectedVersion) {
            Long2ObjectHashMap<ArrayList<NextTickListEntry>> rebuilt =
                new Long2ObjectHashMap<ArrayList<NextTickListEntry>>(64, 0.65F, true);
            assignments = 0;
            append(rebuilt, accessor.ice$pendingTickTree());
            append(rebuilt, accessor.ice$pendingTicksThisTick());
            long observedVersion = accessor.ice$pendingTickVersion();
            if (observedVersion != expectedVersion) {
                throw new ConcurrentModificationException("计划刻在索引构建期间发生变化");
            }
            index = rebuilt;
            indexedVersion = observedVersion;
        }

        private void append(Long2ObjectHashMap<ArrayList<NextTickListEntry>> target,
                            Iterable<NextTickListEntry> entries) {
            if (entries == null) throw new IllegalStateException("计划刻集合为空引用");
            for (NextTickListEntry entry : entries) {
                if (entry == null || entry.position == null) {
                    throw new IllegalStateException("计划刻集合包含空条目");
                }
                BlockPos position = entry.position;
                int baseX = position.getX() >> 4;
                int baseZ = position.getZ() >> 4;
                add(target, baseX, baseZ, entry);
                boolean overlapsEast = (position.getX() & 15) >= 14;
                boolean overlapsSouth = (position.getZ() & 15) >= 14;
                if (overlapsEast) add(target, baseX + 1, baseZ, entry);
                if (overlapsSouth) add(target, baseX, baseZ + 1, entry);
                if (overlapsEast && overlapsSouth) add(target, baseX + 1, baseZ + 1, entry);
            }
        }

        private void add(Long2ObjectHashMap<ArrayList<NextTickListEntry>> target,
                         int chunkX, int chunkZ, NextTickListEntry entry) {
            if (++assignments > MAX_ASSIGNMENTS) {
                throw new IllegalStateException("计划刻索引超过安全上限 " + MAX_ASSIGNMENTS);
            }
            long key = chunkKey(chunkX, chunkZ);
            ArrayList<NextTickListEntry> values = target.get(key);
            if (values == null) {
                values = new ArrayList<NextTickListEntry>();
                target.put(key, values);
            }
            values.add(entry);
        }

        private void disable() {
            active = false;
            indexedVersion = Long.MIN_VALUE;
            if (index != null) index.clear();
        }

        private void clear() {
            token = 0L;
            provider = null;
            world = null;
            disable();
        }
    }
}

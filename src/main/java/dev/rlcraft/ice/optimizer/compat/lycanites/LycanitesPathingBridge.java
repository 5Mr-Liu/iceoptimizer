package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.agrona.collections.Long2ObjectHashMap;

/** Search-local primitive caches for the exact Lycanites 2.0.8.9 node processor. */
public final class LycanitesPathingBridge {
    private static final String MODULE = "lycanites-path-node-cache";
    private static final int MAX_ENTRIES = 65536;
    private static final ThreadLocal<SearchStack> SEARCHES = new ThreadLocal<SearchStack>() {
        @Override protected SearchStack initialValue() { return new SearchStack(); }
    };
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private LycanitesPathingBridge() {
    }

    public static void begin(LycanitesRawNodeAccessor processor, IBlockAccess source) {
        if (!OptimizerBridge.isEnabled(MODULE) || processor == null || source == null) return;
        try {
            SEARCHES.get().push(processor, source);
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    public static void end(LycanitesRawNodeAccessor processor) {
        SearchStack stack = SEARCHES.get();
        stack.pop(processor);
    }

    public static PathNodeType rawNodeType(LycanitesRawNodeAccessor processor, IBlockAccess source,
                                           int x, int y, int z) {
        SearchContext context = SEARCHES.get().current(processor, source);
        if (context == null || !OptimizerBridge.isEnabled(MODULE)) {
            return processor.ice$rawNodeType(source, x, y, z);
        }
        long key = packed(x, y, z);
        PathNodeType cached;
        try {
            cached = context.rawTypes.get(key);
        } catch (Throwable error) {
            context.disable();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return processor.ice$rawNodeType(source, x, y, z);
        }
        if (cached != null) {
            activate();
            return cached;
        }
        PathNodeType computed = processor.ice$rawNodeType(source, x, y, z);
        try {
            if (computed != null && context.rawTypes.size() < MAX_ENTRIES) context.rawTypes.put(key, computed);
        } catch (Throwable error) {
            context.disable();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
        return computed;
    }

    public static IBlockState blockState(IBlockAccess source, BlockPos position) {
        SearchContext context = SEARCHES.get().current(source);
        if (context == null || !OptimizerBridge.isEnabled(MODULE)) return source.getBlockState(position);
        long key = packed(position.getX(), position.getY(), position.getZ());
        IBlockState cached;
        try {
            cached = context.blockStates.get(key);
        } catch (Throwable error) {
            context.disable();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return source.getBlockState(position);
        }
        if (cached != null) {
            activate();
            return cached;
        }
        IBlockState computed = source.getBlockState(position);
        try {
            if (computed != null && context.blockStates.size() < MAX_ENTRIES) context.blockStates.put(key, computed);
        } catch (Throwable error) {
            context.disable();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
        return computed;
    }

    static long packed(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) z & 0x3FFFFFFL) << 12
            | (long) y & 0xFFFL;
    }

    private static void activate() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE, "Lycanites 方块状态与节点类型仅在单次寻路调用内复用");
    }

    private static final class SearchStack {
        private SearchContext[] contexts = new SearchContext[2];
        private int depth;

        private void push(LycanitesRawNodeAccessor processor, IBlockAccess source) {
            if (depth == contexts.length) {
                SearchContext[] grown = new SearchContext[contexts.length << 1];
                System.arraycopy(contexts, 0, grown, 0, contexts.length);
                contexts = grown;
            }
            SearchContext context = contexts[depth];
            if (context == null) contexts[depth] = context = new SearchContext();
            context.reset(processor, source);
            depth++;
        }

        private void pop(LycanitesRawNodeAccessor processor) {
            if (depth == 0) return;
            SearchContext current = contexts[depth - 1];
            if (current.processor != processor) {
                while (depth > 0) contexts[--depth].clear();
                OptimizerBridge.incompatible(MODULE, "Lycanites 寻路生命周期发生非预期重入，已回退原逻辑");
                return;
            }
            current.clear();
            depth--;
        }

        private SearchContext current(LycanitesRawNodeAccessor processor, IBlockAccess source) {
            if (depth == 0) return null;
            SearchContext current = contexts[depth - 1];
            return current.active && current.processor == processor && current.source == source ? current : null;
        }

        private SearchContext current(IBlockAccess source) {
            if (depth == 0) return null;
            SearchContext current = contexts[depth - 1];
            return current.active && current.source == source ? current : null;
        }
    }

    private static final class SearchContext {
        private final Long2ObjectHashMap<PathNodeType> rawTypes =
            new Long2ObjectHashMap<PathNodeType>(512, 0.65F, true);
        private final Long2ObjectHashMap<IBlockState> blockStates =
            new Long2ObjectHashMap<IBlockState>(512, 0.65F, true);
        private LycanitesRawNodeAccessor processor;
        private IBlockAccess source;
        private boolean active;

        private void reset(LycanitesRawNodeAccessor value, IBlockAccess access) {
            rawTypes.clear();
            blockStates.clear();
            processor = value;
            source = access;
            active = true;
        }

        private void clear() {
            rawTypes.clear();
            blockStates.clear();
            processor = null;
            source = null;
            active = false;
        }

        private void disable() {
            active = false;
            try { rawTypes.clear(); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
            try { blockStates.clear(); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
        }
    }
}

package dev.rlcraft.ice.optimizer.compat.iceandfire;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Primitive caches scoped to one synchronous Ice and Fire PathFinder call.
 * World reads are never moved off the authoritative thread or retained across
 * searches, so a new path always observes the current world state.
 */
public final class IceAndFirePathingBridge {
    private static final int MODULE = OptimizationModule.ICEANDFIRE_PATH_NODE_CACHE.ordinal();
    private static final int MAX_ENTRIES = 65536;
    private static final ThreadLocal<SearchStack> SEARCHES = new ThreadLocal<SearchStack>();
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private IceAndFirePathingBridge() {
    }

    public static void begin(IceAndFireRawNodeAccessor processor, IBlockAccess source) {
        if (processor == null || source == null || !OptimizerBridge.isEnabled(MODULE)) return;
        try {
            SearchStack stack = SEARCHES.get();
            if (stack == null) {
                stack = new SearchStack();
                SEARCHES.set(stack);
            }
            stack.push(processor, source);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    public static void end(IceAndFireRawNodeAccessor processor) {
        SearchStack stack = SEARCHES.get();
        if (stack == null) return;
        try {
            stack.pop(processor);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            SEARCHES.remove();
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    public static PathNodeType rawNodeType(IceAndFireRawNodeAccessor processor,
                                           IBlockAccess source, int x, int y, int z) {
        if (!OptimizerBridge.isEnabled(MODULE)) {
            return processor.ice$rawNodeType(source, x, y, z);
        }
        SearchStack stack = SEARCHES.get();
        SearchContext context = stack == null ? null : stack.current(processor, source);
        if (context == null) return processor.ice$rawNodeType(source, x, y, z);

        long key = packed(x, y, z);
        PathNodeType cached;
        try {
            cached = context.rawTypes.get(key);
        } catch (Throwable error) {
            return rawCacheFailure(context, processor, source, x, y, z, error);
        }
        if (cached != null) {
            activate();
            return cached;
        }

        PathNodeType computed = processor.ice$rawNodeType(source, x, y, z);
        try {
            if (computed != null && context.rawTypes.size() < MAX_ENTRIES) {
                context.rawTypes.put(key, computed);
            }
        } catch (Throwable error) {
            disableAfterFailure(context, error);
        }
        return computed;
    }

    public static IBlockState blockState(IBlockAccess source, BlockPos position) {
        if (position == null || !OptimizerBridge.isEnabled(MODULE)) {
            return source.getBlockState(position);
        }
        SearchStack stack = SEARCHES.get();
        SearchContext context = stack == null ? null : stack.current(source);
        if (context == null) return source.getBlockState(position);

        long key = packed(position.getX(), position.getY(), position.getZ());
        IBlockState cached;
        try {
            cached = context.blockStates.get(key);
        } catch (Throwable error) {
            disableAfterFailure(context, error);
            return source.getBlockState(position);
        }
        if (cached != null) {
            activate();
            return cached;
        }

        IBlockState computed = source.getBlockState(position);
        try {
            if (computed != null && context.blockStates.size() < MAX_ENTRIES) {
                context.blockStates.put(key, computed);
            }
        } catch (Throwable error) {
            disableAfterFailure(context, error);
        }
        return computed;
    }

    static long packed(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) z & 0x3FFFFFFL) << 12
            | (long) y & 0xFFFL;
    }

    private static PathNodeType rawCacheFailure(SearchContext context,
                                                IceAndFireRawNodeAccessor processor,
                                                IBlockAccess source, int x, int y, int z,
                                                Throwable error) {
        disableAfterFailure(context, error);
        return processor.ice$rawNodeType(source, x, y, z);
    }

    private static void disableAfterFailure(SearchContext context, Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        context.disable();
        recoveryPending = true;
        OptimizerBridge.failure(MODULE, error);
    }

    private static void activate() {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE,
            "Ice and Fire 方块状态与节点类型仅在单次同步寻路内复用");
    }

    private static final class SearchStack {
        private SearchContext[] contexts = new SearchContext[2];
        private int depth;

        private void push(IceAndFireRawNodeAccessor processor, IBlockAccess source) {
            if (depth > 0 && contexts[depth - 1].processor == processor) {
                // A previous exceptional search may not have reached done().
                // Reusing the top slot bounds retained state; a genuinely
                // reentrant same-processor search safely falls back after its end.
                contexts[depth - 1].reset(processor, source);
                return;
            }
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

        private void pop(IceAndFireRawNodeAccessor processor) {
            if (depth == 0) return;
            SearchContext current = contexts[depth - 1];
            if (current.processor != processor) {
                while (depth > 0) contexts[--depth].clear();
                OptimizerBridge.incompatible(MODULE,
                    "Ice and Fire 寻路生命周期不是后进先出，已回退原逻辑");
                return;
            }
            current.clear();
            depth--;
        }

        private SearchContext current(IceAndFireRawNodeAccessor processor,
                                      IBlockAccess source) {
            if (depth == 0) return null;
            SearchContext current = contexts[depth - 1];
            return current.active && current.processor == processor && current.source == source
                ? current : null;
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
        private IceAndFireRawNodeAccessor processor;
        private IBlockAccess source;
        private boolean active;

        private void reset(IceAndFireRawNodeAccessor value, IBlockAccess access) {
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
            try {
                rawTypes.clear();
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
            try {
                blockStates.clear();
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
    }
}

package dev.rlcraft.ice.optimizer.compat.lycanites;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidBlock;
import org.agrona.collections.Object2IntHashMap;

/** Call-scoped helpers for the exact Lycanites BlockSpawnLocation scan. */
public final class LycanitesSpawnScanBridge {
    private static final String MODULE = "lycanites-spawn-scan";
    private static final String TARGET =
        "com.lycanitesmobs.core.spawner.location.BlockSpawnLocation";
    private static final ThreadLocal<ScopeStack> SCOPES = new ThreadLocal<ScopeStack>() {
        @Override protected ScopeStack initialValue() { return new ScopeStack(); }
    };
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private LycanitesSpawnScanBridge() {
    }

    public static long begin(LycanitesSpawnScanAccessor owner) {
        try {
            ScopeStack stack = SCOPES.get();
            boolean active = owner != null
                && TARGET.equals(owner.getClass().getName())
                && OptimizerBridge.isEnabled(MODULE);
            if (!active && !stack.hasScope()) return 0L;
            return stack.push(owner, active);
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return 0L;
        }
    }

    public static void end(long token) {
        if (token == 0L) return;
        try {
            if (!SCOPES.get().pop(token)) {
                OptimizerBridge.incompatible(MODULE,
                    "Lycanites 刷怪扫描作用域发生非预期重入；已恢复原始读取");
            }
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
        }
    }

    /** Exact first World.getBlockState call in getSpawnPositions. */
    public static IBlockState scanState(World world, BlockPos position) {
        IBlockState state = world.getBlockState(position);
        Scope scope = SCOPES.get().current();
        if (scope != null && scope.active) scope.remember(world, position, state);
        return state;
    }

    /** Exact World.getBlockState call in isValidBlock. */
    public static IBlockState validationState(World world, BlockPos position) {
        Scope scope = SCOPES.get().current();
        if (scope != null && scope.canReuse(world, position)) {
            IBlockState state = scope.consumeCandidate();
            Block block = state.getBlock();
            if (!(block instanceof IFluidBlock) && !(block instanceof BlockLiquid)) {
                activate("Lycanites 普通方块验证复用同一扫描位置的只读方块状态");
                return state;
            }
        }
        if (scope != null) scope.discardCandidate();
        return world.getBlockState(position);
    }

    /** Replaces only the local HashMap construction; inactive calls receive a normal HashMap. */
    public static Map<Object, Integer> newBlockCounter(Object owner) {
        try {
            Scope scope = SCOPES.get().current();
            if (scope == null || !scope.active || scope.owner != owner) {
                return new HashMap<Object, Integer>();
            }
            return new LazyBlockCountMap();
        } catch (Throwable error) {
            recoveryPending = true;
            OptimizerBridge.failure(MODULE, error);
            return new HashMap<Object, Integer>();
        }
    }

    private static boolean vanillaWorldImplementation(World world) {
        if (world == null) return false;
        String name = world.getClass().getName();
        return "net.minecraft.world.WorldServer".equals(name)
            || "net.minecraft.world.WorldServerMulti".equals(name)
            || "net.minecraft.client.multiplayer.WorldClient".equals(name);
    }

    private static long positionKey(BlockPos position) {
        return ((long) position.getX() & 0x3FFFFFFL) << 38
            | ((long) position.getZ() & 0x3FFFFFFL) << 12
            | (long) position.getY() & 0xFFFL;
    }

    private static void activate(String detail) {
        if (recoveryPending) {
            recoveryPending = false;
            OptimizerBridge.success(MODULE);
        }
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE, detail);
    }

    private static final class ScopeStack {
        private Scope[] scopes = new Scope[2];
        private int depth;
        private long nextToken = 1L;

        private boolean hasScope() {
            return depth > 0;
        }

        private long push(LycanitesSpawnScanAccessor owner, boolean active) {
            if (depth == scopes.length) {
                Scope[] grown = new Scope[scopes.length << 1];
                System.arraycopy(scopes, 0, grown, 0, scopes.length);
                scopes = grown;
            }
            Scope scope = scopes[depth];
            if (scope == null) scopes[depth] = scope = new Scope();
            long token = nextToken++;
            if (token == 0L) token = nextToken++;
            scope.reset(token, owner, active);
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

        private Scope current() {
            return depth == 0 ? null : scopes[depth - 1];
        }
    }

    private static final class Scope {
        private long token;
        private LycanitesSpawnScanAccessor owner;
        private List<ResourceLocation> blockIds;
        private boolean active;
        private World candidateWorld;
        private long candidatePosition;
        private IBlockState candidateState;
        private boolean candidateAvailable;

        private void reset(long value, LycanitesSpawnScanAccessor source, boolean enabled) {
            token = value;
            owner = source;
            active = enabled;
            blockIds = enabled && source != null ? source.ice$spawnBlockIds() : null;
            discardCandidate();
        }

        private void remember(World world, BlockPos position, IBlockState state) {
            candidateWorld = world;
            candidatePosition = positionKey(position);
            candidateState = state;
            candidateAvailable = state != null;
        }

        private boolean canReuse(World world, BlockPos position) {
            if (!candidateAvailable || candidateWorld != world
                || candidatePosition != positionKey(position) || !vanillaWorldImplementation(world)) {
                return false;
            }
            List<ResourceLocation> currentIds = owner.ice$spawnBlockIds();
            return owner.ice$spawnSurface() && owner.ice$spawnUnderground()
                && currentIds == blockIds && currentIds != null
                && currentIds.getClass() == ArrayList.class;
        }

        private IBlockState consumeCandidate() {
            IBlockState result = candidateState;
            discardCandidate();
            return result;
        }

        private void discardCandidate() {
            candidateWorld = null;
            candidatePosition = 0L;
            candidateState = null;
            candidateAvailable = false;
        }

        private void clear() {
            token = 0L;
            owner = null;
            blockIds = null;
            active = false;
            discardCandidate();
        }
    }

    /** A primitive counter that allocates its backing table only on the first valid block. */
    private static final class LazyBlockCountMap extends AbstractMap<Object, Integer> {
        private Object2IntHashMap<Object> values;
        private Object cachedKey;
        private int cachedValue;
        private boolean cached;

        @Override public boolean containsKey(Object key) {
            int value = values == null ? 0 : values.getValue(key);
            cachedKey = key;
            cachedValue = value;
            cached = true;
            return value != 0;
        }

        @Override public Integer get(Object key) {
            int value;
            if (cached && sameKey(cachedKey, key)) value = cachedValue;
            else value = values == null ? 0 : values.getValue(key);
            return value == 0 ? null : Integer.valueOf(value);
        }

        @Override public Integer put(Object key, Integer value) {
            if (values == null) {
                values = new Object2IntHashMap<Object>(16, 0.65F, 0, true);
                activate("Lycanites 刷怪扫描按需使用无装箱方块计数表");
            }
            cached = false;
            int previous = values.put(key, value.intValue());
            return previous == 0 ? null : Integer.valueOf(previous);
        }

        @Override public int size() {
            return values == null ? 0 : values.size();
        }

        @Override public java.util.Collection<Integer> values() {
            return values == null ? Collections.<Integer>emptyList() : values.values();
        }

        @Override public Set<Entry<Object, Integer>> entrySet() {
            return values == null ? Collections.<Entry<Object, Integer>>emptySet() : values.entrySet();
        }

        private static boolean sameKey(Object left, Object right) {
            return left == right || left != null && left.equals(right);
        }
    }
}

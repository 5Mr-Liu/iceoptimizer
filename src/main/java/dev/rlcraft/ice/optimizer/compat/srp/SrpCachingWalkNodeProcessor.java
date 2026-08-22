package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.world.IBlockAccess;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Reuses path-node classifications only while one vanilla PathFinder search is
 * active. Nothing survives postProcess(), so mutable world results never cross
 * a path request or tick boundary.
 */
public final class SrpCachingWalkNodeProcessor extends WalkNodeProcessor {
    private static final String MODULE = "srp-path-node-cache";
    private static final int MAX_ENTRIES = 65536;
    private static volatile boolean activated;
    private static volatile boolean recoveryPending;

    private final Long2ObjectHashMap<PathNodeType> rawTypes =
        new Long2ObjectHashMap<PathNodeType>(512, 0.65F, true);
    private final Long2ObjectHashMap<PathNodeType> checkedTypes =
        new Long2ObjectHashMap<PathNodeType>(512, 0.65F, true);
    private IBlockAccess activeAccess;
    private boolean cacheActive;

    @Override
    public void init(IBlockAccess source, EntityLiving entity) {
        super.init(source, entity);
        activeAccess = source;
        try {
            rawTypes.clear();
            checkedTypes.clear();
            cacheActive = OptimizerBridge.isEnabled(MODULE);
        } catch (Throwable error) {
            cacheFailure(error);
        }
    }

    @Override
    public void postProcess() {
        try {
            super.postProcess();
        } finally {
            safeClear();
            activeAccess = null;
            cacheActive = false;
        }
    }

    @Override
    public PathNodeType getPathNodeType(IBlockAccess source, int x, int y, int z) {
        if (!cacheActive || source != activeAccess) return super.getPathNodeType(source, x, y, z);
        long key = packed(x, y, z);
        PathNodeType cached;
        try {
            cached = checkedTypes.get(key);
        } catch (Throwable error) {
            cacheFailure(error);
            return super.getPathNodeType(source, x, y, z);
        }
        if (cached != null) {
            activate();
            return cached;
        }
        PathNodeType computed = super.getPathNodeType(source, x, y, z);
        try {
            if (computed != null && checkedTypes.size() < MAX_ENTRIES) checkedTypes.put(key, computed);
        } catch (Throwable error) {
            cacheFailure(error);
        }
        return computed;
    }

    @Override
    protected PathNodeType getPathNodeTypeRaw(IBlockAccess source, int x, int y, int z) {
        if (!cacheActive || source != activeAccess) return super.getPathNodeTypeRaw(source, x, y, z);
        long key = packed(x, y, z);
        PathNodeType cached;
        try {
            cached = rawTypes.get(key);
        } catch (Throwable error) {
            cacheFailure(error);
            return super.getPathNodeTypeRaw(source, x, y, z);
        }
        if (cached != null) {
            activate();
            return cached;
        }
        PathNodeType computed = super.getPathNodeTypeRaw(source, x, y, z);
        try {
            if (computed != null && rawTypes.size() < MAX_ENTRIES) rawTypes.put(key, computed);
        } catch (Throwable error) {
            cacheFailure(error);
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
        OptimizerBridge.activate(MODULE, "SRP 原版寻路节点分类已限定在单次搜索内复用");
    }

    private void cacheFailure(Throwable error) {
        cacheActive = false;
        recoveryPending = true;
        safeClear();
        OptimizerBridge.failure(MODULE, error);
    }

    private void safeClear() {
        try { rawTypes.clear(); }
        catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
        try { checkedTypes.clear(); }
        catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
    }
}

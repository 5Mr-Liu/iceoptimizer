package dev.rlcraft.ice.optimizer.compat.srp;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.compat.entity.PathSearchWorkspace;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.world.IBlockAccess;

/**
 * Reuses path-node classifications only while one vanilla PathFinder search is
 * active. Nothing survives postProcess(), so mutable world results never cross
 * a path request or tick boundary.
 */
public final class SrpCachingWalkNodeProcessor extends WalkNodeProcessor {
    private static final int MODULE = OptimizationModule.SRP_PATH_NODE_CACHE.ordinal();
    private static volatile boolean activated;
    private PathSearchWorkspace workspace;

    @Override
    public void init(IBlockAccess source, EntityLiving entity) {
        super.init(source, entity);
        if (workspace != null) workspace.release();
        workspace = OptimizerBridge.isEnabled(MODULE) ? PathSearchWorkspace.acquire(this, source) : null;
    }

    @Override
    public void postProcess() {
        try {
            super.postProcess();
        } finally {
            if (workspace != null) workspace.release();
            workspace = null;
        }
    }

    @Override
    public PathNodeType getPathNodeType(IBlockAccess source, int x, int y, int z) {
        PathSearchWorkspace search = workspace;
        if (search == null || !search.matches(this, source)) return super.getPathNodeType(source, x, y, z);
        long key = packed(x, y, z);
        PathNodeType cached = search.checkedTypes.get(key);
        if (cached != null) {
            activate();
            return cached;
        }
        PathNodeType computed = super.getPathNodeType(source, x, y, z);
        search.checkedTypes.put(key, computed);
        return computed;
    }

    @Override
    protected PathNodeType getPathNodeTypeRaw(IBlockAccess source, int x, int y, int z) {
        PathSearchWorkspace search = workspace;
        if (search == null || !search.matches(this, source)) return super.getPathNodeTypeRaw(source, x, y, z);
        long key = packed(x, y, z);
        PathNodeType cached = search.rawTypes.get(key);
        if (cached != null) {
            activate();
            return cached;
        }
        PathNodeType computed = super.getPathNodeTypeRaw(source, x, y, z);
        search.rawTypes.put(key, computed);
        return computed;
    }

    static long packed(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) z & 0x3FFFFFFL) << 12
            | (long) y & 0xFFFL;
    }

    private static void activate() {
        if (activated) return;
        activated = true;
        OptimizerBridge.activate(MODULE, "SRP 原版寻路节点分类已限定在单次搜索内复用");
    }

}

package dev.rlcraft.ice.optimizer.compat.entity;

import java.util.ArrayList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.world.IBlockAccess;

/** One reusable workspace per nested synchronous search on the current thread. */
public final class PathSearchWorkspace {
    private static final ThreadLocal<Pool> POOL = new ThreadLocal<Pool>() {
        @Override protected Pool initialValue() { return new Pool(); }
    };
    public final LongObjectScratch<PathNodeType> rawTypes = new LongObjectScratch<PathNodeType>();
    public final LongObjectScratch<PathNodeType> checkedTypes = new LongObjectScratch<PathNodeType>();
    public final LongObjectScratch<IBlockState> blockStates = new LongObjectScratch<IBlockState>();
    private Object owner;
    private IBlockAccess source;
    private boolean active;

    private PathSearchWorkspace() { }

    public static PathSearchWorkspace acquire(Object owner, IBlockAccess source) {
        Pool pool = POOL.get();
        PathSearchWorkspace workspace = null;
        for (int i = 0; i < pool.values.size(); i++) {
            if (!pool.values.get(i).active) { workspace = pool.values.get(i); break; }
        }
        if (workspace == null) {
            workspace = new PathSearchWorkspace();
            pool.values.add(workspace);
        }
        workspace.owner = owner;
        workspace.source = source;
        workspace.active = true;
        return workspace;
    }

    public boolean matches(Object owner, IBlockAccess source) {
        return active && this.owner == owner && this.source == source;
    }

    public void release() {
        rawTypes.clear();
        checkedTypes.clear();
        blockStates.clear();
        owner = null;
        source = null;
        active = false;
    }

    private static final class Pool {
        private final ArrayList<PathSearchWorkspace> values = new ArrayList<PathSearchWorkspace>(2);
    }
}

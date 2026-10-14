package dev.rlcraft.ice.optimizer.compat.entity;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.entity.Entity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** RLTweaker's exact query/selection phases with reusable invocation-local candidate storage. */
public final class NearestEntityQueryBridge {
    private static final int MODULE = OptimizationModule.ENTITY_NEAREST_QUERY.ordinal();
    private static final ThreadLocal<Candidates> CANDIDATES = new ThreadLocal<Candidates>() {
        @Override protected Candidates initialValue() { return new Candidates(); }
    };

    private NearestEntityQueryBridge() { }

    public static boolean isEnabled() { return OptimizerBridge.isEnabled(MODULE); }

    public static <T extends Entity> T nearest(World world, Class<? extends T> type,
                                              AxisAlignedBB box, T origin, double radius) {
        Candidates stack = CANDIDATES.get();
        CandidateBuffer candidates = stack.push();
        try {
            collect(world, type, box, radius, candidates);
            T best = null;
            double distance = Double.MAX_VALUE;
            for (int i = 0; i < candidates.size; i++) {
                @SuppressWarnings("unchecked") T entity = (T) candidates.entities[i];
                if (entity == origin) continue;
                double next = origin.getDistanceSq(entity);
                // RLTweaker chooses the last equal-distance result, unlike SRP's first minimum.
                if (next <= distance) { best = entity; distance = next; }
            }
            return best;
        } finally {
            candidates.clear();
            stack.depth--;
        }
    }

    private static <T extends Entity> void collect(World world, Class<? extends T> type,
                                                   AxisAlignedBB box, double radius,
                                                   CandidateBuffer candidates) {
        int minX = MathHelper.floor(box.minX - radius) >> 4;
        int maxX = MathHelper.floor(box.maxX + radius) >> 4;
        int minZ = MathHelper.floor(box.minZ - radius) >> 4;
        int maxZ = MathHelper.floor(box.maxZ + radius) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                if (chunk == null) continue;
                ClassInheritanceMultiMap<Entity>[] sections = chunk.getEntityLists();
                if (box.maxY < 0 || box.minY >= (sections.length << 4)) continue;
                int minY = Math.max(MathHelper.floor(box.minY - radius) >> 4, 0);
                int maxY = Math.min(MathHelper.floor(box.maxY + radius) >> 4, sections.length - 1);
                for (int y = minY; y <= maxY; y++) {
                    for (T entity : sections[y].getByClass(type)) {
                        if (!entity.getEntityBoundingBox().intersects(box)
                            || !EntitySelectors.NOT_SPECTATING.apply(entity)) continue;
                        candidates.add(entity);
                    }
                }
            }
        }
    }

    private static final class Candidates {
        final ArrayList<CandidateBuffer> buffers = new ArrayList<CandidateBuffer>(2);
        int depth;
        CandidateBuffer push() {
            if (depth == buffers.size()) buffers.add(new CandidateBuffer());
            return buffers.get(depth++);
        }
    }

    private static final class CandidateBuffer {
        Entity[] entities = new Entity[64];
        int size;
        void add(Entity value) {
            if (size == entities.length) entities = Arrays.copyOf(entities, size << 1);
            entities[size++] = value;
        }
        void clear() {
            Arrays.fill(entities, 0, size, null);
            size = 0;
        }
    }
}

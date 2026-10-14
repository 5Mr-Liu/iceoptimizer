package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderChunkIndexAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import net.minecraft.util.math.BlockPos;

/**
 * Allocation-free replacement for RenderGlobal's Queue BFS. It commits only
 * after the complete seed queue and stable RenderChunk index ABI have been
 * certified. A declined call leaves the original Queue untouched.
 */
public final class PrimitiveTerrainVisibilityBridge {
    private static final int MAX_SECTIONS = 1 << 20;
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private PrimitiveTerrainVisibilityBridge() {
    }

    /** Called once immediately before the original setupTerrain Queue loop. */
    public static boolean tryTraverse(Object owner, Queue queue, Object origin,
                                      Object camera, int frameIndex,
                                      boolean renderChunksMany, boolean fog,
                                      int renderDistance,
                                      boolean optifineTraversal) {
        if (!OptimizerRegistry.isOperational(OptimizationModule.MODERN_VISIBILITY_GRID) || owner == null
            || queue == null || origin == null || camera == null || queue.isEmpty()) {
            return false;
        }
        // OptiFine already reuses render-info records and precomputes facing
        // subsets. The extra primitive bridge costs more in full-pack paired
        // runs and produces a different visible set. Preserve its certified
        // original traversal before touching the queue or chunk frame stamps.
        if (optifineTraversal) {
            STATES.get().optifineDelegated = true;
            return false;
        }
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
            OptimizationModule.MODERN_VISIBILITY_GRID);
        if (!(owner instanceof TerrainVisibilityAccessor)) {
            breaker.forceIncompatible("RenderGlobal primitive visibility ABI missing");
            return false;
        }
        State state = STATES.get();
        state.optifineDelegated = false;
        TerrainVisibilityAccessor access = (TerrainVisibilityAccessor) owner;
        try {
            Workspace workspace = state.workspace(owner, access);
            if (workspace.optifineTraversal != optifineTraversal) {
                throw new PreflightFailure("setupTerrain traversal mode mismatch");
            }
            workspace.prepareOrigin(origin);
            int seeds = workspace.certifySeeds(queue);

            // No operation above this point mutates the original queue or chunks.
            queue.clear();
            state.activeOwner = owner;
            state.activeWorkspace = workspace;
            workspace.traverse(access, camera, frameIndex, renderChunksMany,
                fog, renderDistance, seeds);
            if (!state.activated) {
                breaker.activate("Primitive terrain visibility traversal active");
                state.activated = true;
            }
            return true;
        } catch (PreflightFailure incompatible) {
            boolean committed = state.activeWorkspace != null;
            state.releaseTraversal();
            breaker.forceIncompatible(incompatible.getMessage());
            // Once the queue is consumed, replaying the original loop would
            // expose partially committed traversal output a second time.
            if (committed) throwUnchecked(incompatible);
            return false;
        } catch (Throwable error) {
            state.releaseTraversal();
            FatalErrors.rethrowIfFatal(error);
            breaker.recordFailure(error);
            throwUnchecked(error);
            return false;
        }
    }

    /** Keeps the injected loop-exit ABI and releases only this traversal's references. */
    public static void afterTraversal(Object owner, boolean modernHandled) {
        State state = STATES.get();
        if (state.activeWorkspace == null || state.activeOwner != owner) return;
        try {
            if (!modernHandled) {
                OptimizerRegistry.breaker(OptimizationModule.MODERN_VISIBILITY_GRID)
                    .forceIncompatible("setupTerrain traversal ownership mismatch");
            }
        } finally {
            state.releaseTraversal();
        }
    }

    static void resetForTest() {
        STATES.remove();
    }

    public static String traversalPolicy() {
        State state = STATES.get();
        return state.optifineDelegated ? "optifine-original" : state.activated ? "primitive-vanilla" : "not-observed";
    }

    private static final class State {
        private Object owner;
        private Object[] chunks;
        private Workspace workspace;
        private Object activeOwner;
        private Workspace activeWorkspace;
        private boolean activated;
        private boolean optifineDelegated;

        private Workspace workspace(Object requestedOwner,
                                    TerrainVisibilityAccessor access) {
            Object[] requestedChunks = access.ice$renderChunks();
            if (requestedChunks == null || requestedChunks.length == 0
                || requestedChunks.length > MAX_SECTIONS) {
                throw new PreflightFailure("RenderGlobal chunk array bounds");
            }
            if (owner != requestedOwner || chunks != requestedChunks || workspace == null) {
                Workspace replacement = new Workspace(access, requestedChunks);
                releaseTraversal();
                owner = requestedOwner;
                chunks = requestedChunks;
                workspace = replacement;
            }
            return workspace;
        }

        private void releaseTraversal() {
            Workspace current = activeWorkspace != null ? activeWorkspace : workspace;
            activeOwner = null;
            activeWorkspace = null;
            if (current != null) current.releaseQueueReferences();
        }
    }

    private static final class Workspace {
        private final Object[] chunkByIndex;
        private final Object[] directions;
        private final Object[] opposites;
        private final Object[] infoQueue;
        private final Object[] chunkQueue;
        private final byte[] incomingQueue;
        private final byte[] pathQueue;
        private final int[] counterQueue;
        private final int[] visits;
        private final boolean optifineTraversal;
        private int visitGeneration = 1;
        private int retainedQueueEntries;

        private Workspace(TerrainVisibilityAccessor access, Object[] chunks) {
            int count = chunks.length;
            chunkByIndex = new Object[count];
            infoQueue = new Object[count];
            chunkQueue = new Object[count];
            incomingQueue = new byte[count];
            pathQueue = new byte[count];
            counterQueue = new int[count];
            visits = new int[count];
            optifineTraversal = access.ice$isOptifineTraversal();
            for (Object chunk : chunks) {
                TerrainRenderChunkIndexAccessor chunkAccess = chunkAccess(chunk);
                int index = chunkAccess.ice$renderChunkIndex();
                if (index < 0 || index >= count || chunkByIndex[index] != null) {
                    throw new PreflightFailure("RenderChunk stable index is not bijective");
                }
                if (chunkAccess.ice$bounds() == null) {
                    throw new PreflightFailure("RenderChunk bounds missing");
                }
                chunkByIndex[index] = chunk;
            }
            directions = access.ice$directions();
            if (directions == null || directions.length != 6) {
                throw new PreflightFailure("EnumFacing direction ABI");
            }
            opposites = new Object[6];
            for (int i = 0; i < directions.length; i++) {
                Object direction = directions[i];
                if (direction == null || directionIndex(direction) != i) {
                    throw new PreflightFailure("EnumFacing order ABI");
                }
                Object opposite = access.ice$oppositeDirection(direction);
                int expected = i ^ 1;
                if (opposite != directions[expected]) {
                    throw new PreflightFailure("EnumFacing opposite ABI");
                }
                opposites[i] = opposite;
            }
        }

        private int certifySeeds(Queue queue) {
            if (queue.getClass() != ArrayDeque.class) {
                throw new PreflightFailure("setupTerrain Queue is not canonical ArrayDeque");
            }
            int count = queue.size();
            if (count <= 0 || count > infoQueue.length) {
                throw new PreflightFailure("setupTerrain seed Queue bounds");
            }
            Object[] copied = queue.toArray(infoQueue);
            if (copied != infoQueue) {
                throw new PreflightFailure("setupTerrain seed Queue allocated unexpectedly");
            }
            int stamp = nextVisitGeneration();
            for (int i = 0; i < count; i++) {
                TerrainRenderInfoAccessor info = infoAccess(infoQueue[i]);
                if (!info.ice$isCanonicalRenderInfo()) {
                    throw new PreflightFailure("non-canonical setupTerrain render info");
                }
                Object chunk = info.ice$renderChunk();
                TerrainRenderChunkIndexAccessor chunkAccess = certifiedChunk(chunk);
                int index = chunkAccess.ice$renderChunkIndex();
                if (visits[index] == stamp) {
                    throw new PreflightFailure("duplicate setupTerrain seed chunk");
                }
                visits[index] = stamp;
                Object incoming = info.ice$incomingDirection();
                if (incoming != null && directionIndex(incoming) < 0) {
                    throw new PreflightFailure("setupTerrain seed direction ABI");
                }
                chunkQueue[i] = chunk;
                incomingQueue[i] = (byte) directionIndex(incoming);
                pathQueue[i] = info.ice$pathDirections();
                counterQueue[i] = info.ice$counter();
            }
            retainedQueueEntries = count;
            return count;
        }

        private void traverse(TerrainVisibilityAccessor access, Object camera,
                              int frameIndex, boolean renderChunksMany,
                              boolean fog, int renderDistance, int seeds) {
            List output = access.ice$renderInfos();
            if (output == null) throw new PreflightFailure("RenderGlobal renderInfos missing");
            int head = 0;
            int tail = seeds;
            try {
                while (head < tail) {
                    Object infoObject = infoQueue[head];
                    Object chunk = chunkQueue[head];
                    int incomingIndex = incomingQueue[head];
                    int path = pathQueue[head] & 0xff;
                    int counter = counterQueue[head];
                    head++;
                    access.ice$appendRenderInfo(infoObject, chunk);
                    TerrainRenderChunkIndexAccessor current = (TerrainRenderChunkIndexAccessor) chunk;
                    for (int directionIndex = 0; directionIndex < 6; directionIndex++) {
                        Object direction = directions[directionIndex];
                        if (optifineTraversal && renderChunksMany) {
                            int oppositeIndex = directionIndex ^ 1;
                            if ((path & 1 << oppositeIndex) != 0) continue;
                            if (incomingIndex >= 0) {
                                int fromIndex = incomingIndex ^ 1;
                                long mask = current.ice$visibilityMask();
                                boolean trustedMask = mask >= 0L;
                                boolean visible = trustedMask
                                    ? (mask & (1L << (fromIndex * 6 + directionIndex))) != 0L
                                    : current.ice$isVisible(opposites[incomingIndex], direction);
                                if (!visible) continue;
                            }
                        }
                        // Vanilla deliberately computes the offset before both
                        // path tests. OptiFine first selects facings and checks
                        // compiled visibility, then invokes its five-argument
                        // offset helper. Keep the two observable orders apart.
                        Object neighbor = access.ice$getRenderChunkOffset(
                            traversalOrigin, chunk, direction, fog,
                            renderDistance);
                        if (!optifineTraversal && renderChunksMany) {
                            int oppositeIndex = directionIndex ^ 1;
                            if ((path & 1 << oppositeIndex) != 0) continue;
                            if (incomingIndex >= 0) {
                                int fromIndex = incomingIndex ^ 1;
                                long mask = current.ice$visibilityMask();
                                boolean trustedMask = mask >= 0L;
                                boolean visible = trustedMask
                                    ? (mask & (1L << (fromIndex * 6 + directionIndex))) != 0L
                                    : current.ice$isVisible(opposites[incomingIndex], direction);
                                if (!visible) continue;
                            }
                        }
                        if (neighbor == null) continue;
                        TerrainRenderChunkIndexAccessor next = (TerrainRenderChunkIndexAccessor) neighbor;
                        // This call must remain before the frustum check, even for repeats.
                        if (!next.ice$setFrameIndex(frameIndex)) continue;
                        Object bounds = optifineTraversal ? null : next.ice$bounds();
                        if (!access.ice$isInFrustum(neighbor, camera, bounds,
                            frameIndex)) continue;
                        Object nextInfoObject = access.ice$newRenderInfo(neighbor, direction,
                            counter + 1);
                        TerrainRenderInfoAccessor nextInfo = (TerrainRenderInfoAccessor) nextInfoObject;
                        nextInfo.ice$setDirection((byte) path, direction);
                        infoQueue[tail] = nextInfoObject;
                        chunkQueue[tail] = neighbor;
                        incomingQueue[tail] = (byte) directionIndex;
                        pathQueue[tail] = nextInfo.ice$pathDirections();
                        counterQueue[tail] = nextInfo.ice$counter();
                        tail++;
                    }
                }
            } finally {
                retainedQueueEntries = Math.max(retainedQueueEntries, tail);
            }
        }

        private Object traversalOrigin;

        private void prepareOrigin(Object origin) {
            if (!(origin instanceof BlockPos)) {
                throw new PreflightFailure("setupTerrain origin is not BlockPos");
            }
            traversalOrigin = origin;
        }

        private TerrainRenderChunkIndexAccessor certifiedChunk(Object chunk) {
            TerrainRenderChunkIndexAccessor access = chunkAccess(chunk);
            int index = access.ice$renderChunkIndex();
            if (index < 0 || index >= chunkByIndex.length || chunkByIndex[index] != chunk) {
                throw new PreflightFailure("neighbor is outside certified ViewFrustum");
            }
            return access;
        }

        private int directionIndex(Object direction) {
            if (direction == null || directions == null) return -1;
            for (int i = 0; i < directions.length; i++) {
                if (directions[i] == direction) return i;
            }
            return -1;
        }

        private int nextVisitGeneration() {
            if (visitGeneration == Integer.MAX_VALUE) {
                Arrays.fill(visits, 0);
                visitGeneration = 1;
            }
            return visitGeneration++;
        }

        private void releaseQueueReferences() {
            for (int i = 0; i < retainedQueueEntries; i++) {
                infoQueue[i] = null;
                chunkQueue[i] = null;
            }
            retainedQueueEntries = 0;
            traversalOrigin = null;
        }

    }

    private static final class PreflightFailure extends RuntimeException {
        private PreflightFailure(String message) { super(message); }
    }

    private static TerrainRenderChunkIndexAccessor chunkAccess(Object chunk) {
        if (!(chunk instanceof TerrainRenderChunkIndexAccessor)) {
            throw new PreflightFailure("RenderChunk primitive visibility ABI missing");
        }
        return (TerrainRenderChunkIndexAccessor) chunk;
    }

    private static TerrainRenderInfoAccessor infoAccess(Object info) {
        if (!(info instanceof TerrainRenderInfoAccessor)) {
            throw new PreflightFailure("RenderInfo primitive visibility ABI missing");
        }
        return (TerrainRenderInfoAccessor) info;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable error) throws T {
        throw (T) error;
    }
}

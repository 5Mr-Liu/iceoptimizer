package dev.rlcraft.ice.optimizer.render.visibility;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderChunkIndexAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendLifecycleState;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.MeasurementArm;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.SceneFingerprint;
import dev.rlcraft.ice.optimizer.runtime.MonotonicTokenCounter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.math.BlockPos;

/**
 * Allocation-free replacement for RenderGlobal's Queue BFS. It commits only
 * after the complete seed queue and stable RenderChunk index ABI have been
 * certified. A declined call leaves the original Queue untouched.
 */
public final class PrimitiveTerrainVisibilityBridge {
    private static final int MAX_SECTIONS = 1 << 20;
    private static final int BASELINE_SLOTS = 256;
    private static final long MASK_36 = (1L << 36) - 1L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final MeasurementArm[] ABBA = {
        MeasurementArm.LEGACY, MeasurementArm.MODERN,
        MeasurementArm.MODERN, MeasurementArm.LEGACY
    };
    private static final AtomicLong GENERATIONS = new AtomicLong(1L);
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
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
            OptimizationModule.MODERN_VISIBILITY_GRID);
        if (breaker == null || !breaker.isOperational() || owner == null
            || queue == null || origin == null || camera == null || queue.isEmpty()) {
            return false;
        }
        if (!(owner instanceof TerrainVisibilityAccessor)) {
            breaker.forceIncompatible("RenderGlobal primitive visibility ABI missing");
            return false;
        }
        State state = STATES.get();
        TerrainVisibilityAccessor access = (TerrainVisibilityAccessor) owner;
        try {
            Workspace workspace = state.workspace(owner, access);
            if (workspace.optifineTraversal != optifineTraversal) {
                throw new PreflightFailure("setupTerrain traversal mode mismatch");
            }
            if (state.controller == null) state.beginGeneration(breaker);
            BackendLifecycleState lifecycle = state.controller.lifecycleState();
            if (lifecycle == BackendLifecycleState.LEGACY
                || lifecycle == BackendLifecycleState.QUARANTINED) return false;

            int seeds = workspace.certifySeeds(queue);
            long sceneKey = workspace.sceneKey(origin, renderChunksMany, fog,
                renderDistance, seeds);
            Decision decision = state.decide(lifecycle, sceneKey);
            Sample sample = new Sample(owner, workspace, sceneKey, decision,
                System.nanoTime());
            state.sample = sample;
            if (decision.arm == MeasurementArm.LEGACY) return false;

            // No operation above this point mutates the original queue or chunks.
            queue.clear();
            sample.committed = true;
            long started = System.nanoTime();
            workspace.traverse(access, camera, frameIndex, renderChunksMany,
                fog, renderDistance, seeds);
            sample.traversalNanos = Math.max(1L, System.nanoTime() - started);
            return true;
        } catch (PreflightFailure incompatible) {
            state.sample = null;
            if (state.workspace != null) state.workspace.releaseQueueReferences();
            breaker.forceIncompatible(incompatible.getMessage());
            return false;
        } catch (Throwable error) {
            state.sample = null;
            if (state.workspace != null) state.workspace.releaseQueueReferences();
            FatalErrors.rethrowIfFatal(error);
            if (state.controller != null) {
                try { state.controller.runtimeFailure(error); }
                catch (Throwable controllerFailure) {
                    FatalErrors.rethrowIfFatal(controllerFailure);
                    if (controllerFailure != error) {
                        error.addSuppressed(controllerFailure);
                    }
                    breaker.recordFailure(error);
                }
            } else {
                breaker.recordFailure(error);
            }
            throwUnchecked(error);
            return false;
        }
    }

    /** Called at the exact original Queue-loop exit for both selected arms. */
    public static void afterTraversal(Object owner, boolean modernHandled) {
        State state = STATES.get();
        Sample sample = state.sample;
        if (sample == null || sample.owner != owner) return;
        state.sample = null;
        try {
            if (sample.committed != modernHandled) {
                throw new PreflightFailure("setupTerrain traversal ownership mismatch");
            }
            long elapsed = modernHandled && sample.traversalNanos > 0L
                ? sample.traversalNanos
                : Math.max(1L, System.nanoTime() - sample.startedNanos);
            Signature signature = sample.workspace.signature(
                (TerrainVisibilityAccessor) owner);
            state.complete(sample, signature, elapsed);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(
                OptimizationModule.MODERN_VISIBILITY_GRID);
            if (state.controller != null) try {
                state.controller.runtimeFailure(error);
            } catch (Throwable controllerFailure) {
                FatalErrors.rethrowIfFatal(controllerFailure);
                if (controllerFailure != error) {
                    error.addSuppressed(controllerFailure);
                }
                if (breaker != null) breaker.recordFailure(error);
            }
            else if (breaker != null) breaker.recordFailure(error);
        } finally {
            sample.workspace.releaseQueueReferences();
        }
    }

    static void resetForTest() {
        STATES.remove();
    }

    private static final class State {
        private Object owner;
        private Object[] chunks;
        private Workspace workspace;
        private AdaptiveBackendController controller;
        private Sample sample;
        private long regressionFrames;
        private int regressionProbeIndex = -1;
        private final long[] regressionLegacy = new long[8];
        private final long[] regressionModern = new long[8];
        private int regressionLegacyCount;
        private int regressionModernCount;
        private SceneFingerprint regressionScene;
        private final BaselineTable baselines = new BaselineTable();

        private Workspace workspace(Object requestedOwner,
                                    TerrainVisibilityAccessor access) {
            Object[] requestedChunks = access.ice$renderChunks();
            if (requestedChunks == null || requestedChunks.length == 0
                || requestedChunks.length > MAX_SECTIONS) {
                throw new PreflightFailure("RenderGlobal chunk array bounds");
            }
            if (owner != requestedOwner || chunks != requestedChunks || workspace == null) {
                Workspace replacement = new Workspace(access, requestedChunks);
                owner = requestedOwner;
                chunks = requestedChunks;
                workspace = replacement;
                controller = null;
                sample = null;
                baselines.clear();
                resetRegression();
            }
            return workspace;
        }

        private void beginGeneration(ModuleCircuitBreaker breaker) {
            controller = new AdaptiveBackendController(breaker,
                EnumSet.noneOf(ModernCapability.class));
            long generation = MonotonicTokenCounter.nextOrZero(GENERATIONS,
                "primitive visibility generation");
            if (generation == 0L) {
                throw new PreflightFailure(
                    "primitive visibility generation exhausted");
            }
            controller.begin(generation);
            controller.capabilityResult(CapabilityReport.builder().build());
        }

        private Decision decide(BackendLifecycleState lifecycle, long sceneKey) {
            switch (lifecycle) {
                case WARMUP:
                    return Decision.LEGACY;
                case OUTPUT_VALIDATE:
                    // A candidate output is meaningful only against an exact
                    // Legacy result captured for the same workload key.  An
                    // unseen scene spends this frame establishing the baseline
                    // and does not advance validation.
                    return baselines.get(sceneKey) == null
                        ? Decision.BASELINE : Decision.MODERN;
                case PAIRED_MEASURE:
                    MeasurementArm paired = controller.expectedMeasurementArm();
                    return paired == MeasurementArm.MODERN
                        && baselines.get(sceneKey) == null
                            ? Decision.BASELINE : new Decision(paired, false);
                case MODERN:
                    return baselines.get(sceneKey) == null
                        ? Decision.BASELINE : Decision.MODERN;
                case REGRESSION_MONITOR:
                    if (baselines.get(sceneKey) == null) return Decision.BASELINE;
                    regressionFrames++;
                    if (regressionProbeIndex < 0 && (regressionFrames & 127L) == 0L) {
                        regressionProbeIndex = 0;
                    }
                    if (regressionProbeIndex >= 0) {
                        return new Decision(ABBA[regressionProbeIndex], true);
                    }
                    return Decision.MODERN;
                default:
                    return Decision.LEGACY;
            }
        }

        private void complete(Sample completed, Signature signature, long nanos) {
            BackendLifecycleState lifecycle = controller.lifecycleState();
            Signature baseline = baselines.get(completed.sceneKey);
            boolean stable = signature.valid;
            if (completed.arm == MeasurementArm.LEGACY && stable) {
                baselines.put(completed.sceneKey, signature);
                baseline = signature;
            } else if (completed.arm == MeasurementArm.MODERN) {
                stable = signature.matches(baseline);
            }
            switch (lifecycle) {
                case WARMUP:
                    controller.warmupFrame(stable);
                    break;
                case OUTPUT_VALIDATE:
                    if (completed.baselineOnly) {
                        // Capturing a missing Legacy baseline is intentionally
                        // not a successful validation frame.
                        break;
                    }
                    if (completed.arm == MeasurementArm.MODERN) {
                        controller.validationResult(stable,
                            stable ? null : "primitive traversal differs from scene Legacy baseline");
                    } else {
                        controller.correctnessFailure("validation arm was not modern");
                    }
                    break;
                case PAIRED_MEASURE:
                    if (completed.baselineOnly) break;
                    if (completed.arm == MeasurementArm.MODERN && !stable) {
                        controller.correctnessFailure(
                            "paired primitive traversal differs from Legacy baseline");
                        break;
                    }
                    controller.recordMeasurement(scene(completed, signature), completed.arm,
                        nanos, stable);
                    break;
                case MODERN:
                    controller.activateAtSafeBoundary();
                    break;
                case REGRESSION_MONITOR:
                    if (completed.baselineOnly) break;
                    if (completed.arm == MeasurementArm.MODERN && !stable) {
                        controller.correctnessFailure(
                            "primitive traversal regression differs from Legacy baseline");
                        break;
                    }
                    if (completed.regressionProbe) {
                        recordRegressionProbe(scene(completed, signature), completed.arm,
                            nanos, stable);
                    }
                    break;
                default:
                    break;
            }
            if (controller.lifecycleState() == BackendLifecycleState.MODERN) {
                controller.activateAtSafeBoundary();
            }
        }

        private SceneFingerprint scene(Sample completed, Signature signature) {
            long mixed = completed.sceneKey ^ Long.rotateLeft(signature.hash, 23);
            return new SceneFingerprint((int) (mixed >>> 32), (int) mixed,
                (int) (signature.hash >>> 32), (int) signature.hash,
                (int) (completed.sceneKey >>> 32), (int) completed.sceneKey,
                signature.count, 0, 0, completed.workspace.capacity(), 0, 0,
                0, completed.workspace.generation, 0L);
        }

        private void recordRegressionProbe(SceneFingerprint scene, MeasurementArm arm,
                                           long nanos, boolean stable) {
            if (!stable || scene == null || nanos <= 0L) {
                resetRegressionWindow();
                advanceRegressionProbe();
                return;
            }
            if (regressionScene == null) regressionScene = scene;
            if (!regressionScene.equals(scene)) {
                resetRegressionWindow();
                regressionScene = scene;
            }
            if (arm == MeasurementArm.LEGACY) {
                if (regressionLegacyCount < regressionLegacy.length) {
                    regressionLegacy[regressionLegacyCount++] = nanos;
                }
            } else if (regressionModernCount < regressionModern.length) {
                regressionModern[regressionModernCount++] = nanos;
            }
            advanceRegressionProbe();
            if (regressionLegacyCount == regressionLegacy.length
                && regressionModernCount == regressionModern.length) {
                controller.recordRegressionWindow(
                    percentile(regressionLegacy, regressionLegacyCount, 0.50D),
                    percentile(regressionModern, regressionModernCount, 0.50D),
                    percentile(regressionLegacy, regressionLegacyCount, 0.95D),
                    percentile(regressionModern, regressionModernCount, 0.95D), true);
                resetRegressionWindow();
            }
        }

        private void advanceRegressionProbe() {
            if (++regressionProbeIndex >= ABBA.length) regressionProbeIndex = -1;
        }

        private void resetRegression() {
            regressionFrames = 0L;
            regressionProbeIndex = -1;
            resetRegressionWindow();
        }

        private void resetRegressionWindow() {
            regressionLegacyCount = 0;
            regressionModernCount = 0;
            regressionScene = null;
            Arrays.fill(regressionLegacy, 0L);
            Arrays.fill(regressionModern, 0L);
        }
    }

    private static final class Workspace {
        private final Object[] sourceChunks;
        private final Object[] chunkByIndex;
        private final Object[] directions;
        private final Object[] opposites;
        private final Object[] infoQueue;
        private final Object[] chunkQueue;
        private final Object[] incomingQueue;
        private final byte[] pathQueue;
        private final int[] counterQueue;
        private final int[] visits;
        private final int[] signatureVisits;
        private final boolean optifineTraversal;
        private int visitGeneration = 1;
        private int signatureGeneration = 1;
        private int retainedQueueEntries;
        private final long generation;
        private long signatureHash;
        private int signatureCount;

        private Workspace(TerrainVisibilityAccessor access, Object[] chunks) {
            sourceChunks = chunks;
            int count = chunks.length;
            chunkByIndex = new Object[count];
            infoQueue = new Object[count];
            chunkQueue = new Object[count];
            incomingQueue = new Object[count];
            pathQueue = new byte[count];
            counterQueue = new int[count];
            visits = new int[count];
            signatureVisits = new int[count];
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
            generation = Math.max(1L, GENERATIONS.get());
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
                incomingQueue[i] = incoming;
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
            int stamp = nextVisitGeneration();
            for (int i = 0; i < seeds; i++) {
                int index = certifiedChunk(chunkQueue[i]).ice$renderChunkIndex();
                visits[index] = stamp;
            }
            int head = 0;
            int tail = seeds;
            try {
                while (head < tail) {
                    Object infoObject = infoQueue[head];
                    Object chunk = chunkQueue[head];
                    Object incoming = incomingQueue[head];
                    int path = pathQueue[head] & 0xff;
                    int counter = counterQueue[head];
                    head++;
                    access.ice$appendRenderInfo(infoObject, chunk);
                    TerrainRenderChunkIndexAccessor current = certifiedChunk(chunk);
                    for (int directionIndex = 0; directionIndex < 6; directionIndex++) {
                        Object direction = directions[directionIndex];
                        if (optifineTraversal && renderChunksMany) {
                            int oppositeIndex = directionIndex ^ 1;
                            if ((path & 1 << oppositeIndex) != 0) continue;
                            if (incoming != null) {
                                int incomingIndex = directionIndex(incoming);
                                if (incomingIndex < 0) {
                                    throw new PreflightFailure("incoming direction changed");
                                }
                                int fromIndex = incomingIndex ^ 1;
                                long mask = current.ice$visibilityMask();
                                boolean trustedMask = mask >= 0L
                                    && (mask & ~MASK_36) == 0L;
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
                            currentOrigin(access), chunk, direction, fog,
                            renderDistance);
                        if (!optifineTraversal && renderChunksMany) {
                            int oppositeIndex = directionIndex ^ 1;
                            if ((path & 1 << oppositeIndex) != 0) continue;
                            if (incoming != null) {
                                int incomingIndex = directionIndex(incoming);
                                if (incomingIndex < 0) {
                                    throw new PreflightFailure("incoming direction changed");
                                }
                                int fromIndex = incomingIndex ^ 1;
                                long mask = current.ice$visibilityMask();
                                boolean trustedMask = mask >= 0L
                                    && (mask & ~MASK_36) == 0L;
                                boolean visible = trustedMask
                                    ? (mask & (1L << (fromIndex * 6 + directionIndex))) != 0L
                                    : current.ice$isVisible(opposites[incomingIndex], direction);
                                if (!visible) continue;
                            }
                        }
                        if (neighbor == null) continue;
                        TerrainRenderChunkIndexAccessor next = certifiedChunk(neighbor);
                        // This call must remain before the frustum check, even for repeats.
                        if (!next.ice$setFrameIndex(frameIndex)) continue;
                        int stableIndex = next.ice$renderChunkIndex();
                        if (visits[stableIndex] == stamp) {
                            throw new IllegalStateException("RenderChunk frame/index disagreement");
                        }
                        visits[stableIndex] = stamp;
                        Object bounds = optifineTraversal ? null : next.ice$bounds();
                        if (!access.ice$isInFrustum(neighbor, camera, bounds,
                            frameIndex)) continue;
                        if (tail >= infoQueue.length) {
                            throw new IllegalStateException("primitive traversal capacity exceeded");
                        }
                        Object nextInfoObject = access.ice$newRenderInfo(neighbor, direction,
                            counter + 1);
                        TerrainRenderInfoAccessor nextInfo = infoAccess(nextInfoObject);
                        if (!nextInfo.ice$isCanonicalRenderInfo()) {
                            throw new IllegalStateException("new render info ABI mismatch");
                        }
                        nextInfo.ice$setDirection((byte) path, direction);
                        infoQueue[tail] = nextInfoObject;
                        chunkQueue[tail] = neighbor;
                        incomingQueue[tail] = direction;
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

        private long sceneKey(Object origin, boolean renderChunksMany,
                              boolean fog, int renderDistance, int seeds) {
            if (origin.getClass() != BlockPos.class) {
                throw new PreflightFailure("setupTerrain origin is not canonical BlockPos");
            }
            traversalOrigin = origin;
            BlockPos position = (BlockPos) origin;
            long hash = mix(FNV_OFFSET, position.getX());
            hash = mix(hash, position.getY());
            hash = mix(hash, position.getZ());
            hash = mix(hash, renderChunksMany ? 1 : 0);
            hash = mix(hash, fog ? 1 : 0);
            hash = mix(hash, renderDistance);
            hash = mix(hash, optifineTraversal ? 1 : 0);
            hash = mix(hash, seeds);
            for (int i = 0; i < seeds; i++) {
                hash = mix(hash, certifiedChunk(chunkQueue[i]).ice$renderChunkIndex());
                hash = mix(hash, pathQueue[i] & 0xff);
                hash = mix(hash, counterQueue[i]);
            }
            return hash;
        }

        private Object currentOrigin(TerrainVisibilityAccessor ignored) {
            Object origin = traversalOrigin;
            if (origin == null) throw new IllegalStateException("traversal origin missing");
            return origin;
        }

        private Signature signature(TerrainVisibilityAccessor access) {
            List infos = access.ice$renderInfos();
            if (infos == null || infos.size() > chunkByIndex.length) {
                return Signature.INVALID;
            }
            signatureHash = FNV_OFFSET;
            signatureCount = 0;
            if (!appendSignatureList(infos, 1)) return Signature.INVALID;
            if (optifineTraversal) {
                List entities = access.ice$renderInfosEntities();
                List tiles = access.ice$renderInfosTileEntities();
                if (entities == null || tiles == null
                    || entities.size() > chunkByIndex.length
                    || tiles.size() > chunkByIndex.length
                    || !appendSignatureList(entities, 2)
                    || !appendSignatureList(tiles, 3)) return Signature.INVALID;
            }
            return new Signature(true, signatureCount, signatureHash);
        }

        private boolean appendSignatureList(List infos, int tag) {
            int stamp = nextSignatureGeneration();
            int count = infos.size();
            signatureHash = mix(signatureHash, tag);
            signatureHash = mix(signatureHash, count);
            signatureCount += count;
            for (int i = 0; i < count; i++) {
                Object value = infos.get(i);
                if (!(value instanceof TerrainRenderInfoAccessor)) return false;
                TerrainRenderInfoAccessor info = (TerrainRenderInfoAccessor) value;
                if (!info.ice$isCanonicalRenderInfo()) return false;
                Object chunk = info.ice$renderChunk();
                if (!(chunk instanceof TerrainRenderChunkIndexAccessor)) return false;
                TerrainRenderChunkIndexAccessor chunkAccess =
                    (TerrainRenderChunkIndexAccessor) chunk;
                int index = chunkAccess.ice$renderChunkIndex();
                if (index < 0 || index >= chunkByIndex.length
                    || chunkByIndex[index] != chunk || signatureVisits[index] == stamp) {
                    return false;
                }
                signatureVisits[index] = stamp;
                Object incoming = info.ice$incomingDirection();
                int incomingIndex = incoming == null ? -1 : directionIndex(incoming);
                int path = info.ice$pathDirections() & 0xff;
                int counter = info.ice$counter();
                if (incomingIndex < -1 || counter < 0
                    || (incomingIndex >= 0 && (path & 1 << incomingIndex) == 0)) {
                    return false;
                }
                signatureHash = mix(signatureHash, index);
                signatureHash = mix(signatureHash, incomingIndex + 1);
                signatureHash = mix(signatureHash, path);
                signatureHash = mix(signatureHash, counter);
            }
            return true;
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

        private int nextSignatureGeneration() {
            if (signatureGeneration == Integer.MAX_VALUE) {
                Arrays.fill(signatureVisits, 0);
                signatureGeneration = 1;
            }
            return signatureGeneration++;
        }

        private void releaseQueueReferences() {
            for (int i = 0; i < retainedQueueEntries; i++) {
                infoQueue[i] = null;
                chunkQueue[i] = null;
                incomingQueue[i] = null;
                pathQueue[i] = 0;
                counterQueue[i] = 0;
            }
            retainedQueueEntries = 0;
            traversalOrigin = null;
        }

        private int capacity() { return chunkByIndex.length; }
    }

    private static final class Sample {
        private final Object owner;
        private final Workspace workspace;
        private final long sceneKey;
        private final MeasurementArm arm;
        private final boolean regressionProbe;
        private final boolean baselineOnly;
        private final long startedNanos;
        private boolean committed;
        private long traversalNanos;

        private Sample(Object owner, Workspace workspace, long sceneKey,
                       Decision decision, long startedNanos) {
            this.owner = owner;
            this.workspace = workspace;
            this.sceneKey = sceneKey;
            this.arm = decision.arm;
            this.regressionProbe = decision.regressionProbe;
            this.baselineOnly = decision.baselineOnly;
            this.startedNanos = startedNanos;
        }
    }

    private static final class Decision {
        private static final Decision LEGACY = new Decision(MeasurementArm.LEGACY, false);
        private static final Decision MODERN = new Decision(MeasurementArm.MODERN, false);
        private static final Decision BASELINE = new Decision(
            MeasurementArm.LEGACY, false, true);
        private final MeasurementArm arm;
        private final boolean regressionProbe;
        private final boolean baselineOnly;
        private Decision(MeasurementArm arm, boolean regressionProbe) {
            this(arm, regressionProbe, false);
        }
        private Decision(MeasurementArm arm, boolean regressionProbe,
                         boolean baselineOnly) {
            this.arm = arm;
            this.regressionProbe = regressionProbe;
            this.baselineOnly = baselineOnly;
        }
    }

    private static final class Signature {
        private static final Signature INVALID = new Signature(false, 0, 0L);
        private final boolean valid;
        private final int count;
        private final long hash;
        private Signature(boolean valid, int count, long hash) {
            this.valid = valid;
            this.count = count;
            this.hash = hash;
        }

        private boolean matches(Signature other) {
            return valid && other != null && other.valid
                && count == other.count && hash == other.hash;
        }
    }

    /** Fixed-size, exact-key cache; collisions replace and therefore fail safe. */
    private static final class BaselineTable {
        private final long[] keys = new long[BASELINE_SLOTS];
        private final Signature[] values = new Signature[BASELINE_SLOTS];

        private Signature get(long key) {
            int index = index(key);
            return values[index] != null && keys[index] == key
                ? values[index] : null;
        }

        private void put(long key, Signature value) {
            if (value == null || !value.valid) return;
            int index = index(key);
            keys[index] = key;
            values[index] = value;
        }

        private void clear() {
            Arrays.fill(keys, 0L);
            Arrays.fill(values, null);
        }

        private static int index(long key) {
            long mixed = key ^ key >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            return (int) mixed & (BASELINE_SLOTS - 1);
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

    private static long mix(long hash, int value) {
        hash ^= value & 0xffffffffL;
        return hash * FNV_PRIME;
    }

    private static long percentile(long[] values, int length, double quantile) {
        long[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        int index = (int) Math.ceil(quantile * length) - 1;
        return copy[Math.max(0, Math.min(copy.length - 1, index))];
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable error) throws T {
        throw (T) error;
    }
}

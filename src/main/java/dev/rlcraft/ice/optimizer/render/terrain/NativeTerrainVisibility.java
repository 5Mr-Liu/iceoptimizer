package dev.rlcraft.ice.optimizer.render.terrain;

import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderChunkIndexAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderInfoAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor;
import dev.rlcraft.ice.optimizer.compat.chunk.NativeTerrainWorldAccessor;
import java.util.Arrays;
import java.util.Queue;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

/** Camera-directed section BFS with merged entry-face visibility. */
public final class NativeTerrainVisibility {
    private static final EnumFacing[] FACES = EnumFacing.values();
    private static State state;

    private NativeTerrainVisibility() { }

    public static void traverse(Object owner, Queue seeds, Object origin, Object camera,
                                int frame, boolean occlusion, boolean fog, int distance,
                                Entity view, double partial) {
        TerrainVisibilityAccessor access = (TerrainVisibilityAccessor) owner;
        Object[] sections = access.ice$renderChunks();
        if (state == null || state.sections != sections) state = new State(sections);
        Vec3d offset = ActiveRenderInfo.getCameraPosition();
        double x = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial + offset.x;
        double y = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial + offset.y;
        double z = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial + offset.z;
        if (!seeds.isEmpty()) {
            RenderChunk seed = (RenderChunk) ((TerrainRenderInfoAccessor) seeds.peek()).ice$renderChunk();
            BlockPos position = seed.getPosition();
            if (position.getX() != (MathHelper.floor(x) >> 4 << 4)
                || position.getY() != (MathHelper.floor(y) >> 4 << 4)
                || position.getZ() != (MathHelper.floor(z) >> 4 << 4)) {
                RenderChunk root = (RenderChunk) ((NativeTerrainWorldAccessor) owner).ice$cameraSection(new BlockPos(x, y, z));
                if (root != null) {
                    seeds.clear();
                    seeds.add(access.ice$newRenderInfo(root, null, 0));
                }
            }
        }
        state.traverse(access, seeds, origin, camera, frame, occlusion, fog, distance, x, y, z);
    }

    public static void reset() { state = null; }

    // With camera-directed edges, all paths to a section have the same Manhattan
    // depth. Every incoming face is merged before the BFS dequeues that section.
    static int awayFaces(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
        return (cameraY >= y ? 1 : 0) | (cameraY <= y + 16 ? 2 : 0)
            | (cameraZ >= z ? 4 : 0) | (cameraZ <= z + 16 ? 8 : 0)
            | (cameraX >= x ? 16 : 0) | (cameraX <= x + 16 ? 32 : 0);
    }

    static int exits(long visibility, int entryFace) {
        return (int) (visibility >>> (entryFace * 6)) & 63;
    }

    static final class State {
        final Object[] sections;
        final RenderChunk[] byIndex;
        final int[] queue, visited;
        final byte[] exits;
        final long[] visibility;
        final Object[] infos;
        int epoch;

        State(Object[] sections) {
            this.sections = sections;
            int size = sections.length;
            queue = new int[size]; visited = new int[size];
            byIndex = new RenderChunk[size];
            // ViewFrustum's spatial array order need not equal its chunk IDs.
            for (Object section : sections) {
                RenderChunk chunk = (RenderChunk) section;
                byIndex[index(chunk)] = chunk;
            }
            exits = new byte[size]; infos = new Object[size];
            visibility = new long[size];
        }

        void traverse(TerrainVisibilityAccessor access, Queue seeds, Object origin, Object camera,
                      int frame, boolean occlusion, boolean fog, int distance,
                      double cameraX, double cameraY, double cameraZ) {
            if (++epoch <= 0) { Arrays.fill(visited, 0); epoch = 1; }
            NativeTerrainDrawQueue drawQueue = NativeTerrainRenderer.beginVisibility();
            int head = 0, tail = 0;
            Object seed;
            while ((seed = seeds.poll()) != null) {
                RenderChunk chunk = (RenderChunk) ((TerrainRenderInfoAccessor) seed).ice$renderChunk();
                int index = index(chunk);
                if (visited[index] == epoch) continue;
                visited[index] = epoch; exits[index] = 63; infos[index] = seed;
                queue[tail++] = index;
            }
            while (head < tail) {
                int currentIndex = queue[head++];
                RenderChunk current = byIndex[currentIndex];
                visited[currentIndex] = -epoch;
                access.ice$appendRenderInfo(infos[currentIndex], current);
                if (drawQueue != null) drawQueue.add(current);
                BlockPos pos = current.getPosition();
                int directions = (occlusion ? exits[currentIndex] : 63)
                    & awayFaces(pos.getX(), pos.getY(), pos.getZ(), cameraX, cameraY, cameraZ);
                while (directions != 0) {
                    int direction = Integer.numberOfTrailingZeros(directions);
                    directions &= directions - 1;
                    RenderChunk next = (RenderChunk) access.ice$getRenderChunkOffset(origin, current,
                        FACES[direction], fog, distance);
                    if (next == null) continue;
                    int nextIndex = index(next);
                    int mark = visited[nextIndex];
                    if (mark == -epoch) continue;
                    TerrainRenderChunkIndexAccessor nextAccess = (TerrainRenderChunkIndexAccessor) next;
                    if (mark != epoch) {
                        visited[nextIndex] = epoch;
                        if (!access.ice$isInFrustum(next, camera, nextAccess.ice$bounds(), frame)) {
                            visited[nextIndex] = -epoch;
                            continue;
                        }
                        exits[nextIndex] = 0;
                        visibility[nextIndex] = occlusion ? nextAccess.ice$visibilityMask() : 0;
                        infos[nextIndex] = access.ice$newRenderInfo(next, FACES[direction], 0);
                        queue[tail++] = nextIndex;
                    }
                    int incoming = direction ^ 1;
                    long mask = visibility[nextIndex];
                    int allowed = 0;
                    if (!occlusion) allowed = 63;
                    else if (mask >= 0) allowed = exits(mask, incoming);
                    else {
                        // Respect CompiledChunk subclasses which override face visibility.
                        for (int to = 0; to < 6; to++)
                            if (nextAccess.ice$isVisible(FACES[incoming], FACES[to])) allowed |= 1 << to;
                    }
                    exits[nextIndex] |= allowed;
                }
            }
        }

        private static int index(RenderChunk chunk) {
            return ((TerrainRenderChunkIndexAccessor) chunk).ice$renderChunkIndex();
        }
    }
}

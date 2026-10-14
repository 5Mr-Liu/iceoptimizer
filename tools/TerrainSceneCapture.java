package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Test-only current-frame evidence, captured before timing in a disposable game. */
public final class TerrainSceneCapture {
    private static boolean armed;
    private static int captured;
    public static void arm() { armed = true; captured = 0; }
    public static void disarm() { armed = false; }

    public static void capture(Object arena, Object container, Object layerObject) {
        if (!armed) return;
        BlockRenderLayer layer = (BlockRenderLayer) layerObject;
        int bit = 1 << layer.ordinal();
        if ((captured & bit) != 0) return;
        captured |= bit;
        File game = Minecraft.getMinecraft().gameDir;
        if (!new File(game, "ICE_ISOLATED_BENCHMARK.marker").isFile())
            throw new IllegalStateException("Capture requires isolated game");
        File output = new File(game, "ice-benchmark/terrain-" + layer.name() + ".bin");
        output.getParentFile().mkdirs();
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            Class<?> accessor = Class.forName("dev.rlcraft.ice.optimizer.compat.chunk.TerrainRenderListAccessor");
            List<?> visible = (List<?>) accessor.getMethod("ice$renderChunks").invoke(container);
            Map<?,?> meshes = (Map<?,?>) field(arena, "meshes");
            Object visibility = field(arena, "visibilitySource");
            Map<?,?> occluders = visibility == null ? Collections.emptyMap() : (Map<?,?>) field(visibility, "chunks");
            out.writeUTF("ICE_TERRAIN_SCENE_2"); out.writeUTF(layer.name());
            for (String axis : new String[]{"X", "Y", "Z"})
                out.writeDouble(((Number) accessor.getMethod("ice$viewEntity" + axis).invoke(container)).doubleValue());
            FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
            for (int kind : new int[]{GL11.GL_MODELVIEW_MATRIX, GL11.GL_PROJECTION_MATRIX}) {
                matrix.clear(); GL11.glGetFloat(kind, matrix);
                for (int i = 0; i < 16; i++) out.writeFloat(matrix.get(i));
            }
            out.writeInt(visible.size());
            long bytes = 0;
            for (Object item : visible) {
                RenderChunk chunk = (RenderChunk) item;
                out.writeInt(chunk.getPosition().getX()); out.writeInt(chunk.getPosition().getY()); out.writeInt(chunk.getPosition().getZ());
                VertexBuffer vbo = chunk.getVertexBufferByLayer(layer.ordinal());
                Object record = meshes.get(vbo);
                out.writeBoolean(record != null);
                if (record != null) {
                    for (String axis : new String[]{"X", "Y", "Z"}) out.writeInt(((Number) field(record, "region" + axis)).intValue());
                    int vertices = ((Number) field(record, "vertexCount")).intValue();
                    out.writeInt(vertices);
                    Object range = field(record, "range");
                    Method bufferMethod = arena.getClass().getDeclaredMethod("nativeBuffer", range.getClass());
                    Method offsetMethod = arena.getClass().getDeclaredMethod("pageOffset", range.getClass());
                    bufferMethod.setAccessible(true); offsetMethod.setAccessible(true);
                    int buffer = ((Number) bufferMethod.invoke(arena, range)).intValue();
                    long offset = ((Number) offsetMethod.invoke(arena, range)).longValue();
                    out.writeInt(buffer); out.writeLong(offset);
                    int size = Math.multiplyExact(vertices, 28);
                    Object mesh = field(record, "mesh");
                    boolean indexed = false;
                    int unique = vertices;
                    try {
                        indexed = (Boolean) mesh.getClass().getMethod("isIndexed").invoke(mesh);
                        unique = ((Number) mesh.getClass().getMethod("getStoredVertexCount").invoke(mesh)).intValue();
                    } catch (NoSuchMethodException olderUnindexedBuild) { }
                    if (indexed) size = Math.addExact(Math.multiplyExact(unique, 28), Math.multiplyExact(vertices, 2));
                    out.writeBoolean(indexed); out.writeInt(unique);
                    // Store the actual compact payload and original physical
                    // address. Never read vertices*28 across its allocation.
                    ByteBuffer data = BufferUtils.createByteBuffer(size);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                    GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, offset, data);
                    byte[] transfer = new byte[Math.min(size, 65536)];
                    while (data.hasRemaining()) { int n = Math.min(data.remaining(), transfer.length); data.get(transfer, 0, n); out.write(transfer, 0, n); }
                    bytes += size;
                }
                Object entry = occluders.get(chunk);
                boolean current = false;
                if (entry != null) {
                    Method valid = entry.getClass().getDeclaredMethod("current", RenderChunk.class); valid.setAccessible(true);
                    current = (Boolean) valid.invoke(entry, chunk);
                }
                out.writeBoolean(current);
                if (current) {
                    Object space = field(entry, "space"), mesh = field(entry, "mesh");
                    out.writeBoolean((Boolean) field(space, "renderRegions"));
                    for (String axis : new String[]{"X", "Y", "Z"}) out.writeDouble(((Number) field(space, "origin" + axis)).doubleValue());
                    for (String bound : new String[]{"minX","minY","minZ","maxX","maxY","maxZ"}) out.writeDouble(((Number) field(mesh, bound)).doubleValue());
                    float[] quads = (float[]) field(mesh, "quads");
                    out.writeInt(quads.length); for (float value : quads) out.writeFloat(value);
                }
            }
            System.out.println("ICE_BENCH captured_terrain=" + layer + " chunks=" + visible.size() + " bytes=" + bytes);
        } catch (Exception failure) { throw new IllegalStateException("Terrain capture failed", failure); }
        finally { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous); }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}

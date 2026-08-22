package dev.rlcraft.ice.optimizer.render.entity;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import net.minecraft.client.renderer.vertex.VertexFormat;

/**
 * Immutable exact copy of the quads compiled into one ModelRenderer display
 * list.  The payload is produced on the render thread, but still carries both
 * generations so a delayed publication can never populate a newer context.
 */
public final class ModelMeshPayload {
    private final int displayList;
    private final byte[] vertices;
    private final byte[] digest;
    private final VertexFormat format;
    private final int drawMode;
    private final int vertexCount;
    private final long resourceGeneration;
    private final long contextGeneration;

    public ModelMeshPayload(int displayList, byte[] vertices, VertexFormat format,
                            int drawMode, int vertexCount,
                            long resourceGeneration, long contextGeneration) {
        if (displayList <= 0 || vertices == null || vertices.length == 0
            || vertices.length > 16 * 1024 * 1024 || format == null
            || format.getSize() <= 0 || format.getSize() > 256
            || vertices.length % format.getSize() != 0 || drawMode <= 0
            || vertexCount <= 0 || vertexCount != vertices.length / format.getSize()
            || resourceGeneration <= 0L || contextGeneration <= 0L) {
            throw new IllegalArgumentException("model mesh payload");
        }
        this.displayList = displayList;
        this.vertices = vertices.clone();
        this.digest = sha256(this.vertices);
        this.format = new VertexFormat(format);
        this.drawMode = drawMode;
        this.vertexCount = vertexCount;
        this.resourceGeneration = resourceGeneration;
        this.contextGeneration = contextGeneration;
    }

    public int getDisplayList() { return displayList; }
    public byte[] getVertices() { return vertices.clone(); }
    void copyVerticesTo(ByteBuffer target) {
        if (target == null || target.remaining() < vertices.length) {
            throw new IllegalArgumentException("model mesh copy target");
        }
        target.put(vertices);
    }
    public int getByteLength() { return vertices.length; }
    public byte[] getDigest() { return digest.clone(); }
    public VertexFormat getFormat() { return new VertexFormat(format); }
    public int getDrawMode() { return drawMode; }
    public int getVertexCount() { return vertexCount; }
    public long getResourceGeneration() { return resourceGeneration; }
    public long getContextGeneration() { return contextGeneration; }

    public boolean sameContent(ModelMeshPayload other) {
        return other != null && drawMode == other.drawMode
            && vertexCount == other.vertexCount && format.equals(other.format)
            && Arrays.equals(digest, other.digest);
    }

    public static byte[] sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("model mesh digest");
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static byte[] sha256(ByteBuffer bytes) {
        if (bytes == null) throw new IllegalArgumentException("model mesh digest");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes.duplicate());
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

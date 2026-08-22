package dev.rlcraft.ice.optimizer.render.visibility;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Copies matrices Minecraft already queried; this bridge performs no GL readback. */
public final class RenderMatrixBridge {
    private static final float[] MODEL_VIEW = new float[16];
    private static final float[] PROJECTION = new float[16];
    private static final int[] VIEWPORT = new int[4];
    private static long version;

    private RenderMatrixBridge() {
    }

    public static synchronized void capture(FloatBuffer modelView,
                                            FloatBuffer projection,
                                            IntBuffer viewport) {
        if (modelView == null || projection == null || viewport == null
            || modelView.limit() < 16 || projection.limit() < 16
            || viewport.limit() < 4) return;
        for (int i = 0; i < 16; i++) {
            MODEL_VIEW[i] = modelView.get(i);
            PROJECTION[i] = projection.get(i);
        }
        for (int i = 0; i < 4; i++) VIEWPORT[i] = viewport.get(i);
        if (version != Long.MAX_VALUE) version++;
    }

    public static synchronized Snapshot snapshot() {
        if (version == 0L) return null;
        return new Snapshot(MODEL_VIEW.clone(), PROJECTION.clone(), VIEWPORT.clone(), version);
    }

    public static final class Snapshot {
        private final float[] modelView;
        private final float[] projection;
        private final int[] viewport;
        private final long version;
        private final long matrixHash;

        private Snapshot(float[] modelView, float[] projection,
                         int[] viewport, long version) {
            this.modelView = modelView;
            this.projection = projection;
            this.viewport = viewport;
            this.version = version;
            this.matrixHash = hash(modelView, projection, viewport);
        }

        public float[] copyModelView() { return modelView.clone(); }
        public float[] copyProjection() { return projection.clone(); }
        public int getViewportX() { return viewport[0]; }
        public int getViewportY() { return viewport[1]; }
        public int getWidth() { return viewport[2]; }
        public int getHeight() { return viewport[3]; }
        public long getVersion() { return version; }
        public long getMatrixHash() { return matrixHash; }

        boolean matrixEquals(Snapshot other) {
            if (other == null || matrixHash != other.matrixHash) return false;
            for (int i = 0; i < 16; i++) {
                if (Float.floatToRawIntBits(modelView[i])
                    != Float.floatToRawIntBits(other.modelView[i])
                    || Float.floatToRawIntBits(projection[i])
                    != Float.floatToRawIntBits(other.projection[i])) return false;
            }
            for (int i = 0; i < 4; i++) if (viewport[i] != other.viewport[i]) return false;
            return true;
        }

        float[] modelViewUnsafe() { return modelView; }
        float[] projectionUnsafe() { return projection; }

        private static long hash(float[] model, float[] projection, int[] viewport) {
            long result = 0xcbf29ce484222325L;
            for (float value : model) {
                result ^= Float.floatToRawIntBits(value) & 0xffffffffL;
                result *= 0x100000001b3L;
            }
            for (float value : projection) {
                result ^= Float.floatToRawIntBits(value) & 0xffffffffL;
                result *= 0x100000001b3L;
            }
            for (int value : viewport) {
                result ^= value & 0xffffffffL;
                result *= 0x100000001b3L;
            }
            return result;
        }
    }
}

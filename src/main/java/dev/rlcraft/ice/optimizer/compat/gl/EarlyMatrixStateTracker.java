package dev.rlcraft.ice.optimizer.compat.gl;

import java.nio.FloatBuffer;

/** Core-JAR resident render-thread mirror of compatibility matrix stacks. */
public final class EarlyMatrixStateTracker {
    private static final int MODELVIEW = 5888;
    private static final int PROJECTION = 5889;
    private static final int TEXTURE = 5890;
    private static final ThreadLocal<State> STATES = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private EarlyMatrixStateTracker() {
    }

    public static void seed(int mode, FloatBuffer modelView, FloatBuffer projection) {
        seed(mode, modelView, projection, null);
    }

    public static void seed(int mode, FloatBuffer modelView, FloatBuffer projection,
                            FloatBuffer texture) {
        State state = STATES.get();
        try {
            state.modelView.reset();
            state.projection.reset();
            state.texture.reset();
            read16(modelView, state.modelView.top());
            read16(projection, state.projection.top());
            if (texture == null) identity(state.texture.top());
            else read16(texture, state.texture.top());
            state.mode = mode;
            state.known = stack(state) != null
                && finite(state.modelView.top())
                && finite(state.projection.top())
                && finite(state.texture.top());
            if (!state.known) state.invalidations++;
        } catch (Throwable error) {
            rethrowIfFatal(error);
            invalidate();
        }
    }

    public static void matrixMode(int mode) {
        State state = STATES.get();
        state.mode = mode;
        if (stack(state) == null) invalidate();
    }

    public static void loadIdentity() {
        MatrixStack stack = current();
        if (stack != null) identity(stack.top());
    }

    public static void pushMatrix() {
        MatrixStack stack = current();
        if (stack == null || !stack.push()) invalidate();
    }

    public static void popMatrix() {
        MatrixStack stack = current();
        if (stack == null || !stack.pop()) invalidate();
    }

    public static void rotate(float degrees, float x, float y, float z) {
        State state = STATES.get();
        MatrixStack stack = state.known ? stack(state) : null;
        if (stack == null) return;
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!(length > 0.0D) || !Double.isFinite(length)
            || !Float.isFinite(degrees)) {
            invalidate();
            return;
        }
        float nx = (float) (x / length);
        float ny = (float) (y / length);
        float nz = (float) (z / length);
        double radians = degrees * (Math.PI / 180.0D);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float one = 1.0F - c;
        float[] rotation = state.right;
        for (int i = 0; i < 16; i++) rotation[i] = 0.0F;
        rotation[0] = nx * nx * one + c;
        rotation[1] = ny * nx * one + nz * s;
        rotation[2] = nx * nz * one - ny * s;
        rotation[4] = nx * ny * one - nz * s;
        rotation[5] = ny * ny * one + c;
        rotation[6] = ny * nz * one + nx * s;
        rotation[8] = nx * nz * one + ny * s;
        rotation[9] = ny * nz * one - nx * s;
        rotation[10] = nz * nz * one + c;
        rotation[15] = 1.0F;
        if (!multiply(stack.top(), rotation, state.result)) invalidate();
    }

    public static void scale(float x, float y, float z) { scale0(x, y, z); }
    public static void scale(double x, double y, double z) {
        scale0((float) x, (float) y, (float) z);
    }

    public static void translate(float x, float y, float z) { translate0(x, y, z); }
    public static void translate(double x, double y, double z) {
        translate0((float) x, (float) y, (float) z);
    }

    public static void multMatrix(FloatBuffer source) {
        State state = STATES.get();
        MatrixStack stack = state.known ? stack(state) : null;
        if (stack == null) return;
        try {
            float[] right = state.right;
            read16(source, right);
            if (!finite(right) || !multiply(stack.top(), right, state.result)) {
                invalidate();
            }
        } catch (Throwable error) {
            rethrowIfFatal(error);
            invalidate();
        }
    }

    public static void ortho(double left, double right, double bottom, double top,
                             double near, double far) {
        State state = STATES.get();
        MatrixStack stack = state.known ? stack(state) : null;
        double width = right - left;
        double height = top - bottom;
        double depth = far - near;
        if (stack == null || !Double.isFinite(width) || !Double.isFinite(height)
            || !Double.isFinite(depth) || width == 0.0D || height == 0.0D
            || depth == 0.0D) {
            invalidate();
            return;
        }
        float[] matrix = state.right;
        for (int i = 0; i < 16; i++) matrix[i] = 0.0F;
        matrix[0] = (float) (2.0D / width);
        matrix[5] = (float) (2.0D / height);
        matrix[10] = (float) (-2.0D / depth);
        matrix[12] = (float) (-(right + left) / width);
        matrix[13] = (float) (-(top + bottom) / height);
        matrix[14] = (float) (-(far + near) / depth);
        matrix[15] = 1.0F;
        if (!finite(matrix) || !multiply(stack.top(), matrix, state.result)) {
            invalidate();
        }
    }

    public static float[] modelView() {
        State state = STATES.get();
        if (!state.known) return null;
        return state.modelView.top().clone();
    }

    public static boolean matchesModelView(float[] expected) {
        State state = STATES.get();
        if (!state.known || expected == null || expected.length != 16) return false;
        float[] actual = state.modelView.top();
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(actual[i])
                != Float.floatToRawIntBits(expected[i])) return false;
        }
        return true;
    }

    public static Snapshot snapshot() {
        State state = STATES.get();
        return state.known ? new Snapshot(state.mode, state.modelView.top(),
            state.projection.top(), state.texture.top()) : null;
    }

    public static void invalidate() {
        State state = STATES.get();
        state.known = false;
        state.invalidations++;
    }

    public static boolean isKnown() { return STATES.get().known; }
    public static int currentMode() {
        State state = STATES.get();
        return state.known ? state.mode : -1;
    }
    public static long invalidations() { return STATES.get().invalidations; }

    public static final class Snapshot {
        private final int mode;
        private final float[] modelView;
        private final float[] projection;
        private final float[] texture;

        private Snapshot(int mode, float[] modelView, float[] projection,
                         float[] texture) {
            this.mode = mode;
            this.modelView = modelView.clone();
            this.projection = projection.clone();
            this.texture = texture.clone();
        }

        public int getMode() { return mode; }
        public float[] getModelView() { return modelView.clone(); }
        public float[] getProjection() { return projection.clone(); }
        public float[] getTexture() { return texture.clone(); }
    }

    private static void scale0(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            invalidate();
            return;
        }
        MatrixStack stack = current();
        if (stack == null) return;
        float[] matrix = stack.top();
        for (int row = 0; row < 4; row++) {
            matrix[row] *= x;
            matrix[4 + row] *= y;
            matrix[8 + row] *= z;
        }
        if (!finite(matrix)) invalidate();
    }

    private static void translate0(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            invalidate();
            return;
        }
        MatrixStack stack = current();
        if (stack == null) return;
        float[] m = stack.top();
        for (int row = 0; row < 4; row++) {
            m[12 + row] = m[row] * x + m[4 + row] * y
                + m[8 + row] * z + m[12 + row];
        }
        if (!finite(m)) invalidate();
    }

    private static MatrixStack current() {
        State state = STATES.get();
        if (!state.known) return null;
        MatrixStack result = stack(state);
        if (result == null) invalidate();
        return result;
    }

    private static MatrixStack stack(State state) {
        if (state.mode == MODELVIEW) return state.modelView;
        if (state.mode == PROJECTION) return state.projection;
        if (state.mode == TEXTURE) return state.texture;
        return null;
    }

    private static boolean multiply(float[] left, float[] right, float[] result) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0.0F;
                for (int k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                if (!Float.isFinite(value)) return false;
                result[column * 4 + row] = value;
            }
        }
        System.arraycopy(result, 0, left, 0, 16);
        return true;
    }

    private static boolean finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static void read16(FloatBuffer source, float[] target) {
        if (source == null || source.remaining() < 16) {
            throw new IllegalArgumentException("matrix buffer");
        }
        FloatBuffer copy = source.duplicate();
        for (int i = 0; i < 16; i++) target[i] = copy.get();
    }

    private static void identity(float[] matrix) {
        for (int i = 0; i < 16; i++) matrix[i] = 0.0F;
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1.0F;
    }

    /** Keep this early Core-JAR ABI independent of the regular Main runtime. */
    private static void rethrowIfFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (current instanceof ThreadDeath) throw (ThreadDeath) current;
            if (current instanceof VirtualMachineError) {
                throw (VirtualMachineError) current;
            }
            Throwable next = current.getCause();
            if (next == current) return;
            current = next;
        }
    }

    private static final class State {
        private final MatrixStack modelView = new MatrixStack(64);
        private final MatrixStack projection = new MatrixStack(8);
        private final MatrixStack texture = new MatrixStack(8);
        private final float[] right = new float[16];
        private final float[] result = new float[16];
        private int mode = MODELVIEW;
        private boolean known;
        private long invalidations;
    }

    private static final class MatrixStack {
        private final float[][] values;
        private int depth;

        private MatrixStack(int capacity) {
            values = new float[capacity][16];
            identity(values[0]);
        }

        private float[] top() { return values[depth]; }

        private boolean push() {
            if (depth + 1 >= values.length) return false;
            System.arraycopy(values[depth], 0, values[depth + 1], 0, 16);
            depth++;
            return true;
        }

        private boolean pop() {
            if (depth == 0) return false;
            depth--;
            return true;
        }

        private void reset() { depth = 0; }
    }
}

import com.sun.management.ThreadMXBean;
import dev.rlcraft.ice.optimizer.compat.gl.EarlyMatrixStateTracker;
import java.lang.management.ManagementFactory;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;

/** Standalone Java 8 matrix mirror CPU/allocation benchmark, without GL or game FPS. */
public final class MatrixTransformBenchmark {
    private static final int FRAMES = 10000;
    private static final int TRANSFORMS = 64;
    private static volatile long checksum;

    public static void main(String[] args) {
        if (args.length != 2 || !("unit".equals(args[0])
            || "general".equals(args[0]) || "multiply".equals(args[0]))) {
            throw new IllegalArgumentException("unit|general|multiply before|after");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 4; i++) run(bean, args[0]);
        double[] nanos = new double[7];
        double[] bytes = new double[7];
        for (int i = 0; i < nanos.length; i++) {
            double[] result = run(bean, args[0]);
            nanos[i] = result[0];
            bytes[i] = result[1];
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT,
            "%s %s ns_per_op=%.2f bytes_per_op=%.2f checksum=%d%n",
            args[0], args[1], nanos[3], bytes[3], checksum);
    }

    private static double[] run(ThreadMXBean bean, String mode) {
        float[] identity = new float[16];
        for (int i = 0; i < identity.length; i++) identity[i] = i % 5 == 0 ? 1.0F : 0.0F;
        EarlyMatrixStateTracker.seed(5888, FloatBuffer.wrap(identity),
            FloatBuffer.wrap(identity));
        FloatBuffer[] multipliers = new FloatBuffer[TRANSFORMS];
        float[] angles = new float[TRANSFORMS];
        float[][] axes = new float[TRANSFORMS][3];
        for (int i = 0; i < TRANSFORMS; i++) {
            angles[i] = (i * 17 % 53) - 26.25F;
            axes[i][i % 3] = (i & 1) == 0 ? 1.0F : -1.0F;
            float[] right = identity.clone();
            double radians = angles[i] * Math.PI / 180.0D;
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
            right[0] = right[5] = c;
            right[1] = s;
            right[4] = -s;
            multipliers[i] = FloatBuffer.wrap(right).asReadOnlyBuffer();
        }
        long thread = Thread.currentThread().getId();
        long startBytes = bean.getThreadAllocatedBytes(thread);
        long startTime = System.nanoTime();
        long total = 0L;
        for (int frame = 0; frame < FRAMES; frame++) {
            EarlyMatrixStateTracker.pushMatrix();
            for (int transform = 0; transform < TRANSFORMS; transform++) {
                if ("multiply".equals(mode)) {
                    EarlyMatrixStateTracker.multMatrix(multipliers[transform]);
                } else if ("general".equals(mode)) {
                    EarlyMatrixStateTracker.rotate(angles[transform], 2.0F, -3.0F, 4.0F);
                } else {
                    float[] axis = axes[transform];
                    EarlyMatrixStateTracker.rotate(angles[transform], axis[0], axis[1], axis[2]);
                }
            }
            float[] matrix = EarlyMatrixStateTracker.modelView();
            if (matrix == null) throw new AssertionError("matrix invalidated");
            for (float value : matrix) total += Float.floatToRawIntBits(value);
            EarlyMatrixStateTracker.popMatrix();
        }
        long elapsed = System.nanoTime() - startTime;
        long allocated = bean.getThreadAllocatedBytes(thread) - startBytes;
        checksum = total;
        double operations = FRAMES * (double) TRANSFORMS;
        return new double[] {elapsed / operations, allocated / operations};
    }
}

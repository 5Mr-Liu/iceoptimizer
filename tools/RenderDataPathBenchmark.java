import com.sun.management.ThreadMXBean;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache.GlyphLayout;
import dev.rlcraft.ice.optimizer.render.visibility.RenderMatrixBridge;
import java.lang.management.ManagementFactory;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;

/** Java 8 allocation/CPU comparison for matrix reads and font cache admission. */
public final class RenderDataPathBenchmark {
    private static volatile Object sink;
    private static volatile long checksum;

    public static void main(String[] args) {
        if (args.length != 2 || !("matrix".equals(args[0]) || "font".equals(args[0]))
            || !("before".equals(args[1]) || "after".equals(args[1]))) {
            throw new IllegalArgumentException("matrix|font before|after");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 3; i++) run(bean, args[0], "before".equals(args[1]));
        double[] nanos = new double[5];
        double[] bytes = new double[5];
        for (int i = 0; i < 5; i++) {
            double[] result = run(bean, args[0], "before".equals(args[1]));
            nanos[i] = result[0];
            bytes[i] = result[1];
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT, "%s %s ns_per_op=%.2f bytes_per_op=%.2f checksum=%d%n",
            args[0], args[1], nanos[2], bytes[2], checksum);
    }

    private static double[] run(ThreadMXBean bean, String mode, boolean before) {
        FloatBuffer model = FloatBuffer.allocate(16);
        FloatBuffer projection = FloatBuffer.allocate(16);
        IntBuffer viewport = IntBuffer.wrap(new int[] {0, 0, 1920, 1080});
        for (int i = 0; i < 16; i++) {
            model.put(i, i % 5 == 0 ? 1.0F : 0.0F);
            projection.put(i, i % 5 == 0 ? 1.0F : 0.0F);
        }
        float[] capture = new float[256 * 20];
        int[] pages = new int[256];
        Arrays.fill(pages, 1);
        long thread = Thread.currentThread().getId();
        long startBytes = bean.getThreadAllocatedBytes(thread);
        long startTime = System.nanoTime();
        long total = 0L;
        int operations;
        if ("matrix".equals(mode)) {
            operations = 100000 * 8;
            for (int frame = 0; frame < 100000; frame++) {
                model.put(12, (float) frame);
                RenderMatrixBridge.capture(model, projection, viewport);
                for (int read = 0; read < 8; read++) {
                    RenderMatrixBridge.Snapshot snapshot = RenderMatrixBridge.snapshot();
                    total += snapshot.getMatrixHash();
                    sink = snapshot;
                }
            }
        } else {
            operations = 10000;
            for (int i = 0; i < operations; i++) {
                GlyphLayout layout = before
                    ? new GlyphLayout(Arrays.copyOf(capture, 128 * 20),
                        Arrays.copyOf(pages, 128), 128.0F)
                    : new GlyphLayout(capture, pages, 128, 128.0F);
                total += layout.glyphCount();
                sink = layout;
            }
            if (total != 128L * operations) throw new AssertionError("glyph count");
        }
        long elapsed = System.nanoTime() - startTime;
        long allocated = bean.getThreadAllocatedBytes(thread) - startBytes;
        checksum = total;
        return new double[] {elapsed / (double) operations, allocated / (double) operations};
    }
}

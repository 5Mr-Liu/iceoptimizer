import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

/** Measures production CPU mesh checks only, without issuing any GL work. */
public final class LycanitesMeshValidationBenchmark {
    private static final int OPERATIONS = 50000;
    private static volatile long checksum;
    private static volatile Object signatureSink;

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("before".equals(args[0]) || "after".equals(args[0]))) {
            throw new IllegalArgumentException("before|after");
        }
        boolean before = "before".equals(args[0]);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        Probe probe = new Probe();
        for (int i = 0; i < 3; i++) run(bean, probe, before);
        double[] nanos = new double[5];
        double[] bytes = new double[5];
        for (int i = 0; i < 5; i++) {
            double[] result = run(bean, probe, before);
            nanos[i] = result[0];
            bytes[i] = result[1];
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT,
            "lycanites_mesh %s ns_per_op=%.2f bytes_per_op=%.2f indices=3072 checksum=%d%n",
            args[0], nanos[2], bytes[2], checksum);
    }

    private static double[] run(ThreadMXBean bean, Probe probe, boolean before) throws Exception {
        long thread = Thread.currentThread().getId();
        long startBytes = bean.getThreadAllocatedBytes(thread);
        long startTime = System.nanoTime();
        long total = 0L;
        for (int i = 0; i < OPERATIONS; i++) {
            boolean accepted;
            if (before) {
                Object signature = probe.capture.invoke(probe.access, probe.captureArgs);
                signatureSink = signature;
                accepted = (Boolean) probe.valid.invoke(signature, probe.noArgs);
            } else {
                accepted = (Boolean) probe.matches.invoke(probe.access, probe.matchArgs);
            }
            if (accepted) total++;
        }
        long elapsed = System.nanoTime() - startTime;
        long allocated = bean.getThreadAllocatedBytes(thread) - startBytes;
        if (total != OPERATIONS) throw new AssertionError("mesh acceptance changed");
        checksum = total;
        return new double[] {elapsed / (double) OPERATIONS, allocated / (double) OPERATIONS};
    }

    private static final class Probe {
        private final Object access;
        private final Method capture;
        private final Method valid;
        private final Method matches;
        private final Object[] captureArgs;
        private final Object[] matchArgs;
        private final Object[] noArgs = new Object[0];

        private Probe() throws Exception {
            String owner = "dev.rlcraft.ice.optimizer.compat.lycanites.LycanitesObjRenderBridge";
            Class<?> accessType = Class.forName(owner + "$MeshAccess");
            Class<?> signatureType = Class.forName(owner + "$MeshSignature");
            Constructor<?> constructor = accessType.getDeclaredConstructor(Class.class);
            constructor.setAccessible(true);
            access = constructor.newInstance(Group.class);
            capture = accessType.getDeclaredMethod("capture", Object.class);
            capture.setAccessible(true);
            valid = signatureType.getDeclaredMethod("isTriangleMesh");
            valid.setAccessible(true);
            matches = accessType.getDeclaredMethod("matchesUploaded", Object.class,
                signatureType, Integer.TYPE);
            matches.setAccessible(true);
            Group group = new Group();
            captureArgs = new Object[] {group};
            matchArgs = new Object[] {group, capture.invoke(access, captureArgs), 17};
        }
    }

    public static final class Group {
        public Mesh mesh = new Mesh();
    }

    public static final class Mesh {
        public int[] indices = new int[3072];
        public Object[] vertices = new Object[1024];
        public Object[] normals = new Object[3072];
        private int vbo = 17;

        public Mesh() {
            for (int i = 0; i < indices.length; i++) indices[i] = i % vertices.length;
            Arrays.fill(vertices, new Object());
            Arrays.fill(normals, new Object());
        }

        public int getVbo() { return vbo; }
    }
}

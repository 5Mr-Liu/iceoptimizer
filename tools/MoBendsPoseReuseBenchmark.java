package dev.rlcraft.ice.hooks;

import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Locale;
import java.util.jar.JarFile;

/** Real Mo' Bends rotation CPU/allocation probe; no GL calls or game FPS estimate. */
public final class MoBendsPoseReuseBenchmark {
    private static final int FRAMES = 10000;
    private static final int PASSES = 32;
    private static volatile long checksum;

    public static void main(String[] args) throws Throwable {
        if (args.length != 3 || !("before".equals(args[0]) || "after".equals(args[0]))
            || !("stable".equals(args[1]) || "changing".equals(args[1]))) {
            throw new IllegalArgumentException("before|after stable|changing <MoBends.jar>");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        Probe probe = new Probe(new File(args[2]), "after".equals(args[0]));
        try {
            boolean changing = "changing".equals(args[1]);
            for (int i = 0; i < 4; i++) run(bean, probe, changing);
            double[] nanos = new double[7];
            double[] bytes = new double[7];
            for (int i = 0; i < nanos.length; i++) {
                double[] result = run(bean, probe, changing);
                nanos[i] = result[0];
                bytes[i] = result[1];
            }
            Arrays.sort(nanos);
            Arrays.sort(bytes);
            System.out.printf(Locale.ROOT,
                "mobends_pose %s %s ns_per_op=%.2f bytes_per_op=%.2f operations=%d checksum=%d%n",
                args[0], args[1], nanos[3], bytes[3], FRAMES * PASSES, checksum);
        } finally {
            probe.loader.close();
        }
    }

    private static double[] run(ThreadMXBean bean, Probe probe, boolean changing) throws Throwable {
        probe.setZ.invokeExact(probe.quaternion, probe.z[0]);
        probe.setW.invokeExact(probe.quaternion, probe.w[0]);
        long thread = Thread.currentThread().getId();
        long startBytes = bean.getThreadAllocatedBytes(thread);
        long startTime = System.nanoTime();
        long total = 0L;
        for (int frame = 0; frame < FRAMES; frame++) {
            for (int i = 0; i < 16; i++) probe.fields[i] = i % 5 == 0 ? 1.0D : 0.0D;
            for (int pass = 0; pass < PASSES; pass++) {
                if (changing) {
                    int index = (frame * PASSES + pass) & 255;
                    probe.setZ.invokeExact(probe.quaternion, probe.z[index]);
                    probe.setW.invokeExact(probe.quaternion, probe.w[index]);
                }
                probe.rotate.invokeExact(probe.quaternion, probe.matrix);
            }
            for (double value : probe.fields) total = 31L * total + Double.doubleToRawLongBits(value);
        }
        long elapsed = System.nanoTime() - startTime;
        long allocated = bean.getThreadAllocatedBytes(thread) - startBytes;
        checksum = total;
        double operations = FRAMES * (double) PASSES;
        return new double[] { elapsed / operations, allocated / operations };
    }

    private static final class Probe {
        private final ByteLoader loader;
        private final Object quaternion;
        private final Object matrix;
        private final double[] fields;
        private final MethodHandle rotate;
        private final MethodHandle setZ;
        private final MethodHandle setW;
        private final float[] z = new float[256];
        private final float[] w = new float[256];

        private Probe(File jarFile, boolean cached) throws Exception {
            loader = new ByteLoader(jarFile.toURI().toURL());
            String quaternionName = "goblinbob.mobends.core.math.Quaternion";
            Class<?> quaternionType;
            if (cached) {
                byte[] original = readClass(jarFile, quaternionName);
                quaternionType = loader.define(quaternionName,
                    new MoBendsQuaternionAdapter().transform(quaternionName, original, null));
            } else quaternionType = loader.loadClass(quaternionName);
            Class<?> matrixType = loader.loadClass("goblinbob.mobends.core.math.matrix.Mat4x4d");
            Class<?> matrixInterface = loader.loadClass("goblinbob.mobends.core.math.matrix.IMat4x4d");
            quaternion = quaternionType.newInstance();
            matrix = matrixType.newInstance();
            fields = (double[]) matrixType.getMethod("getFields").invoke(matrix);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType operation = MethodType.methodType(Void.TYPE, Object.class, Object.class);
            if (cached) {
                rotate = lookup.findVirtual(quaternionType, MoBendsQuaternionAdapter.POSE_METHOD,
                    MethodType.methodType(Void.TYPE, Object.class)).asType(operation);
            } else {
                Class<?> transforms = loader.loadClass("goblinbob.mobends.core.math.TransformUtils");
                MethodHandle original = lookup.findStatic(transforms, "rotate",
                    MethodType.methodType(Void.TYPE, matrixInterface, quaternionType)).asType(operation);
                rotate = MethodHandles.permuteArguments(original, operation, 1, 0);
            }
            MethodType setter = MethodType.methodType(Void.TYPE, Object.class, Float.TYPE);
            setZ = lookup.findSetter(quaternionType, "z", Float.TYPE).asType(setter);
            setW = lookup.findSetter(quaternionType, "w", Float.TYPE).asType(setter);
            for (int i = 0; i < z.length; i++) {
                double halfAngle = ((i * 17 % 71) - 35.0D) * Math.PI / 360.0D;
                z[i] = (float) Math.sin(halfAngle);
                w[i] = (float) Math.cos(halfAngle);
            }
        }
    }

    private static byte[] readClass(File jarFile, String name) throws Exception {
        JarFile jar = new JarFile(jarFile);
        try {
            InputStream input = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"));
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
                return output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            jar.close();
        }
    }

    private static final class ByteLoader extends URLClassLoader {
        private ByteLoader(URL jar) { super(new URL[] { jar }, MoBendsPoseReuseBenchmark.class.getClassLoader()); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}

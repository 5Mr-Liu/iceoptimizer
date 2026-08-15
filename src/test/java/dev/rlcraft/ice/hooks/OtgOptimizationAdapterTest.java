package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Structural regression coverage using the OTG binary from a Dregora reference installation. */
public class OtgOptimizationAdapterTest {
    private static final String REAL_JAR_SHA256 =
        "099661c56624efa68951f92b137ed38095f3a3fd8f9c783bfc01924972ab9d3c";
    private static final Sample[] SAMPLES = {
        sample("com.pg85.otg.customobjects.bo4.BO4", "otg-bo4-runtime",
            "764d8fac57a00c48d8285d9eda7f026ee6d0b14e03eb1a1592a46ce84292d8b1"),
        sample("com.pg85.otg.customobjects.bo4.BO4Config", "otg-bo4-column-layout",
            "4c82850218b5d3a93f27c803988ea9ad956e47fbb5d521ecb53412bc5e365437"),
        sample("com.pg85.otg.util.helpers.StringHelper", "otg-comma-parser",
            "fff1a1718671ed5f541c7d97c09c83cad905392f153e738b107b8b4254c98a09"),
        sample("com.pg85.otg.configuration.customobjects.CustomObjectResourcesManager",
            "otg-function-name-cache",
            "90728917888aa2a04aa938d0442ab1852fdb5798723a7704f474666b0830d534")
    };

    @Test
    public void realDregoraOtgJarMatchesTransformsAndVerifies() throws Exception {
        String configured = System.getProperty("ice.otg.jar", "").trim();
        Assume.assumeTrue("run with -PotgJar=<OpenTerrainGenerator-1.12.2-v9.7.jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        assertEquals(REAL_JAR_SHA256, sha256(file));

        JarFile jar = new JarFile(file);
        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { file.toURI().toURL() }, getClass().getClassLoader());
        try {
            for (Sample sample : SAMPLES) {
                byte[] original = read(jar, sample.className);
                assertEquals(sample.sha256, CoreClassFingerprint.sha256(original));
                TargetSpec target = OptimizerTargetCatalog.find(sample.className);
                assertNotNull(target);
                assertTrue(target.accepts(sample.sha256));
                assertEquals(sample.adapterId, target.adapterId);
                byte[] transformed = new IceClientOptimizerTransformer().transform(
                    sample.className, sample.className, original);
                assertFalse(sample.className, Arrays.equals(original, transformed));
                new ClassReader(transformed);
                Class<?> defined = new ByteLoader(dependencies).define(sample.className, transformed);
                assertEquals(sample.className, defined.getName());
                defined.getDeclaredMethods();
                defined.getDeclaredConstructors();
                verifyCalls(sample, transformed);
            }
        } finally {
            dependencies.close();
            jar.close();
        }
    }

    private static void verifyCalls(Sample sample, byte[] transformed) {
        if (sample.className.endsWith(".BO4")) {
            assertEquals(2, countCalls(transformed, Opcodes.INVOKESTATIC,
                OtgBo4Adapter.OPTIMIZER_BRIDGE, "isEnabled", OtgBo4Adapter.ENABLED_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
                OtgBo4Adapter.WRITER_OWNER, OtgBo4Adapter.WRITER_NAME,
                OtgBo4Adapter.WRITER_DESCRIPTOR));
        } else if (sample.className.endsWith(".BO4Config")) {
            assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
                OtgBo4ConfigAdapter.BRIDGE_OWNER, "columnBlockIndex",
                OtgBo4ConfigAdapter.BRIDGE_DESCRIPTOR));
            assertEquals(0, countCalls(transformed, Opcodes.INVOKESPECIAL,
                sample.className.replace('.', '/'), OtgBo4ConfigAdapter.INDEX_METHOD,
                OtgBo4ConfigAdapter.INDEX_DESCRIPTOR));
        } else if (sample.className.endsWith(".StringHelper")) {
            assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
                OtgStringHelperAdapter.BRIDGE_OWNER, "readCommaSeparatedString",
                OtgStringHelperAdapter.DESCRIPTOR));
            assertTrue(hasMethod(transformed, OtgStringHelperAdapter.ORIGINAL_METHOD,
                OtgStringHelperAdapter.DESCRIPTOR));
        } else {
            assertEquals(4, countCalls(transformed, Opcodes.INVOKESTATIC,
                OtgResourcesAdapter.BRIDGE_OWNER, "lowercaseFunctionName",
                OtgResourcesAdapter.DESCRIPTOR));
        }
    }

    private static int countCalls(byte[] bytes, final int opcode, final String owner,
                                  final String name, final String descriptor) {
        final int[] result = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int actualOpcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (actualOpcode == opcode && owner.equals(actualOwner)
                            && name.equals(actualName) && descriptor.equals(actualDescriptor)) result[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name, final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                if (name.equals(methodName) && descriptor.equals(methodDescriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
        return readFully(jar.getInputStream(entry));
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        } finally {
            input.close();
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static Sample sample(String className, String adapterId, String sha256) {
        return new Sample(className, adapterId, sha256);
    }

    private static final class Sample {
        private final String className;
        private final String adapterId;
        private final String sha256;
        private Sample(String className, String adapterId, String sha256) {
            this.className = className;
            this.adapterId = adapterId;
            this.sha256 = sha256;
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

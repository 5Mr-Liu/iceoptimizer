package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class KonkreteLocaleAdapterTest {
    private static final String CLASS_NAME =
        "de.keksuccino.konkrete.localization.LocaleUtils";
    private static final String CLASS_SHA =
        "1a7633b63d4bc2de11bcab0d0d8fc1523e462858835f4d962e59faff028ba5c5";

    @Test
    public void transformsReviewedKonkreteJarWhenProvided() throws Exception {
        String configured = System.getProperty("ice.konkrete.jar", "").trim();
        Assume.assumeTrue("run with -PkonkreteJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            byte[] original = read(jar, CLASS_NAME);
            assertEquals(CLASS_SHA, CoreClassFingerprint.sha256(original));
            TargetSpec target = OptimizerTargetCatalog.find(CLASS_NAME);
            assertNotNull(target);
            byte[] transformed = new KonkreteLocaleAdapter().transform(
                CLASS_NAME, original, target);
            assertFalse(Arrays.equals(original, transformed));
            assertEquals(1, countCalls(transformed, KonkreteLocaleAdapter.BRIDGE, "lookup"));
            assertEquals(1, countCalls(transformed, KonkreteLocaleAdapter.CLASS,
                KonkreteLocaleAdapter.ORIGINAL));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        assertNotNull(entry);
        InputStream input = jar.getInputStream(entry);
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

    private static int countCalls(byte[] bytes, final String owner, final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName, String actualDescriptor,
                                                          boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}

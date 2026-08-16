package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class SrpSpawnFilterAdapterTest {
    @Test
    public void transformsReviewedSrpMixinsHandlerWhenJarIsAvailable() throws Exception {
        String configured = System.getProperty("ice.srp.mixins.jar", "").trim();
        Assume.assumeTrue("run with -PsrpMixinsJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            String className = SrpSpawnFilterAdapter.TARGET.replace('/', '.');
            byte[] original = read(jar, className);
            assertEquals("515253b53e70aefe8162ee53b1bb4f7d7d93c6322536fa30c78066daf117c38c",
                CoreClassFingerprint.sha256(original));
            byte[] transformed = new IceOptimizerTransformer().transform(
                className, className, original);
            assertFalse(Arrays.equals(original, transformed));
            assertTrue(hasInterface(transformed, SrpSpawnFilterAdapter.CALLBACKS));
            assertTrue(hasField(transformed, SrpSpawnFilterAdapter.CALLBACK_FIELD,
                "L" + SrpSpawnFilterAdapter.CALLBACKS + ";"));
            assertTrue(hasMethod(transformed, SrpSpawnFilterAdapter.ORIGINAL_FILTER,
                SrpSpawnFilterAdapter.FILTER_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, SrpSpawnFilterAdapter.BRIDGE,
                "tryFilter", SrpSpawnFilterAdapter.BRIDGE_DESCRIPTOR));
            assertEquals(1, countCalls(transformed, SrpSpawnFilterAdapter.BRIDGE,
                "invalidate", "()V"));
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

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name, String signature,
                                        String superName, String[] interfaces) {
                if (interfaces != null) {
                    for (String value : interfaces) if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasField(byte[] bytes, final String expected,
                                    final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public FieldVisitor visitField(int access, String name, String actualDescriptor,
                                                     String signature, Object value) {
                if (expected.equals(name) && descriptor.equals(actualDescriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String actualDescriptor, String signature,
                                                       String[] exceptions) {
                if (expected.equals(name) && descriptor.equals(actualDescriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String methodName,
                                                       String methodDescriptor, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDescriptor, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}

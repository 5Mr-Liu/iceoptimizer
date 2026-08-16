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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ForgeBlockStateDirectAdapterTest {
    @Test
    public void transformsReviewedReflectorForgeFromActualOptifineJar() throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar=<jar>", !configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try {
            String className = "net.optifine.reflect.ReflectorForge";
            byte[] original = read(jar, className);
            TargetSpec target = target(className, "optifine-reflector-forge-direct-calls");
            byte[] transformed = OptimizerAdapterRegistry.find(target.adapterId)
                .transform(className, original, target);

            assertFalse(Arrays.equals(original, transformed));
            assertEquals(1, countCalls(transformed, ForgeBlockStateDirectAdapter.BRIDGE,
                "tryStateLightValue"));
            assertTrue(hasMethod(transformed,
                ForgeBlockStateDirectAdapter.ORIGINAL_REFLECTOR_LIGHT));
            new ClassReader(transformed);
        } finally {
            jar.close();
        }
    }

    @Test
    public void wrapsOptifineStateReflectorCallsAndRetainsFallbackMethods() throws Exception {
        String className = "net.minecraft.block.state.BlockStateContainer$StateImplementation";
        byte[] original = syntheticState(true);
        TargetSpec target = target(className, "optifine-blockstate-direct-calls");
        byte[] transformed = OptimizerAdapterRegistry.find(target.adapterId)
            .transform(className, original, target);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, ForgeBlockStateDirectAdapter.BRIDGE,
            "tryBlockLightValue"));
        assertEquals(1, countCalls(transformed, ForgeBlockStateDirectAdapter.BRIDGE,
            "tryDoesSideBlockRendering"));
        assertTrue(hasMethod(transformed, ForgeBlockStateDirectAdapter.ORIGINAL_STATE_LIGHT));
        assertTrue(hasMethod(transformed, ForgeBlockStateDirectAdapter.ORIGINAL_SIDE_RENDER));
        new ClassReader(transformed);
        assertEquals(className,
            new ByteLoader(getClass().getClassLoader()).define(className, transformed).getName());
    }

    @Test(expected = OptimizerAdapterSkippedException.class)
    public void skipsStateImplementationThatAlreadyUsesDirectForgeCalls() throws Exception {
        String className = "net.minecraft.block.state.BlockStateContainer$StateImplementation";
        TargetSpec target = target(className, "optifine-blockstate-direct-calls");
        OptimizerAdapterRegistry.find(target.adapterId)
            .transform(className, syntheticState(false), target);
    }

    private static TargetSpec target(String className, String adapterId) {
        for (TargetSpec value : OptimizerTargetCatalog.findAll(className)) {
            if (adapterId.equals(value.adapterId)) return value;
        }
        assertNotNull("missing target " + className + " / " + adapterId, null);
        return null;
    }

    private static byte[] syntheticState(boolean reflectorCalls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            ForgeBlockStateDirectAdapter.STATE, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
            null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
            "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor light = writer.visitMethod(Opcodes.ACC_PUBLIC, "getLightValue",
            "(Ljava/lang/Object;Ljava/lang/Object;)I", null, null);
        light.visitCode();
        if (reflectorCalls) {
            light.visitVarInsn(Opcodes.ALOAD, 0);
            light.visitInsn(Opcodes.ACONST_NULL);
            light.visitInsn(Opcodes.ACONST_NULL);
            light.visitMethodInsn(Opcodes.INVOKESTATIC, "net/optifine/reflect/Reflector",
                "callInt", "(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;"
                    + "[Ljava/lang/Object;)I", false);
        } else {
            light.visitInsn(Opcodes.ICONST_0);
        }
        light.visitInsn(Opcodes.IRETURN);
        light.visitMaxs(3, 3);
        light.visitEnd();

        MethodVisitor side = writer.visitMethod(Opcodes.ACC_PUBLIC,
            "doesSideBlockRendering",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", null, null);
        side.visitCode();
        if (reflectorCalls) {
            side.visitVarInsn(Opcodes.ALOAD, 0);
            side.visitInsn(Opcodes.ACONST_NULL);
            side.visitInsn(Opcodes.ACONST_NULL);
            side.visitMethodInsn(Opcodes.INVOKESTATIC, "net/optifine/reflect/Reflector",
                "callBoolean", "(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;"
                    + "[Ljava/lang/Object;)Z", false);
        } else {
            side.visitInsn(Opcodes.ICONST_0);
        }
        side.visitInsn(Opcodes.IRETURN);
        side.visitMaxs(3, 4);
        side.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
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
                                                          String actualName,
                                                          String actualDescriptor, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actualName,
                                                       String descriptor, String signature,
                                                       String[] exceptions) {
                if (name.equals(actualName)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

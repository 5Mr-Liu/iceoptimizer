package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.util.EnumFacing;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class BetterFoliageAoScratchAdapterTest {
    private static final String SYNTHETIC_INTERNAL =
        "dev/rlcraft/ice/hooks/SyntheticBetterFoliageAoFaceData";
    private static final String INT3_INTERNAL = "mods/octarinecore/common/Int3";
    private static final String REAL_CLASS = "mods.octarinecore.client.render.AoFaceData";
    private static final String REAL_ENTRY = "mods/octarinecore/client/render/AoFaceData.class";
    private static final String RUNTIME_CLASS_SHA256 =
        "4b797522a5a1c2683c4dd8434e8c7ffeeac0041f2cd6a50c5d6d5c9f1118123b";

    @Test
    public void reusesAndClearsOnlyTheReviewedScratchObjectsWhenEnabled() throws Exception {
        byte[] original = syntheticClass(false);
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        byte[] transformed = new BetterFoliageAoScratchAdapter().transform(
            target.className, original, target);

        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countField(transformed, BetterFoliageAoScratchAdapter.FLOAT_FIELD, "[F"));
        assertEquals(1, countField(transformed, BetterFoliageAoScratchAdapter.FLAGS_FIELD,
            "Ljava/util/BitSet;"));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            BetterFoliageAoScratchAdapter.BRIDGE_OWNER,
            BetterFoliageAoScratchAdapter.ENABLE_METHOD,
            BetterFoliageAoScratchAdapter.ENABLE_DESCRIPTOR));
        new ClassReader(transformed);

        boolean oldGlobal = OptimizerConfig.settings.enabled;
        boolean oldModule = OptimizerConfig.settings.betterFoliageAoScratch;
        try {
            OptimizerConfig.settings.enabled = true;
            OptimizerConfig.settings.betterFoliageAoScratch = true;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            OptimizerRegistry.targetObserved("chunk-mesh-ao", target.className, repeat('a', 64), true);
            OptimizerRegistry.patchInstalled("chunk-mesh-ao", target.className, repeat('a', 64));

            ByteLoader loader = new ByteLoader(getClass().getClassLoader());
            Class<?> int3 = loader.define(INT3_INTERNAL.replace('/', '.'), emptyClass(INT3_INTERNAL));
            Class<?> type = loader.define(target.className, transformed);
            Object instance = type.getConstructor(EnumFacing.class).newInstance(EnumFacing.NORTH);
            Object offset = int3.newInstance();
            Method update = type.getMethod("update", int3, Boolean.TYPE, Float.TYPE);
            Field floats = type.getField("lastFloats");
            Field flags = type.getField("lastFlags");
            Field cardinality = type.getField("seenCardinality");

            update.invoke(instance, offset, Boolean.FALSE, Float.valueOf(1.0F));
            float[] firstFloats = (float[]) floats.get(instance);
            BitSet firstFlags = (BitSet) flags.get(instance);
            assertEquals(0, cardinality.getInt(instance));
            assertTrue(firstFlags.get(2));

            update.invoke(instance, offset, Boolean.FALSE, Float.valueOf(1.0F));
            assertSame(firstFloats, floats.get(instance));
            assertSame(firstFlags, flags.get(instance));
            assertEquals("the reused BitSet must be empty before vanilla AO writes it",
                0, cardinality.getInt(instance));

            OptimizerConfig.settings.betterFoliageAoScratch = false;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            Object disabled = type.getConstructor(EnumFacing.class).newInstance(EnumFacing.SOUTH);
            update.invoke(disabled, offset, Boolean.FALSE, Float.valueOf(1.0F));
            Object disabledFirstFloats = floats.get(disabled);
            Object disabledFirstFlags = flags.get(disabled);
            update.invoke(disabled, offset, Boolean.FALSE, Float.valueOf(1.0F));
            assertNotSame(disabledFirstFloats, floats.get(disabled));
            assertNotSame(disabledFirstFlags, flags.get(disabled));
        } finally {
            OptimizerConfig.settings.enabled = oldGlobal;
            OptimizerConfig.settings.betterFoliageAoScratch = oldModule;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        }
    }

    @Test
    public void rejectsAChangedAoAllocationGraph() {
        TargetSpec target = targetFor(SYNTHETIC_INTERNAL.replace('/', '.'));
        try {
            new BetterFoliageAoScratchAdapter().transform(target.className,
                syntheticClass(true), target);
            fail("adapter must reject an extra float scratch allocation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("调用图变化"));
            assertTrue(expected.getMessage().contains("float[12]=2"));
        }
    }

    @Test
    public void transformsAndDefinesTheConfiguredRealBetterFoliageClassWhenAvailable()
        throws Exception {
        String configured = System.getProperty("ice.betterfoliage.jar", "").trim();
        Assume.assumeTrue("run with -PbetterFoliageJar=<jar> for the real-JAR integration test",
            !configured.isEmpty());
        File jarFile = new File(configured);
        Assume.assumeTrue("configured Better Foliage JAR must exist", jarFile.isFile());
        byte[] original = readEntry(jarFile, REAL_ENTRY);
        byte[] transformed = new IceClientOptimizerTransformer().transform(
            REAL_CLASS, REAL_CLASS, original);
        assertFalse(Arrays.equals(original, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            BetterFoliageAoScratchAdapter.BRIDGE_OWNER,
            BetterFoliageAoScratchAdapter.ENABLE_METHOD,
            BetterFoliageAoScratchAdapter.ENABLE_DESCRIPTOR));
        new ClassReader(transformed);

        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    @Test
    public void transformsTheReviewedPostTransformerRuntimeClassWhenAvailable() throws Exception {
        String configuredClass = System.getProperty("ice.betterfoliage.runtimeClass", "").trim();
        String configuredJar = System.getProperty("ice.betterfoliage.jar", "").trim();
        Assume.assumeTrue("run with -PbetterFoliageRuntimeClass=<class> and -PbetterFoliageJar=<jar>",
            !configuredClass.isEmpty() && !configuredJar.isEmpty());
        File classFile = new File(configuredClass);
        File jarFile = new File(configuredJar);
        Assume.assumeTrue(classFile.isFile() && jarFile.isFile());
        byte[] runtimeClass = readFully(new FileInputStream(classFile));
        assertEquals(RUNTIME_CLASS_SHA256, CoreClassFingerprint.sha256(runtimeClass));

        byte[] transformed = new IceClientOptimizerTransformer().transform(
            REAL_CLASS, REAL_CLASS, runtimeClass);
        assertFalse(Arrays.equals(runtimeClass, transformed));
        assertEquals(1, countCalls(transformed, Opcodes.INVOKESTATIC,
            BetterFoliageAoScratchAdapter.BRIDGE_OWNER,
            BetterFoliageAoScratchAdapter.ENABLE_METHOD,
            BetterFoliageAoScratchAdapter.ENABLE_DESCRIPTOR));
        new ClassReader(transformed);

        URLClassLoader dependencies = new URLClassLoader(
            new URL[] { jarFile.toURI().toURL() }, getClass().getClassLoader());
        try {
            ByteLoader loader = new ByteLoader(dependencies);
            assertEquals(REAL_CLASS, loader.define(REAL_CLASS, transformed).getName());
        } finally {
            dependencies.close();
        }
    }

    private static TargetSpec targetFor(String className) {
        return new TargetSpec(className, "chunk-mesh-ao", "betterfoliage-ao-scratch",
            Collections.<String>emptySet());
    }

    private static byte[] syntheticClass(boolean duplicateFloatAllocation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTHETIC_INTERNAL, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "lastFloats", "[F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "lastFlags", "Ljava/util/BitSet;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "seenCardinality", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "ao",
            "Lnet/minecraft/client/renderer/BlockModelRenderer$AmbientOcclusionFace;", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            BetterFoliageAoScratchAdapter.CONSTRUCTOR_DESCRIPTOR, null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 2);
        constructor.visitEnd();

        MethodVisitor update = writer.visitMethod(Opcodes.ACC_PUBLIC,
            BetterFoliageAoScratchAdapter.UPDATE_METHOD,
            BetterFoliageAoScratchAdapter.UPDATE_DESCRIPTOR, null, null);
        update.visitCode();
        update.visitIntInsn(Opcodes.BIPUSH, 12);
        update.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        update.visitVarInsn(Opcodes.ASTORE, 4);
        if (duplicateFloatAllocation) {
            update.visitIntInsn(Opcodes.BIPUSH, 12);
            update.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            update.visitInsn(Opcodes.POP);
        }
        update.visitTypeInsn(Opcodes.NEW, "java/util/BitSet");
        update.visitInsn(Opcodes.DUP);
        update.visitInsn(Opcodes.ICONST_3);
        update.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/BitSet", "<init>", "(I)V", false);
        update.visitVarInsn(Opcodes.ASTORE, 5);
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitVarInsn(Opcodes.ALOAD, 4);
        update.visitFieldInsn(Opcodes.PUTFIELD, SYNTHETIC_INTERNAL, "lastFloats", "[F");
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitVarInsn(Opcodes.ALOAD, 5);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "cardinality", "()I", false);
        update.visitFieldInsn(Opcodes.PUTFIELD, SYNTHETIC_INTERNAL, "seenCardinality", "I");
        org.objectweb.asm.Label skipAo = new org.objectweb.asm.Label();
        update.visitVarInsn(Opcodes.ILOAD, 2);
        update.visitJumpInsn(Opcodes.IFEQ, skipAo);
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitFieldInsn(Opcodes.GETFIELD, SYNTHETIC_INTERNAL, "ao",
            "Lnet/minecraft/client/renderer/BlockModelRenderer$AmbientOcclusionFace;");
        update.visitInsn(Opcodes.ACONST_NULL);
        update.visitInsn(Opcodes.ACONST_NULL);
        update.visitInsn(Opcodes.ACONST_NULL);
        update.visitInsn(Opcodes.ACONST_NULL);
        update.visitVarInsn(Opcodes.ALOAD, 4);
        update.visitVarInsn(Opcodes.ALOAD, 5);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/client/renderer/BlockModelRenderer$AmbientOcclusionFace",
            "func_187491_a",
            "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;[FLjava/util/BitSet;)V",
            false);
        update.visitLabel(skipAo);
        update.visitVarInsn(Opcodes.ALOAD, 5);
        update.visitInsn(Opcodes.ICONST_2);
        update.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "set", "(I)V", false);
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitVarInsn(Opcodes.ALOAD, 5);
        update.visitFieldInsn(Opcodes.PUTFIELD, SYNTHETIC_INTERNAL, "lastFlags", "Ljava/util/BitSet;");
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(7, 6);
        update.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int countField(byte[] bytes, final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public FieldVisitor visitField(int access, String candidate, String desc,
                                           String signature, Object value) {
                if (name.equals(candidate) && descriptor.equals(desc)) count[0]++;
                return super.visitField(access, candidate, desc, signature, value);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countCalls(byte[] bytes, final int opcode, final String owner,
                                  final String name, final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int actualOpcode, String actualOwner, String actualName,
                                                String actualDescriptor, boolean itf) {
                        if (actualOpcode == opcode && owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] readEntry(File file, String entryName) throws Exception {
        JarFile jar = new JarFile(file);
        try {
            JarEntry entry = jar.getJarEntry(entryName);
            assertTrue("reviewed class must exist", entry != null);
            return readFully(jar.getInputStream(entry));
        } finally {
            jar.close();
        }
    }

    private static byte[] readFully(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
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

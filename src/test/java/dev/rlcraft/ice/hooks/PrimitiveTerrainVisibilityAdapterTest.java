package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityMaskAccessor;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import net.minecraft.util.EnumFacing;

public final class PrimitiveTerrainVisibilityAdapterTest {
    @Test
    public void syntheticSrgLoopGetsOneTransactionalDecisionAndCompletionHook() {
        byte[] transformed = transform(PrimitiveTerrainVisibilityAdapter.Part.RENDER_GLOBAL,
            PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL, syntheticRenderGlobal());
        assertTrue(hasInterface(transformed,
            PrimitiveTerrainVisibilityAdapter.GLOBAL_ACCESS));
        assertEquals(1, countCalls(transformed,
            PrimitiveTerrainVisibilityAdapter.BRIDGE, "tryTraverse",
            "(Ljava/lang/Object;Ljava/util/Queue;Ljava/lang/Object;Ljava/lang/Object;IZZIZ)Z"));
        assertEquals(1, countCalls(transformed,
            PrimitiveTerrainVisibilityAdapter.BRIDGE, "afterTraversal",
            "(Ljava/lang/Object;Z)V"));
        assertTrue(hasMethod(transformed, "ice$getRenderChunkOffset",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ZI)Ljava/lang/Object;"));
        assertTrue(hasMethod(transformed, "ice$isInFrustum",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z"));
    }

    @Test
    public void realForgeSrgClassesAllAcceptTheCompleteVisibilityAdapter() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar=<forge-1.12.2-14.23.5.2860-srg.jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        Map<PrimitiveTerrainVisibilityAdapter.Part, String> classes =
            new EnumMap<PrimitiveTerrainVisibilityAdapter.Part, String>(
                PrimitiveTerrainVisibilityAdapter.Part.class);
        classes.put(PrimitiveTerrainVisibilityAdapter.Part.RENDER_GLOBAL,
            PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL);
        classes.put(PrimitiveTerrainVisibilityAdapter.Part.RENDER_INFO,
            PrimitiveTerrainVisibilityAdapter.RENDER_INFO);
        classes.put(PrimitiveTerrainVisibilityAdapter.Part.RENDER_CHUNK,
            PrimitiveTerrainVisibilityAdapter.RENDER_CHUNK);
        classes.put(PrimitiveTerrainVisibilityAdapter.Part.COMPILED_CHUNK,
            PrimitiveTerrainVisibilityAdapter.COMPILED_CHUNK);
        classes.put(PrimitiveTerrainVisibilityAdapter.Part.SET_VISIBILITY,
            PrimitiveTerrainVisibilityAdapter.SET_VISIBILITY);
        JarFile jar = new JarFile(file);
        try {
            for (Map.Entry<PrimitiveTerrainVisibilityAdapter.Part, String> entry
                : classes.entrySet()) {
                byte[] transformed = transform(entry.getKey(), entry.getValue(),
                    read(jar, entry.getValue()));
                verify(transformed, entry.getValue());
            }
        } finally {
            jar.close();
        }
    }

    @Test
    public void reviewedOptifineDequeTraversalAndPackedRenderInfoAreFullyAdapted()
        throws Exception {
        OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
        try {
            byte[] global = support.patchAndRemap("buy",
                "net.minecraft.client.renderer.RenderGlobal");
            byte[] transformedGlobal = transform(
                PrimitiveTerrainVisibilityAdapter.Part.RENDER_GLOBAL,
                PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL, global);
            verify(transformedGlobal, PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL);
            assertEquals(1, countCalls(transformedGlobal,
                PrimitiveTerrainVisibilityAdapter.BRIDGE, "tryTraverse",
                "(Ljava/lang/Object;Ljava/util/Queue;Ljava/lang/Object;"
                    + "Ljava/lang/Object;IZZIZ)Z"));
            assertTrue(hasMethod(transformedGlobal, "ice$appendRenderInfo",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
            assertTrue(hasMethod(transformedGlobal, "ice$renderInfosEntities",
                "()Ljava/util/List;"));
            assertEquals(1, countCallsInMethod(transformedGlobal, "ice$newRenderInfo",
                PrimitiveTerrainVisibilityAdapter.RENDER_CHUNK,
                "getRenderInfo", "()L" + PrimitiveTerrainVisibilityAdapter.RENDER_INFO
                    + ";"));
            assertEquals(1, countCallsInMethod(transformedGlobal, "ice$newRenderInfo",
                PrimitiveTerrainVisibilityAdapter.RENDER_INFO, "access$000",
                "(L" + PrimitiveTerrainVisibilityAdapter.RENDER_INFO + ";L"
                    + PrimitiveTerrainVisibilityAdapter.ENUM_FACING + ";I)V"));

            byte[] info = support.patchAndRemap("buy$a",
                "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation");
            byte[] transformedInfo = transform(
                PrimitiveTerrainVisibilityAdapter.Part.RENDER_INFO,
                PrimitiveTerrainVisibilityAdapter.RENDER_INFO, info);
            verify(transformedInfo, PrimitiveTerrainVisibilityAdapter.RENDER_INFO);
            assertTrue(hasInterface(transformedInfo,
                PrimitiveTerrainVisibilityAdapter.INFO_ACCESS));
            assertTrue(hasMethod(transformedInfo, "ice$pathDirections", "()B"));
            assertTrue(hasMethod(transformedInfo, "ice$counter", "()I"));
        } finally {
            support.close();
        }
    }

    @Test
    public void setVisibilityMirrorTracksSymmetricSetClearAndAllOperations()
        throws Exception {
        byte[] transformed = transform(PrimitiveTerrainVisibilityAdapter.Part.SET_VISIBILITY,
            PrimitiveTerrainVisibilityAdapter.SET_VISIBILITY, syntheticSetVisibility());
        Class<?> type = new ByteLoader(getClass().getClassLoader()).define(
            PrimitiveTerrainVisibilityAdapter.SET_VISIBILITY.replace('/', '.'), transformed);
        Object instance = type.newInstance();
        type.getMethod("func_178619_a", EnumFacing.class, EnumFacing.class,
            boolean.class).invoke(instance, EnumFacing.DOWN, EnumFacing.EAST, true);
        long expected = 1L << (EnumFacing.DOWN.ordinal() * 6 + EnumFacing.EAST.ordinal());
        expected |= 1L << (EnumFacing.EAST.ordinal() * 6 + EnumFacing.DOWN.ordinal());
        assertEquals(expected,
            ((TerrainVisibilityMaskAccessor) instance).ice$visibilityMask());
        type.getMethod("func_178619_a", EnumFacing.class, EnumFacing.class,
            boolean.class).invoke(instance, EnumFacing.DOWN, EnumFacing.EAST, false);
        assertEquals(0L, ((TerrainVisibilityMaskAccessor) instance).ice$visibilityMask());
        type.getMethod("func_178618_a", boolean.class).invoke(instance, true);
        assertEquals((1L << 36) - 1L,
            ((TerrainVisibilityMaskAccessor) instance).ice$visibilityMask());
        type.getMethod("func_178618_a", boolean.class).invoke(instance, false);
        assertEquals(0L, ((TerrainVisibilityMaskAccessor) instance).ice$visibilityMask());
    }

    private static byte[] transform(PrimitiveTerrainVisibilityAdapter.Part part,
                                    String className, byte[] original) {
        byte[] transformed = new PrimitiveTerrainVisibilityAdapter(part).transform(
            className, original, new TargetSpec(className, "modern-visibility-grid",
                "test", Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static void verify(byte[] bytes, String expectedName) throws Exception {
        ClassNode expanded = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(expanded, ClassReader.EXPAND_FRAMES);
        assertEquals(expectedName, expanded.name);
        for (MethodNode method : expanded.methods) {
            Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(new BasicVerifier());
            analyzer.analyze(expanded.name, method);
        }
    }

    private static byte[] syntheticRenderGlobal() {
        String global = PrimitiveTerrainVisibilityAdapter.RENDER_GLOBAL;
        String info = PrimitiveTerrainVisibilityAdapter.RENDER_INFO;
        String chunk = PrimitiveTerrainVisibilityAdapter.RENDER_CHUNK;
        String facing = PrimitiveTerrainVisibilityAdapter.ENUM_FACING;
        String camera = PrimitiveTerrainVisibilityAdapter.CAMERA;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, global, null,
            "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "field_72755_R", "Ljava/util/List;",
            null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "field_175008_n",
            "L" + PrimitiveTerrainVisibilityAdapter.VIEW_FRUSTUM + ";", null, null)
            .visitEnd();
        MethodVisitor setup = writer.visitMethod(Opcodes.ACC_PUBLIC,
            PrimitiveTerrainVisibilityAdapter.SETUP_TERRAIN,
            PrimitiveTerrainVisibilityAdapter.SETUP_TERRAIN_DESC, null, null);
        setup.visitCode();
        setup.visitLdcInsn("iteration");
        setup.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Profiler", "func_76320_a",
            "(Ljava/lang/String;)V", false);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label exit = new org.objectweb.asm.Label();
        org.objectweb.asm.Label pathDone = new org.objectweb.asm.Label();
        setup.visitLabel(loop);
        setup.visitVarInsn(Opcodes.ALOAD, 23);
        setup.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "isEmpty",
            "()Z", true);
        setup.visitJumpInsn(Opcodes.IFNE, exit);
        setup.visitVarInsn(Opcodes.ALOAD, 23);
        setup.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "poll",
            "()Ljava/lang/Object;", true);
        setup.visitTypeInsn(Opcodes.CHECKCAST, info);
        setup.visitVarInsn(Opcodes.ASTORE, 25);
        setup.visitInsn(Opcodes.ACONST_NULL);
        setup.visitTypeInsn(Opcodes.CHECKCAST, chunk);
        setup.visitVarInsn(Opcodes.ASTORE, 27);
        setup.visitInsn(Opcodes.ACONST_NULL);
        setup.visitTypeInsn(Opcodes.CHECKCAST, facing);
        setup.visitVarInsn(Opcodes.ASTORE, 30);
        setup.visitVarInsn(Opcodes.ALOAD, 0);
        setup.visitVarInsn(Opcodes.ALOAD, 21);
        setup.visitVarInsn(Opcodes.ALOAD, 27);
        setup.visitVarInsn(Opcodes.ALOAD, 30);
        setup.visitMethodInsn(Opcodes.INVOKESPECIAL, global, "func_181562_a",
            "(Lnet/minecraft/util/math/BlockPos;L" + chunk + ";L" + facing
                + ";)L" + chunk + ";", false);
        setup.visitVarInsn(Opcodes.ASTORE, 28);
        setup.visitVarInsn(Opcodes.ILOAD, 24);
        setup.visitJumpInsn(Opcodes.IFEQ, pathDone);
        setup.visitVarInsn(Opcodes.ALOAD, 25);
        setup.visitVarInsn(Opcodes.ALOAD, 30);
        setup.visitMethodInsn(Opcodes.INVOKEVIRTUAL, facing, "func_176734_d",
            "()L" + facing + ";", false);
        setup.visitMethodInsn(Opcodes.INVOKEVIRTUAL, info, "func_189560_a",
            "(L" + facing + ";)Z", false);
        setup.visitInsn(Opcodes.POP);
        setup.visitLabel(pathDone);
        setup.visitVarInsn(Opcodes.ALOAD, 28);
        setup.visitVarInsn(Opcodes.ILOAD, 5);
        setup.visitMethodInsn(Opcodes.INVOKEVIRTUAL, chunk, "func_178577_a", "(I)Z",
            false);
        setup.visitInsn(Opcodes.POP);
        setup.visitVarInsn(Opcodes.ALOAD, 4);
        setup.visitVarInsn(Opcodes.ALOAD, 28);
        setup.visitFieldInsn(Opcodes.GETFIELD, chunk, "field_178591_c",
            "Lnet/minecraft/util/math/AxisAlignedBB;");
        setup.visitMethodInsn(Opcodes.INVOKEINTERFACE, camera, "func_78546_a",
            "(Lnet/minecraft/util/math/AxisAlignedBB;)Z", true);
        setup.visitInsn(Opcodes.POP);
        setup.visitJumpInsn(Opcodes.GOTO, loop);
        setup.visitLabel(exit);
        setup.visitInsn(Opcodes.RETURN);
        setup.visitMaxs(0, 31);
        setup.visitEnd();

        MethodVisitor offset = writer.visitMethod(Opcodes.ACC_PRIVATE, "func_181562_a",
            "(Lnet/minecraft/util/math/BlockPos;L" + chunk + ";L" + facing
                + ";)L" + chunk + ";", null, null);
        offset.visitCode();
        offset.visitInsn(Opcodes.ACONST_NULL);
        offset.visitInsn(Opcodes.ARETURN);
        offset.visitMaxs(1, 4);
        offset.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticSetVisibility() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            PrimitiveTerrainVisibilityAdapter.SET_VISIBILITY, null,
            "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
            "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor one = writer.visitMethod(Opcodes.ACC_PUBLIC, "func_178619_a",
            "(Lnet/minecraft/util/EnumFacing;Lnet/minecraft/util/EnumFacing;Z)V",
            null, null);
        one.visitCode();
        one.visitInsn(Opcodes.RETURN);
        one.visitMaxs(0, 4);
        one.visitEnd();
        MethodVisitor all = writer.visitMethod(Opcodes.ACC_PUBLIC, "func_178618_a",
            "(Z)V", null, null);
        all.visitCode();
        all.visitInsn(Opcodes.RETURN);
        all.visitMaxs(0, 2);
        all.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean hasInterface(byte[] bytes, final String expected) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public void visit(int version, int access, String name,
                                        String signature, String superName,
                                        String[] interfaces) {
                if (interfaces != null) for (String value : interfaces) {
                    if (expected.equals(value)) found[0] = true;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean hasMethod(byte[] bytes, final String name,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String actual,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (name.equals(actual) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name, final String descriptor) {
        return countCallsInMethod(bytes, null, owner, name, descriptor);
    }

    private static int countCallsInMethod(byte[] bytes, final String selected,
                                          final String owner, final String name,
                                          final String descriptor) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (selected != null && !selected.equals(method)) return null;
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String actualOwner,
                                                          String actualName,
                                                          String actualDesc, boolean itf) {
                        if (owner.equals(actualOwner) && name.equals(actualName)
                            && descriptor.equals(actualDesc)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        if (entry == null) throw new IllegalStateException("missing " + className);
        InputStream input = jar.getInputStream(entry);
        try {
            byte[] bytes = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("truncated " + className);
                offset += count;
            }
            return bytes;
        } finally {
            input.close();
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

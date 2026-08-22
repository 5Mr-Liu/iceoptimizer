package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ParticleRenderAdapterTest {
    @Test
    public void wrapsOnlyTheStandardParticleManagerEmitter() throws Exception {
        byte[] transformed = transform(new ParticleRenderAdapter(
            ParticleRenderAdapter.Part.MANAGER), ParticleRenderAdapter.MANAGER,
            syntheticManager());
        assertTrue(hasMethod(transformed, ParticleRenderAdapter.ORIGINAL,
            ParticleRenderAdapter.RENDER_MANAGER_DESC));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "render"));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "begin"));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "end"));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "abort"));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "beginBuffer"));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "draw"));
        assertTrue(hasMethod(transformed, ParticleRenderAdapter.ORIGINAL_LIT,
            ParticleRenderAdapter.RENDER_LIT_DESC));
        assertEquals(1, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "beginLit"));
        assertEquals(2, countCalls(transformed, ParticleRenderAdapter.BRIDGE,
            "endLit"));
    }

    @Test
    public void injectsReadOnlyVanillaParticleAccessors() throws Exception {
        byte[] transformed = transform(new ParticleRenderAdapter(
            ParticleRenderAdapter.Part.PARTICLE_ACCESS), ParticleRenderAdapter.PARTICLE,
            syntheticParticle());
        assertTrue(hasInterface(transformed, ParticleRenderAdapter.ACCESS));
        assertTrue(hasMethod(transformed, "ice$previousX", "()D"));
        assertTrue(hasMethod(transformed, "ice$particleTexture",
            "()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"));
        assertTrue(hasMethod(transformed, "ice$previousParticleAngle", "()F"));
    }

    @Test
    public void marksEveryReviewedFbpInternalFlushAndRestart() throws Exception {
        byte[] transformed = transform(new FbpParticleAdapter(),
            FbpParticleAdapter.BLOCK, syntheticFbp(FbpParticleAdapter.BLOCK));
        assertEquals(2, countCalls(transformed, FbpParticleAdapter.BRIDGE, "draw"));
        assertEquals(2, countCalls(transformed, FbpParticleAdapter.BRIDGE, "begin"));
        assertEquals(1, countCalls(transformed, FbpParticleAdapter.BRIDGE, "enter"));
        assertEquals(1, countCalls(transformed, FbpParticleAdapter.BRIDGE, "exit"));
        assertEquals(1, countCalls(transformed, FbpParticleAdapter.BRIDGE, "abort"));
        assertTrue(hasMethod(transformed, FbpParticleAdapter.ORIGINAL,
            FbpParticleAdapter.RENDER_DESC));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsFbpBoundaryReorderingEvenWhenCountsStillMatch()
        throws Exception {
        transform(new FbpParticleAdapter(), FbpParticleAdapter.BLOCK,
            syntheticFbp(FbpParticleAdapter.BLOCK, true));
    }

    @Test
    public void transformsRealFbpBoundaryClassesWhenFixtureIsProvided() throws Exception {
        String configured = System.getProperty("ice.fbp.jar");
        Assume.assumeTrue("run with -PfbpJar=<jar>", configured != null);
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            assertRealFbp(jar, "com.TominoCZ.FBP.particle.FBPParticleBlock");
            assertRealFbp(jar, "com.TominoCZ.FBP.particle.FBPParticleFlame");
        } finally {
            jar.close();
        }
    }

    @Test
    public void transformsProductionSrgParticleClassesWhenFixtureIsProvided()
        throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar");
        Assume.assumeTrue("run with -PminecraftSrgJar=<jar>", configured != null);
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] manager = read(jar, "net.minecraft.client.particle.ParticleManager");
            byte[] managerResult = transform(new ParticleRenderAdapter(
                ParticleRenderAdapter.Part.MANAGER), ParticleRenderAdapter.MANAGER,
                manager);
            assertEquals(1, countCalls(managerResult, ParticleRenderAdapter.BRIDGE,
                "render"));
            assertEquals(1, countCalls(managerResult, ParticleRenderAdapter.BRIDGE,
                "beginBuffer"));
            assertEquals(1, countCalls(managerResult, ParticleRenderAdapter.BRIDGE,
                "draw"));
            assertEquals(1, countCalls(managerResult, ParticleRenderAdapter.BRIDGE,
                "beginLit"));
            byte[] particle = read(jar, "net.minecraft.client.particle.Particle");
            byte[] particleResult = transform(new ParticleRenderAdapter(
                ParticleRenderAdapter.Part.PARTICLE_ACCESS),
                ParticleRenderAdapter.PARTICLE, particle);
            assertTrue(hasInterface(particleResult, ParticleRenderAdapter.ACCESS));
        } finally {
            jar.close();
        }
    }

    private static void assertRealFbp(JarFile jar, String className) throws Exception {
        byte[] original = read(jar, className);
        byte[] transformed = new FbpParticleAdapter().transform(className,
            original, new TargetSpec(className, "fbp-particle-adapter", "test",
                Collections.<String>emptySet()));
        assertFalse(Arrays.equals(original, transformed));
        new ClassReader(transformed);
        assertEquals(2, countCalls(transformed, FbpParticleAdapter.BRIDGE, "draw"));
        assertEquals(2, countCalls(transformed, FbpParticleAdapter.BRIDGE, "begin"));
    }

    private static byte[] transform(OptimizerBytecodeAdapter adapter,
                                    String className, byte[] original) throws Exception {
        byte[] transformed = adapter.transform(className, original,
            new TargetSpec(className, "modern-particle-backend", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] syntheticManager() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            ParticleRenderAdapter.MANAGER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            ParticleRenderAdapter.RENDER_MANAGER,
            ParticleRenderAdapter.RENDER_MANAGER_DESC, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitIntInsn(Opcodes.BIPUSH, 7);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            ParticleRenderAdapter.BUFFER, "func_181668_a",
            "(IL" + ParticleRenderAdapter.VERTEX_FORMAT + ";)V", false);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.FLOAD, 2);
        for (int i = 0; i < 5; i++) method.visitInsn(Opcodes.FCONST_0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            ParticleRenderAdapter.PARTICLE, ParticleRenderAdapter.RENDER_PARTICLE,
            ParticleRenderAdapter.RENDER_PARTICLE_DESC, false);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            ParticleRenderAdapter.TESSELLATOR, "func_78381_a", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        MethodVisitor lit = writer.visitMethod(Opcodes.ACC_PUBLIC,
            ParticleRenderAdapter.RENDER_LIT,
            ParticleRenderAdapter.RENDER_LIT_DESC, null, null);
        lit.visitCode();
        lit.visitInsn(Opcodes.RETURN);
        lit.visitMaxs(0, 0);
        lit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticParticle() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            ParticleRenderAdapter.PARTICLE, null, "java/lang/Object", null);
        field(writer, "field_187123_c", "D");
        field(writer, "field_187124_d", "D");
        field(writer, "field_187125_e", "D");
        field(writer, "field_187126_f", "D");
        field(writer, "field_187127_g", "D");
        field(writer, "field_187128_h", "D");
        field(writer, "field_94054_b", "I");
        field(writer, "field_94055_c", "I");
        field(writer, "field_70544_f", "F");
        field(writer, "field_70552_h", "F");
        field(writer, "field_70553_i", "F");
        field(writer, "field_70551_j", "F");
        field(writer, "field_82339_as", "F");
        field(writer, "field_187119_C", "L" + ParticleRenderAdapter.TEXTURE + ";");
        field(writer, "field_190014_F", "F");
        field(writer, "field_190015_G", "F");
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC,
            ParticleRenderAdapter.RENDER_PARTICLE,
            ParticleRenderAdapter.RENDER_PARTICLE_DESC, null, null);
        render.visitCode();
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticFbp(String owner) {
        return syntheticFbp(owner, false);
    }

    private static byte[] syntheticFbp(String owner, boolean beginFirst) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null,
            FbpParticleAdapter.PARTICLE, null);
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PUBLIC,
            FbpParticleAdapter.RENDER, FbpParticleAdapter.RENDER_DESC, null, null);
        render.visitCode();
        for (int i = 0; i < 2; i++) {
            if (beginFirst) emitFbpBegin(render);
            emitFbpDraw(render);
            if (!beginFirst) emitFbpBegin(render);
        }
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitFbpDraw(MethodVisitor render) {
        render.visitInsn(Opcodes.ACONST_NULL);
        render.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            FbpParticleAdapter.TESSELLATOR, "func_78381_a", "()V", false);
    }

    private static void emitFbpBegin(MethodVisitor render) {
        render.visitInsn(Opcodes.ACONST_NULL);
        render.visitIntInsn(Opcodes.BIPUSH, 7);
        render.visitInsn(Opcodes.ACONST_NULL);
        render.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            FbpParticleAdapter.BUFFER, "func_181668_a",
            "(IL" + FbpParticleAdapter.VERTEX_FORMAT + ";)V", false);
    }

    private static void field(ClassWriter writer, String name, String descriptor) {
        FieldVisitor field = writer.visitField(Opcodes.ACC_PROTECTED, name,
            descriptor, null, null);
        field.visitEnd();
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

    private static boolean hasMethod(byte[] bytes, final String expected,
                                     final String descriptor) {
        final boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String name,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                if (expected.equals(name) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static int countCalls(byte[] bytes, final String owner,
                                  final String name) {
        final int[] count = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            @Override public MethodVisitor visitMethod(int access, String method,
                                                       String desc, String signature,
                                                       String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override public void visitMethodInsn(int opcode, String callOwner,
                                                          String callName,
                                                          String callDesc,
                                                          boolean itf) {
                        if (owner.equals(callOwner) && name.equals(callName)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
        if (entry == null) throw new IllegalArgumentException(className);
        InputStream input = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}

package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class OptifineShaderSourceAdapterTest {
    @Test
    public void replacesOnlyThreeReviewedResolvedSourceSubmissions() {
        byte[] transformed = transform(synthetic(true));
        assertEquals(3, calls(transformed,
            OptifineShaderSourceAdapter.BOOTSTRAP, "submit"));
        assertEquals(0, calls(transformed,
            "org/lwjgl/opengl/ARBShaderObjects", "glShaderSourceARB"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsChangedStageGraph() {
        transform(synthetic(false));
    }

    @Test
    public void transformsReviewedOptifineG5WhenFixtureIsProvided() throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar=<OptiFine G5 jar>",
            !configured.isEmpty());
        File file = new File(configured);
        Assume.assumeTrue(file.isFile());
        JarFile jar = new JarFile(file);
        try {
            byte[] original = read(jar, OptifineShaderSourceAdapter.SHADERS);
            byte[] transformed = transform(original);
            assertFalse(Arrays.equals(original, transformed));
            assertEquals(3, calls(transformed,
                OptifineShaderSourceAdapter.BOOTSTRAP, "submit"));
        } finally {
            jar.close();
        }
    }

    private static byte[] transform(byte[] original) {
        byte[] transformed = new OptifineShaderSourceAdapter().transform(
            "net.optifine.shaders.Shaders", original,
            new TargetSpec("net.optifine.shaders.Shaders",
                "optifine-shader-bridge", "test",
                Collections.<String>emptySet()));
        new ClassReader(transformed);
        return transformed;
    }

    private static byte[] synthetic(boolean complete) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS
            | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            OptifineShaderSourceAdapter.SHADERS, null, "java/lang/Object", null);
        MethodVisitor reader = writer.visitMethod(Opcodes.ACC_PRIVATE
            | Opcodes.ACC_STATIC, "getShaderReader",
            "(Ljava/lang/String;)Ljava/io/Reader;", null, null);
        reader.visitCode();
        reader.visitInsn(Opcodes.ACONST_NULL);
        reader.visitInsn(Opcodes.ARETURN);
        reader.visitMaxs(0, 0);
        reader.visitEnd();
        stage(writer, "createVertShader", true);
        stage(writer, "createGeomShader", true);
        stage(writer, "createFragShader", complete);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void stage(ClassWriter writer, String name, boolean includes) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE
            | Opcodes.ACC_STATIC, name, OptifineShaderSourceAdapter.METHOD_DESC,
            null, null);
        method.visitCode();
        method.visitLdcInsn(Integer.valueOf(35633));
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            "org/lwjgl/opengl/ARBShaderObjects", "glCreateShaderObjectARB",
            "(I)I", false);
        method.visitVarInsn(Opcodes.ISTORE, 2);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            OptifineShaderSourceAdapter.SHADERS, "getShaderReader",
            "(Ljava/lang/String;)Ljava/io/Reader;", false);
        method.visitInsn(Opcodes.POP);
        if (includes) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/optifine/shaders/config/ShaderPackParser", "resolveIncludes",
                "(Ljava/io/BufferedReader;Ljava/lang/String;"
                    + "Lnet/optifine/shaders/IShaderPack;ILjava/util/List;I)"
                    + "Ljava/io/BufferedReader;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitLdcInsn("void main(){}\n");
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            "org/lwjgl/opengl/ARBShaderObjects", "glShaderSourceARB",
            "(ILjava/lang/CharSequence;)V", false);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
            "org/lwjgl/opengl/ARBShaderObjects", "glCompileShaderARB", "(I)V",
            false);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitLdcInsn(Integer.valueOf(35713));
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL20",
            "glGetShaderi", "(II)I", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static int calls(byte[] bytes, final String owner, final String name) {
        final int[] count = { 0 };
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
        }, 0);
        return count[0];
    }

    private static byte[] read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        Assume.assumeNotNull(entry);
        InputStream input = jar.getInputStream(entry);
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
}

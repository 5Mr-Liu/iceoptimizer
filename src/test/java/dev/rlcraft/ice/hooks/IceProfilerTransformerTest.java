package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class IceProfilerTransformerTest {
    @Test
    public void patchesKnownMappedWorldMethodAndProducesReadableBytecode() throws Exception {
        String className = "net.minecraft.world.World";
        String resource = "/" + className.replace('.', '/') + ".class";
        InputStream input = IceProfilerTransformerTest.class.getResourceAsStream(resource);
        assertTrue("mapped World.class must be present on the test runtime classpath", input != null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        input.close();
        byte[] original = output.toByteArray();
        byte[] transformed = new IceProfilerTransformer().transform(className, className, original);
        assertNotEquals(original.length, transformed.length);
        new ClassReader(transformed); // structural parse must succeed
        assertTrue(new String(transformed, StandardCharsets.ISO_8859_1).contains("ProbeBridge"));
    }

    @Test
    public void recognizesProductionObfuscatedWorldSignature() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "amu", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        MethodVisitor target = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(Lvg;Z)V", null, null);
        target.visitCode(); target.visitInsn(Opcodes.RETURN); target.visitMaxs(0, 3); target.visitEnd();
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        byte[] transformed = new IceProfilerTransformer().transform("amu", "net.minecraft.world.World", original);
        assertNotEquals(original.length, transformed.length);
        assertTrue(new String(transformed, StandardCharsets.ISO_8859_1).contains("ProbeBridge"));
        ByteLoader loader = new ByteLoader(getClass().getClassLoader());
        Class<?> entity = loader.define("vg", emptyClass("vg"));
        Class<?> world = loader.define("amu", transformed);
        Object instance = world.newInstance();
        world.getMethod("a", entity, boolean.class).invoke(instance, entity.newInstance(), Boolean.TRUE);
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}

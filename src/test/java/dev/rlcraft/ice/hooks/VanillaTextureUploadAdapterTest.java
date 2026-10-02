package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class VanillaTextureUploadAdapterTest {
    @Test public void transformsTheActualNamedTextureUtilWithoutReplacingPixelUploads() throws Exception {
        byte[] original = read(getClass().getResourceAsStream(
            "/net/minecraft/client/renderer/texture/TextureUtil.class"));
        verify(original);
    }

    @Test public void transformsTheConfiguredForgeSrgTextureUtil() throws Exception {
        String configured = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue(!configured.isEmpty());
        JarFile jar = new JarFile(new File(configured));
        try { verify(read(jar.getInputStream(jar.getJarEntry(VanillaTextureUploadAdapter.TARGET + ".class")))); }
        finally { jar.close(); }
    }

    @Test public void transformsTheActualOptifineG5PostPatchTextureUtil() throws Exception {
        OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
        try { verify(support.patchAndRemap("cdt", VanillaTextureUploadAdapter.TARGET.replace('/', '.'))); }
        finally { support.close(); }
    }

    @Test(expected = IllegalStateException.class)
    public void refusesUnknownCallbacksInsideTheUpload() throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(read(getClass().getResourceAsStream(
            "/net/minecraft/client/renderer/texture/TextureUtil.class"))).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (VanillaTextureUploadAdapter.TARGET_DESCRIPTOR.equals(method.desc)) {
                method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "foreign/TextureCallback", "changeBinding", "()V", false));
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        adapt(writer.toByteArray());
    }

    private static void verify(byte[] original) {
        byte[] transformed = adapt(original);
        assertEquals(0, count(original, VanillaTextureUploadAdapter.BRIDGE, "skipParameters"));
        assertEquals(1, count(transformed, VanillaTextureUploadAdapter.BRIDGE, "skipParameters"));
        assertEquals(count(original, "net/minecraft/client/renderer/GlStateManager", null),
            count(transformed, "net/minecraft/client/renderer/GlStateManager", null));
        assertSame("a second transform must not add a second guard", transformed, adapt(transformed));
        new ClassReader(transformed);
    }

    private static byte[] adapt(byte[] original) {
        TargetSpec target = new TargetSpec(VanillaTextureUploadAdapter.TARGET.replace('/', '.'),
            "foamfix-texture-upload", "vanilla-texture-parameters", Collections.<String>emptySet());
        return new VanillaTextureUploadAdapter().transform(target.className, original, target);
    }

    private static int count(byte[] bytes, String owner, String name) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        int count = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && (name == null || name.equals(call.name))) count++;
            }
        }
        return count;
    }

    private static byte[] read(InputStream input) throws Exception {
        assertNotNull(input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }
}

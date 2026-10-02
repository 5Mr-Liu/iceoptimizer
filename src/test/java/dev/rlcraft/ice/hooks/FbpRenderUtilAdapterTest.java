package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class FbpRenderUtilAdapterTest {
    @Test public void realDiggingCallsTheReviewedHelperAndBlockFlameDoNot() throws Exception {
        JarFile jar = fixture();
        try {
            assertEquals(1, calls(read(jar,
                "com/TominoCZ/FBP/particle/FBPParticleDigging"),
                FbpRenderUtilAdapter.OWNER, "renderCubeShaded_S"));
            for (String name : new String[] { "FBPParticleBlock", "FBPParticleFlame" }) {
                assertEquals(0, calls(read(jar, "com/TominoCZ/FBP/particle/" + name),
                    FbpRenderUtilAdapter.OWNER, "renderCubeShaded_S"));
                assertEquals(0, calls(read(jar, "com/TominoCZ/FBP/particle/" + name),
                    FbpRenderUtilAdapter.OWNER, "renderCubeShaded_WH"));
            }
        } finally { jar.close(); }
    }

    @Test public void realHelpersKeepLegacyBodiesAndOnlyRemoveDuplicateEmptyPrefix()
        throws Exception {
        JarFile jar = fixture();
        try {
            byte[] original = read(jar, FbpRenderUtilAdapter.OWNER);
            ClassNode before = node(original);
            ClassNode after = node(transform(original));
            for (String name : new String[] { "renderCubeShaded_S", "renderCubeShaded_WH" }) {
                MethodNode legacy = find(after, "ice$" + name + "Legacy");
                MethodNode optimized = find(after, "ice$" + name + "Optimized");
                assertEquals(executable(find(before, name)), executable(legacy));
                assertEquals(3, calls(legacy, FbpRenderUtilAdapter.TESSELLATOR, "func_78381_a"));
                assertEquals(2, calls(optimized, "net/minecraft/client/renderer/texture/TextureManager",
                    "func_110577_a"));
                assertEquals(2, calls(optimized, FbpRenderUtilAdapter.BUFFER, "func_181668_a"));
                assertEquals(1, calls(optimized, FbpRenderUtilAdapter.BRIDGE, "drawBoundary"));
                assertEquals(1, calls(optimized, FbpRenderUtilAdapter.BRIDGE, "drawCube"));
                assertEquals(1, calls(find(after, name), FbpRenderUtilAdapter.BRIDGE, "isEnabled"));
                assertEquals(1, calls(find(after, name), FbpRenderUtilAdapter.OWNER,
                    "ice$" + name + "Legacy"));
            }
        } finally { jar.close(); }
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAnAlteredDuplicateTextureBoundary() throws Exception {
        JarFile jar = fixture();
        byte[] original;
        try { original = read(jar, FbpRenderUtilAdapter.OWNER); }
        finally { jar.close(); }
        ClassNode node = node(original);
        int textures = 0;
        for (AbstractInsnNode instruction : find(node, "renderCubeShaded_S").instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.owner.equals("net/minecraft/client/renderer/texture/TextureMap")
                    && ++textures == 2) field.name = "changedAtlas";
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        transform(writer.toByteArray());
    }

    @Test(expected = IllegalStateException.class)
    public void refusesDoubleInstallation() throws Exception {
        JarFile jar = fixture();
        byte[] original;
        try { original = read(jar, FbpRenderUtilAdapter.OWNER); }
        finally { jar.close(); }
        transform(transform(original));
    }

    static byte[] transform(byte[] bytes) {
        return new FbpRenderUtilAdapter().transform(FbpRenderUtilAdapter.OWNER, bytes,
            new TargetSpec(FbpRenderUtilAdapter.OWNER.replace('/', '.'),
                "fbp-cube-submission", "test", Collections.<String>emptySet()));
    }

    static JarFile fixture() throws Exception {
        String configured = System.getProperty("ice.fbp.jar");
        Assume.assumeTrue("run with -PfbpJar=<jar>", configured != null
            && new File(configured).isFile());
        return new JarFile(configured);
    }

    static byte[] read(JarFile jar, String name) throws Exception {
        InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096];
            for (int count; (count = input.read(block)) >= 0;) bytes.write(block, 0, count);
            return bytes.toByteArray();
        } finally { input.close(); }
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    static MethodNode find(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (method.name.equals(name)) return method;
        throw new AssertionError(name);
    }

    private static int calls(byte[] bytes, String owner, String name) {
        int count = 0;
        for (MethodNode method : node(bytes).methods) count += calls(method, owner, name);
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }

    private static int executable(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) count++;
        }
        return count;
    }
}

package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Verify the actual branch graph, including the OptiFine patched inner class. */
public final class HudStateMutationBarrierTest {
    @Test public void realForgeBooleanStateKeepsNoOpAndMutationOrder() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeTrue(new File(path).isFile());
        try (JarFile jar = new JarFile(path)) {
            String owner = GlStateTrackingAdapter.BOOLEAN_STATE;
            try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                verifyBoolean(new ClassReader(input));
            }
        }
    }

    @Test public void realOptifineBooleanStateKeepsNoOpAndMutationOrder() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            verifyBoolean(new ClassReader(support.patchAndRemap("bus$c",
                GlStateTrackingAdapter.BOOLEAN_STATE.replace('/', '.'))));
        }
    }

    @Test public void realOptifineManagerPreservesConditionalNativeBoundaries() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            byte[] original = support.patchAndRemap("bus", GlStateTrackingAdapter.GL_STATE_MANAGER.replace('/', '.'));
            byte[] transformed = new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.GL_STATE_MANAGER)
                .transform(GlStateTrackingAdapter.GL_STATE_MANAGER, original,
                    new TargetSpec(GlStateTrackingAdapter.GL_STATE_MANAGER, "modern-visibility-hzb",
                        "test", Collections.<String>emptySet()));
            ClassNode node = new ClassNode(Opcodes.ASM5);
            new ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES);
            for (String wrapper : new String[] {"func_179112_b", "func_179120_a"}) {
                for (MethodNode method : node.methods) if (method.name.equals(wrapper))
                    for (AbstractInsnNode instruction : method.instructions.toArray())
                        if (instruction instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) instruction;
                            if (call.owner.startsWith("net/optifine/shaders/uniform/")
                                && call.name.equals("setValue")) {
                                assertTrue(call.getPrevious() instanceof MethodInsnNode);
                                assertEquals(GlStateTrackingAdapter.HUD_BOOTSTRAP,
                                    ((MethodInsnNode) call.getPrevious()).owner);
                            }
                        }
            }
            verifyActiveTextureDelegate(support.patchAndRemap("cii",
                GlStateTrackingAdapter.OPENGL_HELPER.replace('/', '.')));
            // These wrappers own no native mutation: BooleanState owns its
            // conditional branch; resetColor changes only Minecraft's cache.
            for (String wrapper : new String[] {"func_179147_l", "func_179084_k", "func_179117_G"}) {
                for (MethodNode method : node.methods) if (method.name.equals(wrapper))
                    for (AbstractInsnNode instruction : method.instructions.toArray())
                        if (instruction instanceof MethodInsnNode)
                            assertNotEquals(GlStateTrackingAdapter.HUD_BOOTSTRAP,
                                ((MethodInsnNode) instruction).owner);
            }
            try (URLClassLoader optifine = new URLClassLoader(new URL[] {
                new File(System.getProperty("ice.optifine.jar")).toURI().toURL()
            }, getClass().getClassLoader())) {
                assertTrue(new ByteLoader(optifine).define(transformed).getDeclaredMethods().length > 0);
            }
        }
    }

    @Test public void forgeActiveTextureDelegateDrainsBeforeBothNativeVariants() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeTrue(new File(path).isFile());
        try (JarFile jar = new JarFile(path);
             InputStream input = jar.getInputStream(jar.getJarEntry(GlStateTrackingAdapter.OPENGL_HELPER + ".class"))) {
            verifyActiveTextureDelegate(new ClassReader(input).b);
        }
    }

    private static void verifyActiveTextureDelegate(byte[] original) throws Exception {
        String owner = GlStateTrackingAdapter.OPENGL_HELPER;
        byte[] changed = new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.OPENGL_HELPER)
            .transform(owner, original, new TargetSpec(owner, "modern-visibility-hzb", "test",
                Collections.<String>emptySet()));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(changed).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) if (method.name.equals("func_77473_a")) {
            int nativeCalls = 0, barriers = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals(GlStateTrackingAdapter.HUD_BOOTSTRAP)) barriers++;
                    if (call.owner.startsWith("org/lwjgl/opengl/")
                        && call.name.startsWith("glActiveTexture")) nativeCalls++;
                }
            assertTrue("missing active texture native variants", nativeCalls > 0);
            assertEquals(nativeCalls, barriers);
        }
        assertTrue(new ByteLoader().define(changed).getDeclaredMethods().length > 0);
    }

    private static void verifyBoolean(ClassReader original) throws Exception {
        String owner = GlStateTrackingAdapter.BOOLEAN_STATE;
        byte[] changed = new GlStateTrackingAdapter(GlStateTrackingAdapter.Part.BOOLEAN_STATE)
            .transform(owner, original.b, new TargetSpec(owner, "modern-visibility-hzb",
                "test", Collections.<String>emptySet()));
        ClassNode before = new ClassNode(Opcodes.ASM5), after = new ClassNode(Opcodes.ASM5);
        original.accept(before, ClassReader.EXPAND_FRAMES);
        new ClassReader(changed).accept(after, ClassReader.EXPAND_FRAMES);
        assertEquals(frames(before), frames(after));
        // This is real JVM verification, not merely ASM's BasicVerifier.
        Class<?> actual = new ByteLoader().define(changed);
        assertTrue(actual.getDeclaredMethods().length > 0);
        assertTrue(actual.getDeclaredConstructors().length > 0);

        // Replace only the native endpoints in the offline copy. Execute the
        // original, otherwise unchanged Minecraft/OptiFine conditional graph.
        ClassReader reader = new ClassReader(changed);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                        String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5,
                    super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String callOwner,
                                                          String method, String descriptor, boolean itf) {
                        if (GlStateTrackingAdapter.HUD_BOOTSTRAP.equals(callOwner)
                            || "org/lwjgl/opengl/GL11".equals(callOwner)
                                && (method.equals("glEnable") || method.equals("glDisable"))) {
                            callOwner = Type.getInternalName(Calls.class);
                        }
                        super.visitMethodInsn(opcode, callOwner, method, descriptor, itf);
                    }
                };
            }
        }, 0);
        Class<?> executable = new ByteLoader().define(writer.toByteArray());
        Constructor<?> constructor = executable.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(3042);
        Method change = executable.getDeclaredMethod("func_179199_a", boolean.class);
        change.setAccessible(true);
        Calls.events.clear();
        change.invoke(state, false); // Initially disabled; no native call.
        change.invoke(state, true);
        change.invoke(state, true);
        change.invoke(state, false);
        change.invoke(state, false);
        assertEquals(Arrays.asList("barrier", "enable:3042", "barrier", "disable:3042"),
            Calls.events);
    }

    private static List<String> frames(ClassNode node) {
        List<String> frames = new ArrayList<String>();
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) {
                FrameNode frame = (FrameNode) instruction;
                frames.add(method.name + method.desc + ":" + frame.type + ":" + frame.local + ":" + frame.stack);
            }
        }
        return frames;
    }

    public static final class Calls {
        static final List<String> events = new ArrayList<String>();
        public static void barrier() { events.add("barrier"); }
        public static void glEnable(int capability) { events.add("enable:" + capability); }
        public static void glDisable(int capability) { events.add("disable:" + capability); }
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader() { super(HudStateMutationBarrierTest.class.getClassLoader()); }
        ByteLoader(ClassLoader parent) { super(parent); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}

package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Executes the real branch bytecode with recording draw/state endpoints in the JVM. */
public final class CommonModelStateBatchRuntimeTest {
    @Test public void forgeChildAdultAndSneakingPreserveDrawMatrixAndCallbackOrder() throws Exception {
        byte[] source = forgeBiped();
        assertOrder(source);
    }

    @Test public void optifineG5ChildAdultAndSneakingPreserveDrawMatrixAndCallbackOrder() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            assertOrder(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
        }
    }

    @Test public void partFailureRestoresBeforeRethrowingAndNeverRunsTail() throws Exception {
        for (boolean child : new boolean[] {false, true}) {
            Fixture fixture = fixture(forgeBiped(), true);
            Trace.reset(true);
            Trace.failPart = "field_78115_e";
            assertFailure(fixture, child, false);
            assertSame(Trace.failure, Trace.closedFailure);
            assertEquals(0, Trace.normalEnds);
            assertEquals(1, Trace.failureEnds);
            assertFalse(Trace.events.contains("tail"));
            assertFalse(Trace.active);
        }
    }

    @Test public void tailFailureOccursAfterRestorationAndIsNotClosedTwice() throws Exception {
        Fixture fixture = fixture(forgeBiped(), true);
        Trace.reset(true);
        Trace.failTail = true;
        assertFailure(fixture, false, true);
        assertEquals(1, Trace.normalEnds);
        assertEquals(0, Trace.failureEnds);
        assertFalse(Trace.active);
    }

    @Test public void declinedBeginUsesOriginalDrawsAndDoesNotCloseAnotherScope() throws Exception {
        Fixture fixture = fixture(forgeBiped(), true);
        Trace.reset(false);
        fixture.draw(true, false);
        assertEquals(1, Trace.begins);
        assertEquals(0, Trace.normalEnds);
        assertEquals(0, Trace.failureEnds);
        assertEquals("tail", Trace.events.get(Trace.events.size() - 1));
        assertFalse(Trace.active);
    }

    @Test public void splitScopeVisitsRootsInRealDrawOrderOnBothForgeAndG5Branches() throws Exception {
        assertSplit(forgeBiped());
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            assertSplit(support.patchAndRemap("bpx", CommonModelStateBatchAdapter.BIPED.replace('/', '.')));
        }
    }

    private static void assertSplit(byte[] source) throws Exception {
        Fixture original = fixture(source, false);
        Fixture adapted = fixture(source, true);
        for (boolean child : new boolean[]{false, true}) {
            Trace.reset(false);
            original.draw(child, true);
            List<String> expected = new ArrayList<String>(Trace.events);
            Trace.reset(true);
            Trace.split = true;
            adapted.draw(child, true);
            assertEquals(expected, Trace.events);
            assertEquals(java.util.Arrays.asList(1, 2, 3, 4, 5, 6), Trace.boundaries);
            assertEquals(1, Trace.normalEnds);
            assertFalse(Trace.active);
        }
    }

    private static void assertOrder(byte[] source) throws Exception {
        Fixture original = fixture(source, false);
        Fixture adapted = fixture(source, true);
        for (boolean child : new boolean[] {false, true}) {
            for (boolean sneak : new boolean[] {false, true}) {
                Trace.reset(false);
                original.draw(child, sneak);
                List<String> expected = new ArrayList<String>(Trace.events);
                Trace.reset(true);
                adapted.draw(child, sneak);
                assertEquals(expected, Trace.events);
                assertEquals(1, Trace.begins);
                assertEquals(1, Trace.normalEnds);
                assertEquals(0, Trace.failureEnds);
                assertFalse(Trace.active);
            }
        }
    }

    private static void assertFailure(Fixture fixture, boolean child, boolean sneak) throws Exception {
        try {
            fixture.draw(child, sneak);
            fail("the original draw/callback failure must propagate");
        } catch (InvocationTargetException expected) {
            assertSame(Trace.failure, expected.getCause());
        }
    }

    private static byte[] forgeBiped() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "").trim();
        Assume.assumeTrue("run with -PminecraftSrgJar", !path.isEmpty() && new File(path).isFile());
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(
            jar.getJarEntry(CommonModelStateBatchAdapter.BIPED + ".class"))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0;) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static Fixture fixture(byte[] original, boolean batch) throws Exception {
        ClassNode source = new ClassNode(Opcodes.ASM5);
        new ClassReader(original).accept(source, ClassReader.EXPAND_FRAMES);
        MethodNode render = ModelStateBatchMethodProof.method(source,
            CommonModelStateBatchAdapter.RENDER, CommonModelStateBatchAdapter.RENDER_DESCRIPTOR);
        ClassNode minimal = new ClassNode(Opcodes.ASM5);
        minimal.version = Opcodes.V1_8;
        minimal.access = Opcodes.ACC_PUBLIC;
        minimal.name = source.name;
        minimal.superName = "java/lang/Object";
        minimal.methods.add(render);
        Map<String, String> fields = new HashMap<String, String>();
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (source.name.equals(field.owner)
                    || "net/minecraft/client/model/ModelBase".equals(field.owner)) fields.put(field.name, field.desc);
            }
        }
        for (Map.Entry<String, String> field : fields.entrySet()) minimal.fields.add(
            new FieldNode(Opcodes.ACC_PUBLIC, field.getKey(), field.getValue(), null, null));
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        minimal.methods.add(constructor);
        MethodNode animation = new MethodNode(Opcodes.ACC_PUBLIC, "func_78087_a",
            "(FFFFFFLnet/minecraft/entity/Entity;)V", null, null);
        animation.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            internal(Trace.class), "animation", "()V", false));
        animation.instructions.add(new InsnNode(Opcodes.RETURN));
        minimal.methods.add(animation);
        render.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
            internal(Trace.class), "head", "()V", false));
        AbstractInsnNode returned = render.instructions.getLast();
        while (returned.getOpcode() != Opcodes.RETURN) returned = returned.getPrevious();
        render.instructions.insertBefore(returned, new MethodInsnNode(Opcodes.INVOKESTATIC,
            internal(Trace.class), "tail", "()V", false));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        minimal.accept(writer);
        byte[] bytes = writer.toByteArray();
        if (batch) bytes = new CommonModelStateBatchAdapter().transform(source.name, bytes,
            new TargetSpec(source.name, "modern-entity-backend,modern-tesr-backend",
                "modern-common-model-state-batch", Collections.<String>emptySet()));
        final String generated = "dev/rlcraft/ice/hooks/ExecutableBiped";
        Map<String, String> mappings = new HashMap<String, String>();
        mappings.put(source.name, generated);
        mappings.put("net/minecraft/client/model/ModelBase", generated);
        mappings.put(ModelRendererVboAdapter.TARGET, internal(Part.class));
        mappings.put(ModelRendererVboAdapter.GL_STATE_MANAGER, internal(Matrix.class));
        mappings.put("net/minecraft/entity/Entity", internal(Entity.class));
        mappings.put(CommonModelStateBatchAdapter.BRIDGE, internal(Trace.class));
        ClassWriter mapped = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(mapped, new SimpleRemapper(mappings)), 0);
        final byte[] executable = mapped.toByteArray();
        Class<?> type = new ClassLoader(CommonModelStateBatchRuntimeTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(generated.replace('/', '.'), executable, 0, executable.length); }
        }.define();
        Object model = type.newInstance();
        for (Field field : type.getFields()) if (field.getType() == Part.class) field.set(model, new Part(field.getName()));
        return new Fixture(model);
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }

    private static final class Fixture {
        final Object model;
        Fixture(Object model) { this.model = model; }
        void draw(boolean child, boolean sneak) throws Exception {
            for (Field field : model.getClass().getFields()) {
                if (field.getType() == boolean.class) field.setBoolean(model, child);
            }
            model.getClass().getMethod("func_78088_a", Entity.class,
                float.class, float.class, float.class, float.class, float.class, float.class)
                .invoke(model, new Entity(sneak), 1F, 2F, 3F, 4F, 5F, 0.0625F);
        }
    }

    public static final class Part {
        final String name;
        Part(String name) { this.name = name; }
        public void func_78785_a(float scale) {
            assertEquals(Trace.enabled && !(Trace.split && "field_178723_h".equals(name)), Trace.active);
            Trace.events.add("part:" + name + ':' + scale);
            if (name.equals(Trace.failPart)) throw Trace.failure;
        }
    }

    public static final class Entity {
        final boolean sneak;
        Entity(boolean sneak) { this.sneak = sneak; }
        public boolean func_70093_af() {
            assertFalse(Trace.active);
            Trace.events.add("sneak:" + sneak);
            return sneak;
        }
    }

    public static final class Matrix {
        public static void func_179094_E() { Trace.events.add("push"); }
        public static void func_179121_F() { Trace.events.add("pop"); }
        public static void func_179152_a(float x, float y, float z) { Trace.events.add("scale:" + x + ':' + y + ':' + z); }
        public static void func_179109_b(float x, float y, float z) { Trace.events.add("translate:" + x + ':' + y + ':' + z); }
    }

    public static final class Trace {
        static final List<String> events = new ArrayList<String>();
        static final List<Integer> boundaries = new ArrayList<Integer>();
        static boolean enabled, active, failTail, split;
        static String failPart;
        static int begins, normalEnds, failureEnds;
        static RuntimeException failure;
        static Throwable closedFailure;
        static void reset(boolean batch) {
            events.clear(); boundaries.clear(); enabled = batch; active = false; failTail = false; failPart = null; split = false;
            begins = normalEnds = failureEnds = 0; closedFailure = null;
            failure = new IllegalStateException("original callback failure");
        }
        public static void head() { assertFalse(active); events.add("head"); }
        public static void animation() { assertFalse(active); events.add("animation"); }
        public static void tail() { assertFalse(active); events.add("tail"); if (failTail) throw failure; }
        public static int beginRoots(Object model, int kind) { assertFalse(active); begins++; active = enabled; return enabled ? (split ? 2 : 1) : 0; }
        public static void beforeRoot(Object model, int kind, int index) {
            boundaries.add(Integer.valueOf(index));
            active = index != 2;
        }
        public static void end() { normalEnds++; active = false; }
        public static void endAfterFailure(Throwable original) { failureEnds++; closedFailure = original; active = false; }
    }
}

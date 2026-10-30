package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

public class OptifineItemAnimationAdapterTest {
    @Test public void resourcePathsKeepItemsWithoutEnablingTerrain() {
        for (String item : new String[] {"minecraft:items/diamond_sword", "spartanfire:items/greatsword_lightning_dragon",
            "mujmajnkraftsbettersurvival:items/firedragonbonespear", "mod:item/blade", "items/blade",
            "mod:items/blade_n", "mod:items/blade_s"}) {
            assertTrue(item, OptifineItemAnimationPolicy.allowAnimation(false, item));
        }
        for (String terrain : new String[] {null, "", "mod:blocks/animated", "items:blocks/blade",
            "mod:blocks/items/blade", "mod:particle/sweep", "mod:custom/blade", "mod:items_extra/blade"}) {
            assertFalse(terrain, OptifineItemAnimationPolicy.allowAnimation(false, terrain));
            assertTrue(OptifineItemAnimationPolicy.allowAnimation(true, terrain));
        }
    }

    @Test public void catalogInstallsIndependentlyOfTheRenderer() {
        boolean found = false;
        for (TargetSpec spec : OptimizerTargetCatalog.findAll(OptifineItemAnimationAdapter.MAP.replace('/', '.'))) {
            if (!spec.adapterId.equals("optifine-item-animations")) continue;
            found = true;
            assertNotNull(OptimizerAdapterRegistry.find(spec.adapterId));
            assertTrue(OptimizerBootstrapPolicy.shouldInstall(spec, false));
        }
        assertTrue(found);
    }

    @Test public void vanillaWithoutOptifineIsLeftAlone() {
        ClassNode vanilla = new ClassNode(Opcodes.ASM5);
        vanilla.version = Opcodes.V1_8;
        vanilla.name = OptifineItemAnimationAdapter.MAP;
        vanilla.superName = "java/lang/Object";
        try {
            adapt(bytes(vanilla));
            fail("Vanilla must be skipped");
        } catch (OptimizerAdapterSkippedException expected) { }
    }

    @Test public void realG5GatePreservesItsOtherOptionsAndWorksWithTextureStreaming() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            byte[] original = support.patchAndRemap("cdp", OptifineItemAnimationAdapter.MAP.replace('/', '.'));
            byte[] patched = adapt(original);
            exerciseOriginalAndPatchedGate(original, patched);
            byte[] streaming = new AnimatedTextureAdapter(AnimatedTextureAdapter.Part.MAP)
                .transform(OptifineItemAnimationAdapter.MAP, original, null);
            exerciseOriginalAndPatchedGate(original, adapt(streaming));
            byte[] reverse = new AnimatedTextureAdapter(AnimatedTextureAdapter.Part.MAP)
                .transform(OptifineItemAnimationAdapter.MAP, patched, null);
            exerciseOriginalAndPatchedGate(original, reverse);
        }
    }

    @Test public void realG5DuplicateOrChangedGateFailsOpen() throws Exception {
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip()) {
            byte[] original = support.patchAndRemap("cdp", OptifineItemAnimationAdapter.MAP.replace('/', '.'));
            reject(adapt(original));
            ClassNode node = read(original);
            for (AbstractInsnNode instruction : gate(node).instructions.toArray()) {
                if (instruction instanceof MethodInsnNode
                    && ((MethodInsnNode) instruction).name.equals("isAnimatedTerrain")) {
                    ((MethodInsnNode) instruction).owner = "UnknownConfig";
                }
            }
            reject(bytes(node));
            node = read(original);
            gate(node).access = Opcodes.ACC_PUBLIC;
            reject(bytes(node));
        }
    }

    @Test public void actualBetterSurvivalNunchakuSpinningModelsRemainAnimated() throws Exception {
        File source = new File(System.getProperty("ice.bettersurvival.jar", ""));
        Assume.assumeTrue("run with -PbetterSurvivalJar", source.isFile());
        try (OptifinePatchedClassSupport support = OptifinePatchedClassSupport.openOrSkip();
             JarFile jar = new JarFile(source)) {
            byte[] original = support.patchAndRemap("cdp", OptifineItemAnimationAdapter.MAP.replace('/', '.'));
            Gate before = executableGate(original);
            Gate after = executableGate(adapt(original));
            TestOptions.terrain = false;
            int count = 0;
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("assets/mujmajnkraftsbettersurvival/models/item/")
                    || !entry.getName().endsWith("nunchakuspinning.json")) continue;
                JsonObject model = json(jar, entry.getName());
                String spriteName = model.getAsJsonObject("textures").get("layer0").getAsString();
                String texture = "assets/" + spriteName.replace(":", "/textures/") + ".png";
                assertNotNull("Missing spinning texture: " + texture, jar.getJarEntry(texture));
                JsonObject animation = json(jar, texture + ".mcmeta").getAsJsonObject("animation");
                assertNotNull(animation);
                assertEquals(1, animation.get("frametime").getAsInt());
                TestSprite sprite = new TestSprite(spriteName);
                assertFalse("Reproduce frozen attack: " + spriteName, before.allows(sprite));
                assertTrue("Restore attack texture: " + spriteName, after.allows(sprite));
                count++;
            }
            assertEquals("Reviewed Better Survival 1.5.4 spinning variants", 23, count);
        }
    }

    private static JsonObject json(JarFile jar, String path) throws Exception {
        JarEntry entry = jar.getJarEntry(path);
        assertNotNull(path, entry);
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader).getAsJsonObject();
        }
    }

    private static void exerciseOriginalAndPatchedGate(byte[] original, byte[] patched) throws Exception {
        Gate before = executableGate(original);
        Gate after = executableGate(patched);
        TestSprite item = new TestSprite("spartanfire:items/greatsword_lightning_dragon");
        TestSprite block = new TestSprite("mod:blocks/animated");
        TestSprite unknown = new TestSprite("mod:custom/sprite");
        for (int mask = 0; mask < 32; mask++) {
            TestOptions.terrain = (mask & 1) != 0;
            TestOptions.water = (mask & 2) != 0;
            TestOptions.lava = (mask & 4) != 0;
            TestOptions.fire = (mask & 8) != 0;
            TestOptions.portal = (mask & 16) != 0;
            assertEquals(TestOptions.terrain, before.allows(item));
            assertTrue(after.allows(item));
            for (TestSprite other : new TestSprite[] {block, unknown, TestIcons.iconWaterStill,
                TestIcons.iconWaterFlow, TestIcons.iconLavaStill, TestIcons.iconLavaFlow,
                TestIcons.iconFireLayer0, TestIcons.iconFireLayer1, TestIcons.iconPortal,
                TestIcons.iconClock, TestIcons.iconCompass}) {
                assertEquals(other.name + " options=" + mask, before.allows(other), after.allows(other));
            }
        }
        // Exercise live option transitions on the same gate instance.
        TestOptions.terrain = false;
        assertFalse(after.allows(block));
        assertTrue(after.allows(item));
        TestOptions.terrain = true;
        assertTrue(after.allows(block));
        TestOptions.terrain = false;
        assertFalse(after.allows(block));
    }

    /** Execute the actual G5 method in a JVM, replacing only its external data sources. */
    private static Gate executableGate(byte[] source) throws Exception {
        ClassNode node = read(source);
        MethodNode method = gate(node);
        node.fields.clear();
        node.methods.clear();
        node.interfaces.clear();
        node.innerClasses.clear();
        node.superName = "java/lang/Object";
        method.access = Opcodes.ACC_PUBLIC;
        node.methods.add(method);
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(init);
        Map<String, String> names = new HashMap<String, String>();
        names.put(node.name, "ice/test/ItemAnimationGate");
        names.put(OptifineItemAnimationAdapter.SPRITE, TestSprite.class.getName().replace('.', '/'));
        names.put("net/optifine/util/TextureUtils", TestIcons.class.getName().replace('.', '/'));
        names.put("Config", TestOptions.class.getName().replace('.', '/'));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        final byte[] definition = writer.toByteArray();
        Class<?> type = new ClassLoader(OptifineItemAnimationAdapterTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("ice.test.ItemAnimationGate", definition, 0, definition.length); }
        }.define();
        return new Gate(type.newInstance(), type.getMethod(OptifineItemAnimationAdapter.GATE, TestSprite.class));
    }

    private static byte[] adapt(byte[] bytes) {
        return new OptifineItemAnimationAdapter().transform(OptifineItemAnimationAdapter.MAP, bytes, null);
    }
    private static void reject(byte[] bytes) {
        try { adapt(bytes); fail("Changed gate must be rejected"); }
        catch (IllegalStateException expected) { }
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
    private static MethodNode gate(ClassNode node) {
        for (MethodNode method : node.methods) if (method.name.equals(OptifineItemAnimationAdapter.GATE)) return method;
        throw new AssertionError("Missing gate");
    }
    private static final class Gate {
        final Object instance;
        final Method method;
        Gate(Object instance, Method method) { this.instance = instance; this.method = method; }
        boolean allows(TestSprite sprite) throws Exception { return (Boolean) method.invoke(instance, sprite); }
    }
    public static final class TestSprite {
        final String name;
        TestSprite(String name) { this.name = name; }
        public String func_94215_i() { return name; }
    }
    public static final class TestOptions {
        static boolean terrain, water, lava, fire, portal;
        public static boolean isAnimatedTerrain() { return terrain; }
        public static boolean isAnimatedWater() { return water; }
        public static boolean isAnimatedLava() { return lava; }
        public static boolean isAnimatedFire() { return fire; }
        public static boolean isAnimatedPortal() { return portal; }
    }
    public static final class TestIcons {
        public static TestSprite iconWaterStill = new TestSprite("minecraft:blocks/water_still");
        public static TestSprite iconWaterFlow = new TestSprite("minecraft:blocks/water_flow");
        public static TestSprite iconLavaStill = new TestSprite("minecraft:blocks/lava_still");
        public static TestSprite iconLavaFlow = new TestSprite("minecraft:blocks/lava_flow");
        public static TestSprite iconFireLayer0 = new TestSprite("minecraft:blocks/fire_layer_0");
        public static TestSprite iconFireLayer1 = new TestSprite("minecraft:blocks/fire_layer_1");
        public static TestSprite iconPortal = new TestSprite("minecraft:blocks/portal");
        public static TestSprite iconClock = new TestSprite("minecraft:items/clock");
        public static TestSprite iconCompass = new TestSprite("minecraft:items/compass");
    }
}

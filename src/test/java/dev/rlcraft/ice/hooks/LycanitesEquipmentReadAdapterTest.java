package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Executes the installed ItemEquipment logic and real vanilla NBT item decoding.
 * Its external part/feature/localization callbacks are observable test fixtures.
 */
public final class LycanitesEquipmentReadAdapterTest {
    private static final String TARGET = "com/lycanitesmobs/core/item/equipment/ItemEquipment";
    private static final String PART = "com/lycanitesmobs/core/item/equipment/ItemEquipmentPart";
    private static final String FEATURE = "com/lycanitesmobs/core/item/equipment/features/EquipmentFeature";
    private static final String DAMAGE = "com/lycanitesmobs/core/item/equipment/features/DamageEquipmentFeature";
    private static final Set<String> READ_METHODS = new HashSet<String>(Arrays.asList(
        "getAdditionalDescriptions", "getAttributeModifiers", "getFeatureSummaries",
        "getFeaturesByType", "getFeaturesByTypeWithPartStack", "getSharpness", "getMana",
        "getDamageAmount", "getDamageCooldown", "getDamageKnockback", "getDamageRange",
        "getDamageSweep", "getEquipmentPartStacks", "getEquipmentPart", "getPartLevel", "<clinit>"));
    private static final List<String> EVENTS = new ArrayList<String>();
    private static FixturePart part;
    private static int parses;
    private static boolean recordEvents = true;
    private static Runnable onMana;
    private static RuntimeException callbackFailure;

    @Before public void setup() {
        Bootstrap.register();
        if (part == null) {
            FixturePart candidate = new FixturePart();
            candidate.setRegistryName(new ResourceLocation("ice_test", "equipment_read_part"));
            ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
            boolean locked = registry.isLocked();
            if (locked) registry.unfreeze();
            try { registry.register(candidate); }
            finally { if (locked) registry.freeze(); }
            ItemStack roundTrip = new ItemStack(new ItemStack(candidate).writeToNBT(new NBTTagCompound()));
            assertSame("fixture must survive real NBT item registry lookup", candidate, roundTrip.getItem());
            part = candidate;
        }
        EVENTS.clear(); parses = 0; recordEvents = true; onMana = null; callbackFailure = null;
        enable(true);
    }

    @After public void restore() {
        onMana = null; callbackFailure = null; EVENTS.clear();
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void actualClassVerifiesAndRejectsDuplicateOrIncompleteTransformations() throws Exception {
        byte[] original = original();
        String dotted = TARGET.replace('/', '.');
        TargetSpec target = OptimizerTargetCatalog.find(dotted);
        assertNotNull(target);
        assertTrue(target.hasReviewedFingerprint(CoreClassFingerprint.sha256(original)));
        byte[] changed = new IceClientOptimizerTransformer().transform(dotted, dotted, original);
        assertFalse("catalog must dispatch the real target to the equipment adapter", Arrays.equals(original, changed));
        ClassNode node = read(changed);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer(new BasicVerifier()).analyze(node.name, method);
            }
        }
        assertRejected(changed);
        ClassNode incomplete = read(original());
        for (Iterator<MethodNode> iterator = incomplete.methods.iterator(); iterator.hasNext();) {
            if ("getMana".equals(iterator.next().name)) iterator.remove();
        }
        ClassWriter writer = new ClassWriter(0); incomplete.accept(writer);
        assertRejected(writer.toByteArray());
    }

    @Test public void actualTooltipKeepsTextAndEveryCallbackWhileDecodingOnce() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        ItemStack equipment = equipment(300, 800, 3);
        List<String> expected = baseline.tooltip(equipment);
        List<String> callbacks = new ArrayList<String>(EVENTS);
        assertEquals("reviewed positive-sharpness tooltip path", 15, parses);
        resetObservations();
        assertEquals(expected, optimized.tooltip(equipment));
        assertEquals(callbacks, EVENTS);
        assertEquals(1, parses);
    }

    @Test public void actualAttributesKeepValuesAndCallbackOrderForEverySlot() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        for (int sharpness : new int[] { 0, 300 }) {
            ItemStack equipment = equipment(sharpness, 800, 3);
            for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                resetObservations();
                List<String> expected = attributes(baseline.attributes(slot, equipment));
                List<String> callbacks = new ArrayList<String>(EVENTS);
                int originalParses = parses;
                resetObservations();
                assertEquals(expected, attributes(optimized.attributes(slot, equipment)));
                assertEquals(callbacks, EVENTS);
                assertEquals(slot == EntityEquipmentSlot.MAINHAND ? 1 : 0, parses);
                assertEquals(slot == EntityEquipmentSlot.MAINHAND ? (sharpness > 0 ? 3 : 2) : 0,
                    originalParses);
            }
        }
    }

    @Test public void multiplePartsKeepInactiveCallbacksAndLastFeatureValueAndEmptyItemsStayEmpty() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        NBTTagList items = new NBTTagList();
        int[][] values = { { 0, 300, 800, 2 }, { 4, 0, 45, 0 }, { 19, 99, 620, 7 } };
        for (int[] value : values) {
            NBTTagCompound saved = equipment(value[1], value[2], value[3]).getTagCompound()
                .getTagList("Items", 10).getCompoundTagAt(0).copy();
            saved.setByte("Slot", (byte) value[0]); items.appendTag(saved);
        }
        ItemStack multiple = new ItemStack(new Item());
        NBTTagCompound tag = new NBTTagCompound(); tag.setTag("Items", items); multiple.setTagCompound(tag);
        ItemStack empty = new ItemStack(new Item());
        for (ItemStack equipment : Arrays.asList(multiple, empty)) {
            resetObservations();
            List<String> expected = baseline.tooltip(equipment);
            List<String> callbacks = new ArrayList<String>(EVENTS);
            int originalParses = parses;
            resetObservations();
            assertEquals(expected, optimized.tooltip(equipment));
            assertEquals(callbacks, EVENTS);
            if (equipment == multiple) {
                assertTrue("original repeatedly decodes the multi-part inventory", originalParses > 1);
                assertEquals(1, parses);
                assertTrue(expected.toString(), expected.toString().contains("harvest:7"));
                assertTrue(EVENTS.contains("active:harvest:0"));
                assertFalse(EVENTS.contains("summary:harvest:0"));
            } else {
                assertEquals(0, originalParses); assertEquals(0, parses);
                assertFalse("a read of absent Items must not attach a tag", equipment.hasTagCompound());
            }
            resetObservations();
            List<String> attributes = attributes(baseline.attributes(EntityEquipmentSlot.MAINHAND, equipment));
            callbacks = new ArrayList<String>(EVENTS);
            resetObservations();
            assertEquals(attributes, attributes(optimized.attributes(EntityEquipmentSlot.MAINHAND, equipment)));
            assertEquals(callbacks, EVENTS);
            assertEquals(equipment == multiple ? 1 : 0, parses);
        }
    }

    @Test public void secondIndependentCallSeesNestedNbtEditsAndItemReplacement() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        ItemStack equipment = equipment(300, 800, 3);
        List<String> first = optimized.tooltip(equipment);
        NBTTagCompound nested = equipment.getTagCompound().getTagList("Items", 10)
            .getCompoundTagAt(0).getCompoundTag("tag");
        nested.setInteger("sharpness", 0);
        nested.setInteger("mana", 19);
        nested.setInteger("level", 7);
        List<String> expected = baseline.tooltip(equipment);
        resetObservations();
        assertEquals(expected, optimized.tooltip(equipment));
        assertNotEquals(first, expected);
        assertEquals(1, parses);
        equipment.setTagCompound(equipment(500, 620, 2).getTagCompound());
        expected = baseline.tooltip(equipment);
        resetObservations();
        assertEquals(expected, optimized.tooltip(equipment));
        assertEquals(1, parses);
    }

    @Test public void disablingModuleRestoresTheOriginalNumberOfDecodes() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        ItemStack equipment = equipment(300, 800, 3);
        List<String> expected = baseline.tooltip(equipment);
        List<String> callbacks = new ArrayList<String>(EVENTS);
        enable(false); resetObservations();
        assertEquals(expected, optimized.tooltip(equipment));
        assertEquals(callbacks, EVENTS);
        assertEquals(15, parses);
        enable(true); resetObservations();
        assertEquals(expected, optimized.tooltip(equipment));
        assertEquals(1, parses);
    }

    @Test public void callbackFailurePropagatesAndDoesNotLeakIntoTheNextInvocation() throws Exception {
        Runtime baseline = executable(false);
        Runtime optimized = executable(true);
        ItemStack equipment = equipment(300, 800, 3);
        RuntimeException failure = new IllegalStateException("part callback failed");
        callbackFailure = failure;
        assertSame(failure, tooltipFailure(baseline, equipment));
        List<String> expected = new ArrayList<String>(EVENTS);
        resetObservations();
        assertSame(failure, tooltipFailure(optimized, equipment));
        assertEquals(expected, EVENTS);
        callbackFailure = null;
        equipment.setTagCompound(equipment(1, 2, 4).getTagCompound());
        List<String> output = baseline.tooltip(equipment);
        resetObservations();
        assertEquals(output, optimized.tooltip(equipment));
        assertEquals(1, parses);
    }

    @Test public void reentrantReadUsesItsOwnEquipmentView() throws Exception {
        final Runtime baseline = executable(false);
        final Runtime optimized = executable(true);
        final ItemStack outer = equipment(300, 800, 3);
        final ItemStack inner = equipment(0, 20, 8);
        final List<List<String>> nested = new ArrayList<List<String>>();
        onMana = nestedAttributes(baseline, inner, nested);
        List<String> expected = baseline.tooltip(outer);
        List<String> callbacks = new ArrayList<String>(EVENTS);
        List<List<String>> nestedExpected = new ArrayList<List<String>>(nested);
        assertEquals(17, parses);
        resetObservations(); nested.clear();
        onMana = nestedAttributes(optimized, inner, nested);
        assertEquals(expected, optimized.tooltip(outer));
        assertEquals(callbacks, EVENTS);
        assertEquals(nestedExpected, nested);
        assertEquals("one outer decode and one independent nested decode", 2, parses);
    }

    @Test public void equipmentSubclassKeepsItsOverriddenGetter() throws Exception {
        Runtime baseline = subclass(executable(false));
        Runtime optimized = subclass(executable(true));
        ItemStack equipment = equipment(300, 800, 3);
        List<String> expected = attributes(baseline.attributes(EntityEquipmentSlot.MAINHAND, equipment));
        List<String> callbacks = new ArrayList<String>(EVENTS);
        int originalParses = parses;
        resetObservations();
        assertEquals(expected, attributes(optimized.attributes(EntityEquipmentSlot.MAINHAND, equipment)));
        assertTrue(expected.toString(), expected.toString().contains("42.0"));
        assertEquals(callbacks, EVENTS);
        assertEquals(originalParses, parses);
    }

    /** Standalone optional probe; run on the test runtime classpath with -Dice.lycanites.jar=... . */
    public static void main(String[] arguments) throws Exception {
        LycanitesEquipmentReadAdapterTest test = new LycanitesEquipmentReadAdapterTest();
        test.setup();
        try {
            recordEvents = false;
            Runtime baseline = executable(false), optimized = executable(true);
            ItemStack equipment = equipment(300, 800, 3);
            com.sun.management.ThreadMXBean allocations =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            if (!allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
            allocations.setThreadAllocatedMemoryEnabled(true);
            for (boolean tooltip : new boolean[] { false, true }) {
                measure(baseline, equipment, tooltip, allocations, 3000);
                measure(optimized, equipment, tooltip, allocations, 3000);
                long[][] before = new long[7][], after = new long[7][];
                for (int round = 0; round < before.length; round++) {
                    if ((round & 1) == 0) {
                        before[round] = measure(baseline, equipment, tooltip, allocations, 3000);
                        after[round] = measure(optimized, equipment, tooltip, allocations, 3000);
                    } else {
                        after[round] = measure(optimized, equipment, tooltip, allocations, 3000);
                        before[round] = measure(baseline, equipment, tooltip, allocations, 3000);
                    }
                }
                System.out.println("Lycanites actual ItemEquipment " + (tooltip ? "tooltip" : "attributes")
                    + " median of 7 alternating rounds: original=" + median(before, 0) / 3000D
                    + " B/call, " + median(before, 1) / 3000D + " ns/call; optimized="
                    + median(after, 0) / 3000D + " B/call, " + median(after, 1) / 3000D
                    + " ns/call (vanilla decoding, fixture part callbacks; not gameplay FPS)");
            }
        } finally { recordEvents = true; test.restore(); }
    }

    private static long[] measure(Runtime runtime, ItemStack stack, boolean tooltip,
            com.sun.management.ThreadMXBean allocations, int count) throws Exception {
        long thread = Thread.currentThread().getId();
        long bytes = allocations.getThreadAllocatedBytes(thread), nanos = System.nanoTime();
        for (int index = 0; index < count; index++) {
            if (tooltip) runtime.tooltip(stack); else runtime.attributes(EntityEquipmentSlot.MAINHAND, stack);
        }
        return new long[] { allocations.getThreadAllocatedBytes(thread) - bytes, System.nanoTime() - nanos };
    }

    private static long median(long[][] samples, int column) {
        long[] values = new long[samples.length];
        for (int index = 0; index < samples.length; index++) values[index] = samples[index][column];
        Arrays.sort(values); return values[values.length / 2];
    }

    private static Runtime subclass(Runtime parent) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ice/test/EquipmentSubclass", null, TARGET, null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        MethodVisitor damage = writer.visitMethod(Opcodes.ACC_PUBLIC, "getDamageAmount",
            "(Lnet/minecraft/item/ItemStack;)D", null, null);
        damage.visitCode(); damage.visitLdcInsn(42D); damage.visitInsn(Opcodes.DRETURN);
        damage.visitMaxs(2, 2); damage.visitEnd(); writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(parent.item.getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return new Runtime(type.newInstance());
    }

    private static Runnable nestedAttributes(final Runtime runtime, final ItemStack equipment,
                                              final List<List<String>> output) {
        return new Runnable() {
            @Override public void run() {
                try { output.add(attributes(runtime.attributes(EntityEquipmentSlot.MAINHAND, equipment))); }
                catch (Exception exception) { throw new AssertionError(exception); }
            }
        };
    }

    private static Throwable tooltipFailure(Runtime runtime, ItemStack equipment) throws Exception {
        try { runtime.tooltip(equipment); fail("callback exception must propagate"); return null; }
        catch (InvocationTargetException expected) { return expected.getCause(); }
    }

    private static void resetObservations() { EVENTS.clear(); parses = 0; }

    private static void enable(boolean enabled) {
        OptimizationModule module = OptimizationModule.byId("lycanites-equipment-read");
        assertNotNull(module);
        OptimizerRegistry.breaker(module).configure(enabled, 3);
        if (enabled) OptimizerRegistry.breaker(module).patchInstalled(TARGET, "test");
    }

    private static ItemStack equipment(int sharpness, int mana, int level) {
        ItemStack component = new ItemStack(part);
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("sharpness", sharpness); data.setInteger("mana", mana); data.setInteger("level", level);
        component.setTagCompound(data);
        NonNullList<ItemStack> parts = NonNullList.withSize(20, ItemStack.EMPTY);
        parts.set(0, component);
        ItemStack equipment = new ItemStack(new Item());
        equipment.setTagCompound(ItemStackHelper.saveAllItems(new NBTTagCompound(), parts));
        return equipment;
    }

    private static List<String> attributes(Multimap<String, AttributeModifier> modifiers) {
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, AttributeModifier> entry : modifiers.entries()) {
            AttributeModifier value = entry.getValue();
            result.add(entry.getKey() + ":" + value.getID() + ":" + value.getName()
                + ":" + value.getAmount() + ":" + value.getOperation());
        }
        Collections.sort(result); return result;
    }

    private static byte[] original() throws Exception {
        String path = System.getProperty("ice.lycanites.jar", "");
        Assume.assumeFalse("configure ice.lycanites.jar", path.isEmpty());
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(TARGET + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192];
            for (int count; (count = in.read(bytes)) != -1;) out.write(bytes, 0, count);
            return out.toByteArray();
        }
    }

    private static byte[] transform(byte[] original) {
        return new LycanitesEquipmentReadAdapter().transform(TARGET.replace('/', '.'), original, null);
    }

    private static void assertRejected(byte[] input) {
        try { transform(input); fail("unsupported class must fail closed"); }
        catch (IllegalStateException expected) { }
    }

    private static ClassNode read(byte[] input) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }

    private static Runtime executable(boolean optimized) throws Exception {
        byte[] source = original();
        ClassNode node = read(optimized ? transform(source) : source);
        for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext();) {
            MethodNode method = iterator.next();
            if (!READ_METHODS.contains(method.name) && !method.name.startsWith("rlcraftIce$read")) iterator.remove();
        }
        // Only the environment-dependent constructor is replaced. All reviewed
        // calculation bodies and the actual NBT reader remain the installed code.
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        node.methods.add(constructor);
        final Map<String, String> methods = new HashMap<String, String>();
        String[][] methodNames = {
            { "func_191197_a", "withSize" }, { "func_74764_b", "hasKey" },
            { "func_191283_b", "loadAllItems" }, { "func_190926_b", "isEmpty" },
            { "func_77973_b", "getItem" }, { "func_77653_i", "getItemStackDisplayName" },
            { "func_111205_h", "getItemAttributeModifiers" }, { "func_111108_a", "getName" }
        };
        for (String[] pair : methodNames) methods.put(pair[0], pair[1]);
        final Map<String, String> fields = new HashMap<String, String>();
        String[][] fieldNames = {
            { "field_190927_a", "EMPTY" }, { "field_111264_e", "ATTACK_DAMAGE" },
            { "field_188790_f", "ATTACK_SPEED" }, { "field_111210_e", "ATTACK_DAMAGE_MODIFIER" },
            { "field_185050_h", "ATTACK_SPEED_MODIFIER" }
        };
        for (String[] pair : fieldNames) fields.put(pair[0], pair[1]);
        final Map<String, String> types = new HashMap<String, String>();
        types.put("com/lycanitesmobs/core/item/ItemBase", Type.getInternalName(FixtureBase.class));
        types.put(PART, Type.getInternalName(FixturePart.class));
        types.put(FEATURE, Type.getInternalName(FixtureFeature.class));
        types.put(DAMAGE, Type.getInternalName(FixtureDamage.class));
        types.put("com/lycanitesmobs/client/localisation/LanguageManager", Type.getInternalName(FixtureLanguage.class));
        types.put("net/minecraft/inventory/ItemStackHelper", Type.getInternalName(DecodeProbe.class));
        ClassWriter writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) { return types.containsKey(name) ? types.get(name) : name; }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return methods.containsKey(name) ? methods.get(name) : name;
            }
            @Override public String mapFieldName(String owner, String name, String descriptor) {
                return fields.containsKey(name) ? fields.get(name) : name;
            }
        }));
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(LycanitesEquipmentReadAdapterTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return new Runtime(type.newInstance());
    }

    private static final class Runtime {
        final Object item;
        final Method tooltip;
        final Method attributes;
        Runtime(Object item) throws Exception {
            this.item = item;
            tooltip = item.getClass().getMethod("getAdditionalDescriptions", ItemStack.class, World.class, ITooltipFlag.class);
            attributes = item.getClass().getMethod("getAttributeModifiers", EntityEquipmentSlot.class, ItemStack.class);
        }
        @SuppressWarnings("unchecked") List<String> tooltip(ItemStack stack) throws Exception {
            return (List<String>) tooltip.invoke(item, stack, null, ITooltipFlag.TooltipFlags.NORMAL);
        }
        @SuppressWarnings("unchecked") Multimap<String, AttributeModifier> attributes(EntityEquipmentSlot slot, ItemStack stack) throws Exception {
            return (Multimap<String, AttributeModifier>) attributes.invoke(item, slot, stack);
        }
    }

    public static class FixtureBase extends Item {
        public NBTTagCompound getTagCompound(ItemStack stack) {
            return stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        }
        @Override public Multimap<String, AttributeModifier> getItemAttributeModifiers(EntityEquipmentSlot slot) {
            if (recordEvents) EVENTS.add("baseAttributes:" + slot); return HashMultimap.create();
        }
    }

    public static final class FixturePart extends Item {
        public final List<FixtureFeature> features = Arrays.<FixtureFeature>asList(
            new FixtureDamage(), new FixtureFeature("harvest"), new FixtureFeature("effect"),
            new FixtureFeature("projectile"), new FixtureFeature("summon"));
        public int getLevel(ItemStack stack) {
            int value = stack.getTagCompound().getInteger("level"); if (recordEvents) EVENTS.add("level:" + value);
            if (callbackFailure != null) throw callbackFailure;
            return value;
        }
        public int getSharpness(ItemStack stack) {
            int value = stack.getTagCompound().getInteger("sharpness"); if (recordEvents) EVENTS.add("sharpness:" + value); return value;
        }
        public int getMana(ItemStack stack) {
            int value = stack.getTagCompound().getInteger("mana"); if (recordEvents) EVENTS.add("mana:" + value);
            Runnable callback = onMana; onMana = null; if (callback != null) callback.run(); return value;
        }
        @Override public String getItemStackDisplayName(ItemStack stack) { if (recordEvents) EVENTS.add("displayName"); return "test part"; }
    }

    public static class FixtureFeature {
        public final String featureType;
        FixtureFeature(String type) { featureType = type; }
        public boolean isActive(ItemStack stack, int level) { if (recordEvents) EVENTS.add("active:" + featureType + ':' + level); return level > 0; }
        public String getSummary(ItemStack stack, int level) { if (recordEvents) EVENTS.add("summary:" + featureType + ':' + level); return featureType + ':' + level; }
    }

    public static final class FixtureDamage extends FixtureFeature {
        public int damageAmount = 9;
        public double damageCooldown = 0.75;
        public double damageRange = 2.5;
        public double damageKnockback = 1.25;
        public double damageSweep = 120;
        FixtureDamage() { super("damage"); }
    }

    public static final class FixtureLanguage { public static String translate(String key) { return key; } }

    public static final class DecodeProbe {
        public static void loadAllItems(NBTTagCompound tag, NonNullList<ItemStack> stacks) {
            parses++; ItemStackHelper.loadAllItems(tag, stacks);
        }
    }
}

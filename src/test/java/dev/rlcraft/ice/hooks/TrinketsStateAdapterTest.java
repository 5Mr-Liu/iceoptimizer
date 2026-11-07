package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsStateBridge;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarFile;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Executes the installed writer bodies; only registry/slot fixtures and bootstrap are substituted. */
public final class TrinketsStateAdapterTest {
    private static volatile Object probeResult;
    private static final String ELEMENT = "xzeroair/trinkets/traits/elements/Element";
    private static final String ELEMENT_REGISTRY = "xzeroair/trinkets/init/Elements";
    private static final String SLOT = "xzeroair/trinkets/api/TrinketHelper$SlotInformation";
    private static final String[] TARGETS = { TrinketsStateAdapter.PROPERTIES,
        TrinketsStateAdapter.ELEMENTS, TrinketsStateAdapter.COUNTER, TrinketsStateAdapter.TICKS };

    @Before public void enable() { enabled(true); }
    @After public void restore() { OptimizerRegistry.configure(ClientOptimizerConfig.capture()); }

    @Test public void installedWritersVerifyAndPreserveEveryLiveCallback() throws Exception {
        for (String target : TARGETS) {
            byte[] original = original(target);
            byte[] changed = new TrinketsStateAdapter().transform(target, original, null);
            ClassNode node = read(changed);
            for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                new Analyzer(new BasicVerifier()).analyze(node.name, method);
            if (!TrinketsStateAdapter.TICKS.equals(target)) {
                try { new TrinketsStateAdapter().transform(target, changed, null); fail("duplicate transformation"); }
                catch (IllegalStateException expected) { }
            }
            for (String callback : new String[] { "getSlot", "getHandler", "getValue", "getName", "getTick",
                "getLength", "getCountdown", "shouldTick", "saveCountersToNBT", "getPrimaryElement",
                "getSecondaryElement", "getSubElements", "getRegistryName", "getID" }) {
                assertEquals(target + "/" + callback, calls(original, callback), calls(changed, callback));
            }
        }
    }

    @Test public void completeClassesPreserveEveryUnmodifiedMethodFrame() throws Exception {
        int reviewedFrames = 0;
        for (String target : TARGETS) {
            byte[] original = original(target);
            ClassNode before = read(original);
            ClassNode after = read(new TrinketsStateAdapter().transform(target, original, null));
            for (MethodNode method : before.methods) {
                boolean changed = (TrinketsStateAdapter.TICKS.equals(target)
                    && "saveCountersToNBT".equals(method.name) && "(Lnet/minecraft/nbt/NBTTagCompound;)V".equals(method.desc))
                    || ((TrinketsStateAdapter.PROPERTIES.equals(target) || TrinketsStateAdapter.ELEMENTS.equals(target))
                    && "saveToNBT".equals(method.name)
                    && "(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;".equals(method.desc));
                if (changed) continue;
                MethodNode counterpart = null;
                for (MethodNode candidate : after.methods)
                    if (method.name.equals(candidate.name) && method.desc.equals(candidate.desc)) counterpart = candidate;
                assertNotNull(target + "." + method.name + method.desc, counterpart);
                List<String> expected = frameStates(method);
                assertEquals(target + "." + method.name + method.desc, expected, frameStates(counterpart));
                reviewedFrames += expected.size();
            }
        }
        assertTrue("must include complete gameplay methods, not only writer fixtures", reviewedFrames > 100);
    }

    private static List<String> frameStates(MethodNode method) {
        Map<LabelNode, Integer> labels = new java.util.IdentityHashMap<LabelNode, Integer>();
        int position = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof LabelNode) labels.put((LabelNode) instruction, position);
            if (instruction.getOpcode() >= 0) position++;
        }
        List<String> result = new ArrayList<String>(); position = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) {
                FrameNode frame = (FrameNode) instruction;
                result.add(position + "/" + frame.type + "/" + frameValues(frame.local, labels)
                    + "/" + frameValues(frame.stack, labels));
            }
            if (instruction.getOpcode() >= 0) position++;
        }
        return result;
    }

    private static String frameValues(List<Object> values, Map<LabelNode, Integer> labels) {
        if (values == null) return "null";
        List<Object> result = new ArrayList<Object>();
        for (Object value : values) result.add(value instanceof LabelNode ? "uninitialized@" + labels.get(value) : value);
        return result.toString();
    }

    @Test public void realFullWriterReadsDirectFieldCounterElementAndSlotChanges() throws Exception {
        Runtime plain = new Runtime(false), fast = new Runtime(true);
        Object left = plain.state(), right = fast.state();
        Random random = new Random(1729);
        for (int i = 0; i < 30; i++) {
            int value = random.nextInt(500);
            for (Object state : new Object[] { left, right }) {
                field(state, "variant").setInt(state, i % 3);
                field(state, "exp").setInt(state, value);
                field(state, "mana").setFloat(state, i == 1 ? -0.0f : value / 7.0f);
                field(state, "mainAbility").setBoolean(state, (i & 1) == 0);
                field(state, "altAbility").setBoolean(state, (i & 2) == 0);
                field(state, "crafter").set(state, "maker-" + (i % 4));
                field(state, "crafterUUID").set(state, "id-" + (i % 3));
                FixtureSlot slot = (FixtureSlot) field(state, "slotInfo").get(state);
                slot.slot = i % 7; slot.handler = "handler-" + (i % 2);
                Object elements = field(state, "elements").get(state);
                field(elements, "primary").set(elements, new FixtureElement(2, "ice_test:primary" + (i % 2)));
                @SuppressWarnings("unchecked") Map<Object, Object> sub = (Map<Object, Object>) field(elements, "subElements").get(elements);
                sub.clear();
                if ((i & 1) == 0) sub.put(new ResourceLocation("ice_test", "sub"), new FixtureElement(5, "ice_test:sub"));
                Object counter = counters(state).get("clock");
                field(counter, "tick").setInt(counter, value);
                field(counter, "length").setInt(counter, 600 + i);
                field(counter, "countdown").setBoolean(counter, (i & 1) == 0);
                field(counter, "shouldTick").setBoolean(counter, (i & 2) == 0);
            }
            assertArrayEquals(encoded(plain.write(left)), encoded(fast.write(right)));
        }
        // Loading existing NBT can replace any field without touching a setter/cache.
        NBTTagCompound saved = plain.write(left);
        fast.type(TrinketsStateAdapter.PROPERTIES).getMethod("loadFromNBT", NBTTagCompound.class).invoke(right, saved);
        assertArrayEquals(encoded(saved), encoded(fast.write(right)));
    }

    @Test public void unchangedScalarEncodingsAreReusedButAllMutableSubtreesRemainIndependent() throws Exception {
        Runtime fast = new Runtime(true);
        Object state = fast.state();
        NBTTagCompound first = fast.write(state).getCompoundTag("xat:Trinket");
        NBTTagCompound second = fast.write(state).getCompoundTag("xat:Trinket");
        assertNotSame(first, second);
        for (String key : new String[] {"variant", "slot", "handler", "exp", "mana", "main.ability",
            "alt.ability", "crafter.name", "crafter.uuid"}) assertSame(key, first.getTag(key), second.getTag(key));
        NBTTagCompound firstElements = first.getCompoundTag("Elements"), nextElements = second.getCompoundTag("Elements");
        assertNotSame(firstElements, nextElements);
        assertSame(firstElements.getTag("primary"), nextElements.getTag("primary"));
        NBTTagCompound firstCounters = first.getCompoundTag("Counters"), nextCounters = second.getCompoundTag("Counters");
        assertNotSame(firstCounters, nextCounters);
        assertNotSame(firstCounters.getTag("clock"), nextCounters.getTag("clock"));
        for (String key : new String[] {"Tick", "Length", "Countdown", "ShouldTick"})
            assertSame(firstCounters.getCompoundTag("clock").getTag(key), nextCounters.getCompoundTag("clock").getTag(key));
        first.setInteger("variant", 999);
        firstElements.setString("primary", "foreign:value");
        firstCounters.getCompoundTag("clock").setInteger("Tick", 999);
        firstCounters.setString("foreign", "value");
        assertEquals(second, fast.write(state).getCompoundTag("xat:Trinket"));
        field(state, "mana").setFloat(state, 50.0f);
        NBTTagCompound third = fast.write(state).getCompoundTag("xat:Trinket");
        assertNotSame(second.getTag("mana"), third.getTag("mana"));
        assertEquals(0.0f, second.getFloat("mana"), 0.0f);
        assertEquals(50.0f, third.getFloat("mana"), 0.0f);
    }

    @Test public void completeFlushRemovesOldKeysAndReadsUnsavedCounterFlags() throws Exception {
        Runtime plain = new Runtime(false), fast = new Runtime(true);
        Object left = plain.state(), right = fast.state();
        for (Object state : new Object[] {left, right}) {
            Object counter = counters(state).get("clock");
            field(counter, "saveNBT").setBoolean(counter, false);
        }
        NBTTagCompound old = fast.write(right).getCompoundTag("xat:Trinket");
        assertTrue(old.getCompoundTag("Counters").isEmpty());
        old.setString("unknown", "obsolete");
        NBTTagCompound root = new NBTTagCompound(); root.setTag("xat:Trinket", old); root.setInteger("outside", 7);
        fast.write(right, root);
        assertNotSame(old, root.getTag("xat:Trinket"));
        assertFalse(root.getCompoundTag("xat:Trinket").hasKey("unknown"));
        assertEquals(7, root.getInteger("outside"));
        assertArrayEquals(encoded(plain.write(left)), encoded(fast.write(right)));
        enabled(false);
        NBTTagCompound a = fast.write(right), b = fast.write(right);
        assertNotSame(a.getCompoundTag("xat:Trinket").getTag("variant"), b.getCompoundTag("xat:Trinket").getTag("variant"));
        enabled(true);
        assertArrayEquals(encoded(plain.write(left)), encoded(fast.write(right)));
    }

    @Test public void counterEntriesKeepTheirOriginalSixLiveReads() throws Exception {
        Runtime plain = new Runtime(false), fast = new Runtime(true);
        Object left = plain.state(), right = fast.state();
        int[] leftReads = installChangingEntry(left), rightReads = installChangingEntry(right);
        assertArrayEquals(encoded(plain.write(left)), encoded(fast.write(right)));
        assertEquals(6, leftReads[0]); assertEquals(6, rightReads[0]);
        assertArrayEquals(encoded(plain.write(left)), encoded(fast.write(right)));
        assertEquals(12, leftReads[0]); assertEquals(12, rightReads[0]);
    }

    private static int[] installChangingEntry(Object state) throws Exception {
        final Object first = counters(state).get("clock");
        final Object second = first.getClass().getConstructor(String.class, int.class,
            boolean.class, boolean.class, boolean.class, boolean.class).newInstance("second", 91, true, false, false, true);
        final int[] reads = {0};
        final Map.Entry<String, Object> entry = new java.util.AbstractMap.SimpleEntry<String, Object>("clock", first) {
            @Override public Object getValue() { return (reads[0]++ & 1) == 0 ? first : second; }
        };
        Map<String, Object> changing = new java.util.AbstractMap<String, Object>() {
            @Override public java.util.Set<Map.Entry<String, Object>> entrySet() {
                return java.util.Collections.singleton(entry);
            }
        };
        Object ticks = field(state, "tickHandler").get(state);
        field(ticks, "Counters").set(ticks, changing);
        return reads;
    }

    @Test public void scalarEncodingKeepsSignedZeroNanBitsAndNullStringFailure() {
        TrinketsStateBridge.ScalarCache cache = new TrinketsStateBridge.ScalarCache(1);
        NBTTagCompound tag = new NBTTagCompound();
        for (int bits : new int[] {0, 0x80000000, 0x7fc01234, 0x7fc05678}) {
            TrinketsStateBridge.setFloat(tag, "v", Float.intBitsToFloat(bits), cache, 0);
            assertEquals(bits, Float.floatToRawIntBits(tag.getFloat("v")));
        }
        try { TrinketsStateBridge.setString(tag, "null", null, cache, 0); fail("null must fail"); }
        catch (NullPointerException expected) { assertEquals("Null string not allowed", expected.getMessage()); }
    }

    /** Optional local probe: the real installed writer body, without gameplay or test instrumentation. */
    public static void main(String[] arguments) throws Exception {
        enabled(true);
        NBTTagCompound mapSample = new NBTTagCompound();
        Field backingMap = NBTTagCompound.class.getDeclaredField("tagMap"); backingMap.setAccessible(true);
        System.out.println("backingMap=" + backingMap.get(mapSample).getClass().getName());
        Runtime plain = new Runtime(false), fast = new Runtime(true);
        Object left = plain.state(), right = fast.state();
        Method original = left.getClass().getMethod("saveToNBT", NBTTagCompound.class);
        Method optimized = right.getClass().getMethod("saveToNBT", NBTTagCompound.class);
        for (int i = 0; i < 50000; i++) {
            probeResult = original.invoke(left, new NBTTagCompound());
            probeResult = optimized.invoke(right, new NBTTagCompound());
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int round = 0; round < 4; round++) {
            probe("original", original, left, bean);
            probe("optimized", optimized, right, bean);
        }
    }

    private static void probe(String name, Method writer, Object state, com.sun.management.ThreadMXBean bean) throws Exception {
        int iterations = 100000;
        long thread = Thread.currentThread().getId(), bytes = bean.getThreadAllocatedBytes(thread), begin = System.nanoTime();
        for (int i = 0; i < iterations; i++) probeResult = writer.invoke(state, new NBTTagCompound());
        long elapsed = System.nanoTime() - begin, allocated = bean.getThreadAllocatedBytes(thread) - bytes;
        System.out.println(name + ": " + elapsed / iterations + " ns/write, " + allocated / iterations + " B/write");
    }

    private static void enabled(boolean active) {
        OptimizationModule module = OptimizationModule.TRINKETS_STATE_NBT;
        OptimizerRegistry.breaker(module).configure(active, 3);
        if (active) OptimizerRegistry.breaker(module).patchInstalled(TrinketsStateAdapter.PROPERTIES, "test");
    }
    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> counters(Object state) throws Exception {
        Object ticks = field(state, "tickHandler").get(state);
        return (Map<String, Object>) ticks.getClass().getMethod("getCounters").invoke(ticks);
    }
    private static byte[] encoded(NBTTagCompound tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, bytes); return bytes.toByteArray();
    }
    private static byte[] original(String name) throws Exception {
        String configured = System.getProperty("ice.trinkets.jar");
        Assume.assumeTrue("configure ice.trinkets.jar", configured != null);
        try (JarFile jar = new JarFile(configured); InputStream in = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192];
            for (int read; (read = in.read(block)) >= 0;) bytes.write(block, 0, read);
            return bytes.toByteArray();
        }
    }
    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }
    private static int calls(byte[] bytes, String name) {
        int count = 0;
        for (MethodNode method : read(bytes).methods) for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof MethodInsnNode && name.equals(((MethodInsnNode) instruction).name)) count++;
        return count;
    }

    public static final class FixtureSlot {
        public int slot = 2; public String handler = "baubles";
        public int getSlot() { return slot; } public String getHandler() { return handler; }
        public void setSlot(int value) { slot = value; } public void setHandler(String value) { handler = value; }
    }
    public static final class FixtureElement {
        private static final Map<String, FixtureElement> REGISTRY = new HashMap<String, FixtureElement>();
        private final int id; private final ResourceLocation name;
        public FixtureElement(int id, String name) { this.id = id; this.name = new ResourceLocation(name); REGISTRY.put(name, this); }
        public int getID() { return id; } public ResourceLocation getRegistryName() { return name; }
        public static FixtureElement getByNameOrId(String name) { return REGISTRY.get(name); }
    }
    public static final class FixtureElements {
        public static final FixtureElement NEUTRAL = new FixtureElement(0, "ice_test:neutral");
    }

    private static final class Runtime extends ClassLoader {
        private final boolean optimized;
        Runtime(boolean optimized) { super(TrinketsStateAdapterTest.class.getClassLoader()); this.optimized = optimized; }
        Class<?> type(String internal) throws Exception { return loadClass(internal.replace('/', '.')); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!Arrays.asList(TARGETS).contains(name.replace('.', '/'))) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) type = findClass(name);
            if (resolve) resolveClass(type);
            return type;
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            if (!Arrays.asList(TARGETS).contains(internal)) throw new ClassNotFoundException(name);
            try {
                byte[] bytes = original(internal);
                if (optimized) bytes = new TrinketsStateAdapter().transform(internal, bytes, null);
                ClassNode node = read(bytes);
                if (TrinketsStateAdapter.PROPERTIES.equals(internal)) {
                    node.superName = "java/lang/Object"; node.signature = null;
                    for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext();) {
                        String method = iterator.next().name;
                        if (!"saveToNBT".equals(method) && !"loadFromNBT".equals(method)
                            && !method.startsWith("ice$trinketsScalarCache")) iterator.remove();
                    }
                    node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "tickHandler", "L" + TrinketsStateAdapter.TICKS + ";", null, null));
                    MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                    init.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(init);
                }
                ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                    @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
                };
                node.accept(new ClassRemapper(output, new Remapper() {
                    @Override public String map(String name) {
                        if (ELEMENT.equals(name)) return Type.getInternalName(FixtureElement.class);
                        if (ELEMENT_REGISTRY.equals(name)) return Type.getInternalName(FixtureElements.class);
                        if (SLOT.equals(name)) return Type.getInternalName(FixtureSlot.class);
                        return name;
                    }
                    @Override public String mapMethodName(String owner, String method, String descriptor) {
                        String[] srg = {"func_74768_a", "func_74776_a", "func_74757_a", "func_74778_a", "func_74782_a",
                            "func_74764_b", "func_74775_l", "func_74762_e", "func_74760_g", "func_74767_n", "func_74779_i", "func_82582_d", "func_150296_c"};
                        String[] mcp = {"setInteger", "setFloat", "setBoolean", "setString", "setTag", "hasKey",
                            "getCompoundTag", "getInteger", "getFloat", "getBoolean", "getString", "isEmpty", "getKeySet"};
                        for (int i = 0; i < srg.length; i++) if (srg[i].equals(method)) return mcp[i];
                        return method;
                    }
                }));
                bytes = output.toByteArray(); return defineClass(name, bytes, 0, bytes.length);
            } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
        }
        Object state() throws Exception {
            Object value = type(TrinketsStateAdapter.PROPERTIES).newInstance();
            field(value, "slotInfo").set(value, new FixtureSlot());
            field(value, "crafter").set(value, "maker"); field(value, "crafterUUID").set(value, "uuid");
            field(value, "elements").set(value, type(TrinketsStateAdapter.ELEMENTS).newInstance());
            Object ticks = type(TrinketsStateAdapter.TICKS).newInstance(); field(value, "tickHandler").set(value, ticks);
            Object counter = type(TrinketsStateAdapter.COUNTER).getConstructor(String.class, int.class,
                boolean.class, boolean.class, boolean.class, boolean.class).newInstance("clock", 60, true, true, false, true);
            counters(value).put("clock", counter); return value;
        }
        NBTTagCompound write(Object state) throws Exception { return write(state, new NBTTagCompound()); }
        NBTTagCompound write(Object state, NBTTagCompound destination) throws Exception {
            return (NBTTagCompound) state.getClass().getMethod("saveToNBT", NBTTagCompound.class).invoke(state, destination);
        }
    }
}

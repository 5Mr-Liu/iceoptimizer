package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumHandSide;
import net.minecraft.world.World;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Executes the installed mod's actual helper, with only SRG names mapped to the dev runtime. */
public class QualityToolsModifierAdapterTest {
    private boolean enabled;
    private boolean module;
    private static final String ATTRIBUTE = "generic.maxHealth";
    private static final UUID ID = new UUID(123L, 456L);

    @Before public void setup() {
        Bootstrap.register();
        enabled = OptimizerConfig.settings.enabled;
        module = OptimizerConfig.settings.qualityToolsEntityAttributes;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.qualityToolsEntityAttributes = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        String hash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        OptimizerRegistry.targetObserved("qualitytools-attributes", "test.QualityHelper", hash, true);
        OptimizerRegistry.patchInstalled("qualitytools-attributes", "test.QualityHelper", hash);
    }

    @After public void restore() {
        OptimizerConfig.settings.enabled = enabled;
        OptimizerConfig.settings.qualityToolsEntityAttributes = module;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void verifiesActualHelperAndRetainsOriginalParserAndAttributeCallbacks() throws Exception {
        byte[] changed = transform(original());
        ClassNode node = read(changed);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
                new Analyzer(new BasicVerifier()).analyze(node.name, method);
            }
        }
        MethodNode method = find(node, QualityToolsModifierAdapter.METHOD);
        assertEquals(1, calls(method, QualityToolsModifierAdapter.BRIDGE, "retainExistingModifier"));
        assertEquals(1, calls(method, "net/minecraft/entity/SharedMonsterAttributes", "func_111259_a"));
        assertEquals(1, calls(method, "net/minecraft/entity/ai/attributes/IAttributeInstance", "func_111121_a"));
        assertEquals("no extra custom list reads", 2,
            calls(method, "net/minecraft/nbt/NBTTagList", "func_150305_b"));
        try {
            transform(changed);
            fail("duplicate insertion must fail closed");
        } catch (IllegalStateException expected) { }
    }

    @Test public void rejectsAnAddedCallbackInsideTheSkippedRegion() throws Exception {
        ClassNode node = read(original());
        MethodNode method = find(node, QualityToolsModifierAdapter.METHOD);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode
                && "func_111259_a".equals(((MethodInsnNode) instruction).name)) {
                InsnList callback = new InsnList();
                callback.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
                callback.add(new InsnNode(Opcodes.POP2));
                method.instructions.insert(instruction, callback);
                break;
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        try {
            transform(writer.toByteArray());
            fail("must not silently skip another transformer's callback");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("audited"));
        }
    }

    @Test public void actualHelperPreservesSlotsExternalChangesAndSameUuidSemantics() throws Exception {
        for (boolean optimized : new boolean[] { false, true }) {
            Method helper = executable(optimized);
            TestLiving entity = new TestLiving();
            ItemStack item = item();
            NBTTagCompound modifierTag = item.getSubCompound("Quality").getTagList("AttributeModifiers", 10).getCompoundTagAt(0);
            Multimap<String, AttributeModifier> unused = HashMultimap.create();
            IAttributeInstance attribute = entity.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
            helper.invoke(null, entity, item, "head", unused);
            assertNull(attribute.getModifier(ID));
            helper.invoke(null, entity, item, "baubles_ring", unused);
            AttributeModifier applied = attribute.getModifier(ID);
            assertNotNull(applied);
            applied.setSaved(false);
            unused.put(ATTRIBUTE, applied);
            modifierTag.setDouble("Amount", Double.NaN);
            modifierTag.setInteger("Operation", 2);
            helper.invoke(null, entity, item, "BAUBLES_RING", unused);
            assertTrue(unused.isEmpty());
            assertSame(applied, attribute.getModifier(ID));
            assertFalse(applied.isSaved());
            assertEquals(2D, applied.getAmount(), 0D);
            // External removal must be repaired at the next original refresh, without a stale cache.
            attribute.removeModifier(applied);
            helper.invoke(null, entity, item, "baubles_ring", unused);
            AttributeModifier replacement = attribute.getModifier(ID);
            assertNotSame(applied, replacement);
            assertTrue(Double.isNaN(replacement.getAmount()));
            assertEquals(2, replacement.getOperation());
            assertTrue(replacement.isSaved());
            unused.put(ATTRIBUTE, replacement);
            modifierTag.setLong("UUIDLeast", 999L);
            helper.invoke(null, entity, item, "baubles_ring", unused);
            assertTrue(unused.containsEntry(ATTRIBUTE, replacement));
            assertNotNull(attribute.getModifier(new UUID(ID.getMostSignificantBits(), 999L)));
        }
    }

    @Test public void disabledHookAndCustomCompoundKeepOriginalReadBehavior() throws Exception {
        for (boolean disabled : new boolean[] { false, true }) {
            OptimizerConfig.settings.qualityToolsEntityAttributes = !disabled;
            OptimizerRegistry.configure(ClientOptimizerConfig.capture());
            if (!disabled) {
                String hash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
                OptimizerRegistry.targetObserved("qualitytools-attributes", "test.QualityHelper", hash, true);
                OptimizerRegistry.patchInstalled("qualitytools-attributes", "test.QualityHelper", hash);
            }
            Method helper = executable(true);
            TestLiving entity = new TestLiving();
            ItemStack item = item();
            final int[] uniqueReads = { 0 };
            NBTTagCompound custom = new NBTTagCompound() {
                @Override public UUID getUniqueId(String name) { uniqueReads[0]++; return super.getUniqueId(name); }
            };
            custom.merge(item.getSubCompound("Quality").getTagList("AttributeModifiers", 10).getCompoundTagAt(0));
            NBTTagList list = new NBTTagList(); list.appendTag(custom);
            item.getSubCompound("Quality").setTag("AttributeModifiers", list);
            Multimap<String, AttributeModifier> unused = HashMultimap.create();
            unused.put(ATTRIBUTE, new AttributeModifier(ID, "qualitytools", 2D, 0));
            helper.invoke(null, entity, item, "baubles_ring", unused);
            assertEquals(1, uniqueReads[0]);
            assertTrue(unused.isEmpty());
        }
    }

    @Test public void customListStillReceivesBothOriginalReads() throws Exception {
        Method helper = executable(true);
        TestLiving entity = new TestLiving();
        ItemStack item = item();
        final int[] reads = { 0 };
        NBTTagList custom = new NBTTagList() {
            @Override public NBTTagCompound getCompoundTagAt(int index) {
                reads[0]++;
                return super.getCompoundTagAt(index);
            }
        };
        custom.appendTag(item.getSubCompound("Quality").getTagList("AttributeModifiers", 10).getCompoundTagAt(0));
        item.getSubCompound("Quality").setTag("AttributeModifiers", custom);
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        unused.put(ATTRIBUTE, new AttributeModifier(ID, "qualitytools", 2D, 0));
        helper.invoke(null, entity, item, "baubles_ring", unused);
        assertEquals(2, reads[0]);
        assertTrue(unused.isEmpty());
    }

    @Test public void reportsActualHelperAllocationForStableModifiers() throws Exception {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) bean;
        Assume.assumeTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        Method baseline = executable(false), optimized = executable(true);
        long[][] stable = measureScenario("stable hit", baseline, optimized, allocation, 1, true);
        assertTrue("the retained modifier path must reduce temporary allocation", stable[1][0] < stable[0][0]);
        measureScenario("4 candidates", baseline, optimized, allocation, 3, true);
        measureScenario("32 other modifiers", baseline, optimized, allocation, 32, true);
        measureScenario("new modifier", baseline, optimized, allocation, 0, false);
    }

    private static long[][] measureScenario(String scenario, Method baseline, Method optimized,
            com.sun.management.ThreadMXBean allocation, int otherModifiers, boolean existingHit) throws Exception {
        final int rounds = 7;
        long[][] baselineSamples = new long[rounds][];
        long[][] optimizedSamples = new long[rounds][];
        // Keep the same loaded methods for every round. Four discarded paired rounds,
        // plus measure's per-sample warmup, let both implementations reach steady compilation.
        for (int round = -4; round < rounds; round++) {
            long[] baseSample;
            long[] optimizedSample;
            if ((round & 1) == 0) {
                baseSample = measure(baseline, allocation, otherModifiers, existingHit);
                optimizedSample = measure(optimized, allocation, otherModifiers, existingHit);
            } else {
                optimizedSample = measure(optimized, allocation, otherModifiers, existingHit);
                baseSample = measure(baseline, allocation, otherModifiers, existingHit);
            }
            if (round >= 0) {
                baselineSamples[round] = baseSample;
                optimizedSamples[round] = optimizedSample;
            }
        }
        long[] baseMedian = medians(baselineSamples);
        long[] optimizedMedian = medians(optimizedSamples);
        report(scenario, baseMedian, optimizedMedian);
        return new long[][] { baseMedian, optimizedMedian };
    }

    private static long[] medians(long[][] samples) {
        long[] bytes = new long[samples.length];
        long[] nanos = new long[samples.length];
        for (int index = 0; index < samples.length; index++) {
            bytes[index] = samples[index][0];
            nanos[index] = samples[index][1];
        }
        Arrays.sort(bytes);
        Arrays.sort(nanos);
        return new long[] { bytes[samples.length / 2], nanos[samples.length / 2] };
    }

    private static long[] measure(Method helper, com.sun.management.ThreadMXBean allocation, int otherModifiers,
            boolean existingHit) throws Exception {
        TestLiving entity = new TestLiving();
        ItemStack item = item();
        AttributeModifier existing = new AttributeModifier(ID, "qualitytools", 2D, 0);
        Multimap<String, AttributeModifier> unused = HashMultimap.create();
        for (int i = 0; i < otherModifiers; i++) {
            unused.put(ATTRIBUTE, new AttributeModifier(new UUID(i + 1000L, 5L), "qualitytools", 3D, 0));
        }
        IAttributeInstance target = entity.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
        Object[] args = { entity, item, "baubles_ring", unused };
        for (int i = 0; i < 20000; i++) {
            if (existingHit) unused.put(ATTRIBUTE, existing); else target.removeModifier(existing);
            helper.invoke(null, args);
        }
        long id = Thread.currentThread().getId();
        long start = allocation.getThreadAllocatedBytes(id);
        long startTime = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            if (existingHit) unused.put(ATTRIBUTE, existing); else target.removeModifier(existing);
            helper.invoke(null, args);
        }
        return new long[] { allocation.getThreadAllocatedBytes(id) - start, System.nanoTime() - startTime };
    }

    private static void report(String scenario, long[] baseline, long[] optimized) {
        System.out.println("QualityTools actual helper " + scenario + " (median of 7 alternating rounds): original=" + baseline[0] / 20000D
            + " B/call, " + baseline[1] / 20000D + " ns/call; optimized=" + optimized[0] / 20000D
            + " B/call, " + optimized[1] / 20000D + " ns/call (microbenchmark, not gameplay FPS)");
    }

    private static ItemStack item() {
        ItemStack stack = new ItemStack(new Item());
        NBTTagCompound tag = SharedMonsterAttributes.writeAttributeModifierToNBT(new AttributeModifier(ID, "qualitytools", 2D, 0));
        tag.setString("AttributeName", ATTRIBUTE);
        NBTTagList attributes = new NBTTagList(); attributes.appendTag(tag);
        NBTTagList slots = new NBTTagList(); slots.appendTag(new NBTTagString("baubles_ring"));
        NBTTagCompound quality = new NBTTagCompound();
        quality.setTag("AttributeModifiers", attributes); quality.setTag("Slots", slots);
        NBTTagCompound root = new NBTTagCompound(); root.setTag("Quality", quality); stack.setTagCompound(root);
        return stack;
    }

    private static byte[] original() throws Exception {
        String path = System.getProperty("ice.qualitytools.jar", "");
        Assume.assumeFalse("configure ice.qualitytools.jar", path.isEmpty());
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(QualityToolsModifierAdapter.TARGET + ".class"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192];
            for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] transform(byte[] original) {
        return new QualityToolsModifierAdapter().transform(QualityToolsModifierAdapter.TARGET.replace('/', '.'), original, null);
    }

    private static Method executable(boolean optimized) throws Exception {
        byte[] bytes = original();
        ClassNode node = read(optimized ? transform(bytes) : bytes);
        for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext();) {
            String name = iterator.next().name;
            if (!QualityToolsModifierAdapter.METHOD.equals(name) && !"getQualityTag".equals(name)) iterator.remove();
        }
        final Map<String, String> names = new HashMap<String, String>();
        String[][] pairs = {
            {"func_190926_b", "isEmpty"}, {"func_77942_o", "hasTagCompound"},
            {"func_77978_p", "getTagCompound"}, {"func_179543_a", "getSubCompound"},
            {"func_150297_b", "hasKey"}, {"func_82582_d", "isEmpty"},
            {"func_150295_c", "getTagList"}, {"func_74745_c", "tagCount"},
            {"func_150307_f", "getStringTagAt"}, {"func_150305_b", "getCompoundTagAt"},
            {"func_111259_a", "readAttributeModifierFromNBT"}, {"func_74779_i", "getString"},
            {"func_110140_aT", "getAttributeMap"}, {"func_111152_a", "getAttributeInstanceByName"},
            {"func_180374_a", "hasModifier"}, {"func_111121_a", "applyModifier"}
        };
        for (String[] pair : pairs) names.put(pair[0], pair[1]);
        ClassWriter writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                String mapped = names.get(name); return mapped == null ? name : mapped;
            }
        }));
        final byte[] executable = writer.toByteArray();
        Class<?> helper = new ClassLoader(QualityToolsModifierAdapterTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, executable, 0, executable.length); }
        }.define();
        return helper.getMethod(QualityToolsModifierAdapter.METHOD, EntityLivingBase.class, ItemStack.class, String.class, Multimap.class);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }
    private static MethodNode find(ClassNode node, String name) {
        for (MethodNode method : node.methods) if (name.equals(method.name)) return method;
        throw new AssertionError(name);
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode node : method.instructions.toArray()) if (node instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) node; if (owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static final class TestLiving extends EntityLivingBase {
        TestLiving() { super((World) null); }
        @Override public Iterable<ItemStack> getArmorInventoryList() { return Arrays.asList(ItemStack.EMPTY); }
        @Override public ItemStack getItemStackFromSlot(EntityEquipmentSlot slot) { return ItemStack.EMPTY; }
        @Override public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack) { }
        @Override public EnumHandSide getPrimaryHand() { return EnumHandSide.RIGHT; }
    }
}

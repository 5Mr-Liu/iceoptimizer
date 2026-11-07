package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.compat.nbt.CapabilityStateBridge;
import dev.rlcraft.ice.optimizer.compat.nbt.CapabilityStateProviderAccess;
import dev.rlcraft.ice.optimizer.compat.nbt.CapabilityStateStackAccess;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Real provider/dispatcher bytecode, with only quiver stack storage replaced by a fixture. */
public class CapabilityStateAdapterTest {
    private boolean previousGlobal;
    private boolean previousState;
    private static final String ENERGY = CapabilityStateProviderAdapter.ENERGY_TARGET;
    private static final String QUIVER = CapabilityStateProviderAdapter.QUIVER_TARGET;
    private static final String ENERGY_STORAGE = "cursedflames/bountifulbaubles/capability/CLEnergyStorage";
    private static final String BRIDGE_FIXTURE = CapabilityStateProviderAdapter.BRIDGE + "Fixture";
    private static Runtime activeRuntime;

    public static void recordSerialization(Object provider) {
        Runtime runtime = activeRuntime;
        if (runtime != null) {
            runtime.totalSerializations++;
            runtime.serializationCounts.put(provider, Integer.valueOf(runtime.serializations(provider) + 1));
        }
    }

    @BeforeClass public static void initializeGame() { Bootstrap.register(); }

    @Before public void enableState() {
        previousGlobal = OptimizerConfig.settings.enabled;
        previousState = OptimizerConfig.settings.capabilityStateNbt;
        configure(true);
    }

    @After public void restoreState() {
        OptimizerConfig.settings.enabled = previousGlobal;
        OptimizerConfig.settings.capabilityStateNbt = previousState;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void actualJarAdaptersVerifyAndComposeWithExistingComparison() throws Exception {
        verify(new CapabilityStateProviderAdapter().transform(ENERGY, jar("ice.bountifulbaubles.jar", ENERGY), null));
        verify(new CapabilityStateProviderAdapter().transform(QUIVER, jar("ice.spartanweaponry.jar", QUIVER), null));
        byte[] dispatcher = resource(CapabilityDispatcher.class);
        byte[] oldFirst = new CapabilityNbtAdapter().transform("", dispatcher, null);
        verify(new CapabilityStateDispatcherAdapter().transform("", oldFirst, null));
        byte[] stateFirst = new CapabilityStateDispatcherAdapter().transform("", dispatcher, null);
        verify(new CapabilityNbtAdapter().transform("", stateFirst, null));
        verify(new CapabilityStateStackAdapter().transform("", resource(ItemStack.class), null));
        verify(new CapabilityStateInventoryAdapter().transform("", resource(ItemStackHandler.class), null));
        String srg = System.getProperty("ice.minecraft.srg.jar", "");
        if (!srg.isEmpty()) {
            verify(new CapabilityStateStackAdapter().transform("", entry(srg, CapabilityStateStackAdapter.TARGET), null));
            verify(new CapabilityStateInventoryAdapter().transform("", entry(srg, CapabilityStateInventoryAdapter.TARGET), null));
        }
    }

    @Test public void actualFlywheelStateMatchesSerializationWithoutBuildingTags() throws Exception {
        Runtime runtime = new Runtime();
        Object leftProvider = runtime.energy(75);
        Object rightProvider = runtime.energy(75);
        Object left = runtime.dispatch(leftProvider);
        Object right = runtime.dispatch(rightProvider);
        assertTrue(runtime.compatible(left, right));
        assertTrue(runtime.baseline(left, right));
        runtime.energyValue(rightProvider, 80);
        assertFalse(runtime.compatible(left, right));
        assertFalse(runtime.baseline(left, right));
        assertEquals(0, runtime.serializations(leftProvider));
        assertEquals(0, runtime.serializations(rightProvider));
        configure(false);
        assertFalse(runtime.compatible(left, right));
        assertEquals(1, runtime.serializations(leftProvider));
        assertEquals(1, runtime.serializations(rightProvider));
    }

    @Test public void mixedUnknownProvidersKeepAllOriginalSerializationInOrder() throws Exception {
        Runtime runtime = new Runtime();
        List<String> calls = new ArrayList<String>();
        Object left = runtime.dispatch(new Unknown("L", calls, 1), runtime.energy(7));
        Object right = runtime.dispatch(new Unknown("R", calls, 2), runtime.energy(8));
        assertFalse(runtime.compatible(left, right));
        assertEquals(Arrays.asList("L", "R"), calls);
        assertEquals(2, runtime.totalSerializations);
    }

    @Test public void quiverComparisonMatchesSerializedSlotsAndInitializesBothOwners() throws Exception {
        Runtime runtime = new Runtime();
        FixtureStack ownerA = stack(1, 0);
        FixtureStack ownerB = stack(1, 0);
        Object qa = runtime.quiver(ownerA, 3);
        Object qb = runtime.quiver(ownerB, 3);
        FixtureInventory a = runtime.inventory(qa);
        FixtureInventory b = runtime.inventory(qb);
        a.values[1] = stack(1, 1);
        b.values[1] = stack(257, 65537);
        assertTrue(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        assertNotNull(ownerA.tag);
        assertNotNull(ownerB.tag);
        assertEquals(a.serializeNBT(), b.serializeNBT());
        assertEquals(0, runtime.serializations(qa));
        assertEquals(0, runtime.serializations(qb));
        b.values[1].tag = new NBTTagCompound();
        b.values[1].tag.setInteger("changed", 1);
        assertFalse(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        a.values[1].tag = b.values[1].tag.copy();
        assertTrue(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        b.values[1].tag.setInteger("changed", 2);
        assertFalse(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        b.values[2] = b.values[1];
        b.values[1] = FixtureStack.EMPTY;
        assertFalse(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        a.values[1] = new FixtureStack(new Item(), 1, 0);
        b.values[1] = new FixtureStack(new Item(), 1, 0);
        b.values[2] = FixtureStack.EMPTY;
        // Distinct unregistered items both serialize as minecraft:air.
        assertTrue(runtime.compatible(runtime.dispatch(qa), runtime.dispatch(qb)));
        assertEquals(a.serializeNBT(), b.serializeNBT());
    }

    @Test public void nestedCapabilityFallsBackBeforeAnyQuiverPreparation() throws Exception {
        Runtime runtime = new Runtime();
        Object qa = runtime.quiver(stack(1, 0), 1);
        Object qb = runtime.quiver(stack(1, 0), 1);
        FixtureStack child = stack(1, 0);
        child.capabilities = new CapabilityDispatcher(new HashMap<ResourceLocation, ICapabilityProvider>());
        runtime.inventory(qa).values[0] = child;
        Object left = runtime.dispatch(qa), right = runtime.dispatch(qb);
        assertEquals(CapabilityStateBridge.UNKNOWN, runtime.compare(left, right));
        assertNull(((FixtureStack) ((CapabilityStateProviderAccess) qa).ice$stateOwner()).tag);
        assertFalse(runtime.compatible(left, right));
        assertEquals(1, runtime.serializations(qa));
        assertEquals(1, runtime.serializations(qb));
    }

    @Test public void deferredNormalAsmPayloadDoesNotLookLikeAnAbsentCapability() throws Exception {
        Runtime runtime = new Runtime();
        Object qa = runtime.quiver(stack(1, 0), 1);
        Object qb = runtime.quiver(stack(1, 0), 1);
        FixtureStack child = stack(1, 0);
        child.pendingNbt = new NBTTagCompound();
        child.pendingNbt.setInteger("amount", 25);
        runtime.inventory(qa).values[0] = child;
        assertEquals(CapabilityStateBridge.UNKNOWN, runtime.compare(runtime.dispatch(qa), runtime.dispatch(qb)));
        assertNull(((FixtureStack) ((CapabilityStateProviderAccess) qa).ice$stateOwner()).tag);
    }

    @Test public void scalarSnapshotsKeepFreshContainersAndPreviousValues() throws Exception {
        Runtime runtime = new Runtime();
        Object provider = runtime.energy(60);
        NBTTagCompound first = runtime.serialize(provider);
        NBTTagCompound second = runtime.serialize(provider);
        assertNotSame(first, second);
        assertSame(first.getTag("amount"), second.getTag("amount"));
        runtime.energyValue(provider, 61);
        NBTTagCompound third = runtime.serialize(provider);
        assertEquals(60, first.getInteger("amount"));
        assertEquals(61, third.getInteger("amount"));
        second.setInteger("amount", 999);
        assertEquals(60, first.getInteger("amount"));
        configure(false);
        assertNotSame(runtime.serialize(provider).getTag("amount"), runtime.serialize(provider).getTag("amount"));
    }

    @Test public void actualInventorySnapshotReusesOnlyImmutableSlotAndSizeLeaves() throws Exception {
        final byte[] transformed = new CapabilityStateInventoryAdapter().transform("", resource(ItemStackHandler.class), null);
        Class<?> inventoryClass = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(CapabilityStateInventoryAdapter.TARGET.replace('/', '.'), transformed, 0, transformed.length); }
        }.define();
        Object inventory = inventoryClass.getConstructor(int.class).newInstance(4);
        ItemStack arrows = new ItemStack(Items.ARROW, 12);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("nested", 7);
        arrows.setTagCompound(payload);
        inventoryClass.getMethod("setStackInSlot", int.class, ItemStack.class)
            .invoke(inventory, 2, arrows);
        Method serialize = inventoryClass.getMethod("serializeNBT");
        NBTTagCompound first = (NBTTagCompound) serialize.invoke(inventory);
        NBTTagCompound second = (NBTTagCompound) serialize.invoke(inventory);
        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.getTag("Items"), second.getTag("Items"));
        NBTTagCompound a = first.getTagList("Items", 10).getCompoundTagAt(0);
        NBTTagCompound b = second.getTagList("Items", 10).getCompoundTagAt(0);
        assertNotSame(a, b);
        assertSame(a.getTag("Slot"), b.getTag("Slot"));
        assertSame(first.getTag("Size"), second.getTag("Size"));
        // Vanilla writeToNBT shares the existing stack tag; keep that ownership.
        assertSame(payload, a.getTag("tag"));
        assertSame(payload, b.getTag("tag"));
        a.setInteger("Slot", 99);
        assertEquals(2, b.getInteger("Slot"));
        configure(false);
        assertEquals(second, serialize.invoke(inventory));
    }

    @Test public void inventoryCopySnapshotAllocationProbe() throws Exception {
        java.lang.management.ThreadMXBean threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(threadBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocations = (com.sun.management.ThreadMXBean) threadBean;
        Assume.assumeTrue(allocations.isThreadAllocatedMemorySupported());
        allocations.setThreadAllocatedMemoryEnabled(true);
        final byte[] transformed = new CapabilityStateInventoryAdapter().transform("", resource(ItemStackHandler.class), null);
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(CapabilityStateInventoryAdapter.TARGET.replace('/', '.'), transformed, 0, transformed.length); }
        }.define();
        Object inventory = type.getConstructor(int.class).newInstance(12);
        Method set = type.getMethod("setStackInSlot", int.class, ItemStack.class);
        for (int slot = 0; slot < 12; slot++) set.invoke(inventory, slot, new ItemStack(Items.ARROW, slot + 1));
        Method serialize = type.getMethod("serializeNBT");
        for (boolean enabled : new boolean[] { false, true }) {
            configure(enabled);
            for (int i = 0; i < 1500; i++) serialize.invoke(inventory);
        }
        long[] baseline = snapshotMeasurement(allocations, serialize, inventory, false);
        long[] optimized = snapshotMeasurement(allocations, serialize, inventory, true);
        printProbe("inventory copy snapshot", baseline, optimized);
        assertTrue("the real snapshot path must allocate fewer bytes", optimized[0] < baseline[0]);
        if (!System.getProperty("ice.bountifulbaubles.jar", "").isEmpty()
            && !System.getProperty("ice.spartanweaponry.jar", "").isEmpty()) {
            Runtime runtime = new Runtime();
            Object energyA = runtime.dispatch(runtime.energy(75));
            Object energyB = runtime.dispatch(runtime.energy(75));
            comparisonProbe(allocations, runtime, "flywheel state comparison", energyA, energyB);
            Object quiverA = runtime.quiver(stack(1, 0), 12);
            Object quiverB = runtime.quiver(stack(1, 0), 12);
            for (int slot = 0; slot < 12; slot++) {
                runtime.inventory(quiverA).values[slot] = stack(slot + 1, 0);
                runtime.inventory(quiverB).values[slot] = stack(slot + 1, 0);
            }
            comparisonProbe(allocations, runtime, "quiver state comparison fixture",
                runtime.dispatch(quiverA), runtime.dispatch(quiverB));
        }
    }

    private static long[] snapshotMeasurement(com.sun.management.ThreadMXBean bean, Method serialize,
        Object inventory, boolean enabled) throws Exception {
        configure(enabled);
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        int size = 0;
        for (int i = 0; i < 2000; i++) size += ((NBTTagCompound) serialize.invoke(inventory)).getInteger("Size");
        long nanos = System.nanoTime() - start;
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(24000, size);
        return new long[] { bytes / 2000, nanos / 2000 };
    }

    private static void comparisonProbe(com.sun.management.ThreadMXBean bean, Runtime runtime,
        String label, Object left, Object right) throws Exception {
        Method compatible = runtime.dispatcherClass.getMethod("areCompatible", runtime.dispatcherClass);
        Object[] arguments = { right };
        Runtime tracking = activeRuntime;
        activeRuntime = null;
        try {
            for (boolean enabled : new boolean[] { false, true }) {
                configure(enabled);
                for (int i = 0; i < 2500; i++) compatible.invoke(left, arguments);
            }
            long[] baseline = comparisonMeasurement(bean, compatible, left, arguments, false);
            long[] optimized = comparisonMeasurement(bean, compatible, left, arguments, true);
            printProbe(label, baseline, optimized);
            assertTrue("direct state comparison must avoid the snapshot allocation", optimized[0] < baseline[0]);
        } finally {
            activeRuntime = tracking;
        }
    }

    private static long[] comparisonMeasurement(com.sun.management.ThreadMXBean bean, Method method,
        Object left, Object[] arguments, boolean enabled) throws Exception {
        configure(enabled);
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        int equal = 0;
        for (int i = 0; i < 2500; i++) if ((Boolean) method.invoke(left, arguments)) equal++;
        long nanos = System.nanoTime() - start;
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(2500, equal);
        return new long[] { bytes / 2500, nanos / 2500 };
    }

    private static void printProbe(String label, long[] baseline, long[] optimized) {
        System.out.println("Capability " + label + ": B/op " + baseline[0] + " -> " + optimized[0]
            + ", ns/op " + baseline[1] + " -> " + optimized[1]
            + " (reflection/fixture overhead included; timing is diagnostic)");
    }

    private static FixtureStack stack(int count, int damage) { return new FixtureStack(Items.ARROW, count, damage); }

    private static void configure(boolean enabled) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.capabilityStateNbt = enabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        if (enabled) {
            String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            OptimizerRegistry.targetObserved("capability-state-nbt", "test.State", hash, true);
            OptimizerRegistry.patchInstalled("capability-state-nbt", "test.State", hash);
        }
    }

    private static byte[] jar(String property, String name) throws Exception {
        String path = System.getProperty(property, "");
        Assume.assumeFalse("configure " + property, path.isEmpty());
        return entry(path, name);
    }

    private static byte[] entry(String path, String name) throws Exception {
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) { return read(input); }
    }

    private static byte[] resource(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) { return read(input); }
    }

    private static byte[] read(InputStream input) throws Exception {
        assertNotNull(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            new Analyzer(new BasicVerifier()).analyze(node.name, method);
    }

    public static final class FixtureStack implements CapabilityStateStackAccess {
        public static final FixtureStack EMPTY = new FixtureStack(Items.AIR, 0, 0);
        final Item item;
        final int count;
        final int damage;
        final ItemStack actual;
        NBTTagCompound tag;
        CapabilityDispatcher capabilities;
        NBTTagCompound pendingNbt;
        FixtureStack(Item item, int count, int damage) {
            this.item = item; this.count = count; this.damage = damage;
            this.actual = new ItemStack(item, count, damage);
        }
        public boolean isEmpty() { return count <= 0 || item == Items.AIR; }
        public boolean hasTagCompound() { return tag != null; }
        public void setTagCompound(NBTTagCompound tag) { this.tag = tag; }
        public NBTTagCompound getTagCompound() { return tag; }
        @Override public Item ice$stateItem() { return item; }
        @Override public int ice$stateCount() { return count; }
        @Override public int ice$stateDamage() { return damage; }
        @Override public CapabilityDispatcher ice$stateCapabilities() { return capabilities; }
        @Override public NBTTagCompound ice$statePendingNbt() { return pendingNbt; }
        public NBTTagCompound writeToNBT(NBTTagCompound output) {
            actual.setTagCompound(tag);
            return actual.writeToNBT(output);
        }
    }

    public static class FixtureInventory {
        final FixtureStack[] values;
        public FixtureInventory(int size) { values = new FixtureStack[size]; Arrays.fill(values, FixtureStack.EMPTY); }
        public int getSlots() { return values.length; }
        public FixtureStack getStackInSlot(int slot) { return values[slot]; }
        public NBTTagCompound serializeNBT() {
            NBTTagList items = new NBTTagList();
            for (int slot = 0; slot < values.length; slot++) if (!values[slot].isEmpty()) {
                NBTTagCompound item = new NBTTagCompound();
                item.setInteger("Slot", slot);
                values[slot].writeToNBT(item);
                items.appendTag(item);
            }
            NBTTagCompound result = new NBTTagCompound();
            result.setTag("Items", items);
            result.setInteger("Size", values.length);
            return result;
        }
        public void deserializeNBT(NBTTagCompound tag) { throw new UnsupportedOperationException(); }
    }

    private static final class Unknown implements ICapabilitySerializable<NBTBase> {
        final String name;
        final List<String> calls;
        final int value;
        Unknown(String name, List<String> calls, int value) { this.name = name; this.calls = calls; this.value = value; }
        @Override public boolean hasCapability(Capability<?> capability, EnumFacing facing) { return false; }
        @Override public <T> T getCapability(Capability<T> capability, EnumFacing facing) { return null; }
        @Override public NBTBase serializeNBT() { calls.add(name); NBTTagCompound nbt = new NBTTagCompound(); nbt.setInteger("value", value); return nbt; }
        @Override public void deserializeNBT(NBTBase value) { }
    }

    private static final class Runtime {
        final Class<?> energyClass;
        final Class<?> quiverClass;
        final Class<?> storageClass;
        final Class<?> dispatcherClass;
        final Class<?> bridgeClass;
        int totalSerializations;
        final Map<Object, Integer> serializationCounts = new java.util.IdentityHashMap<Object, Integer>();

        Runtime() throws Exception {
            final Map<String, byte[]> definitions = new HashMap<String, byte[]>();
            ClassNode energy = new ClassNode(Opcodes.ASM5);
            new ClassReader(jar("ice.bountifulbaubles.jar", ENERGY)).accept(energy, ClassReader.EXPAND_FRAMES);
            energy.methods.removeIf(method -> "<init>".equals(method.name));
            MethodNode constructor = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC, "<init>", "(Lnet/minecraftforge/energy/IEnergyStorage;)V", null, null);
            constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitVarInsn(Opcodes.ALOAD, 1);
            constructor.visitFieldInsn(Opcodes.PUTFIELD, ENERGY, "cap", "Lnet/minecraftforge/energy/IEnergyStorage;");
            constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(2, 2); constructor.visitEnd(); energy.methods.add(constructor);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); energy.accept(writer);
            definitions.put(ENERGY, remap(new CapabilityStateProviderAdapter().transform("", writer.toByteArray(), null)));
            definitions.put(QUIVER, remap(new CapabilityStateProviderAdapter().transform("", jar("ice.spartanweaponry.jar", QUIVER), null)));
            definitions.put(ENERGY_STORAGE, remap(jar("ice.bountifulbaubles.jar", ENERGY_STORAGE)));
            definitions.put(BRIDGE_FIXTURE, remap(resource(CapabilityStateBridge.class)));
            definitions.put(CapabilityStateDispatcherAdapter.TARGET,
                remap(new CapabilityStateDispatcherAdapter().transform("", resource(CapabilityDispatcher.class), null)));
            ClassLoader loader = new ClassLoader(CapabilityStateAdapterTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    byte[] bytes = definitions.get(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type);
                    return type;
                }
            };
            energyClass = loader.loadClass(ENERGY.replace('/', '.'));
            quiverClass = loader.loadClass(QUIVER.replace('/', '.'));
            storageClass = loader.loadClass(ENERGY_STORAGE.replace('/', '.'));
            dispatcherClass = loader.loadClass(CapabilityStateDispatcherAdapter.TARGET.replace('/', '.'));
            bridgeClass = loader.loadClass(BRIDGE_FIXTURE.replace('/', '.'));
            activeRuntime = this;
        }

        Object energy(int value) throws Exception {
            Object storage = storageClass.getConstructor(int.class, int.class, int.class, int.class).newInstance(1000, 100, 100, value);
            return energyClass.getConstructor(IEnergyStorage.class).newInstance(storage);
        }
        void energyValue(Object provider, int value) throws Exception {
            storageClass.getMethod("setEnergyStored", int.class).invoke(((CapabilityStateProviderAccess) provider).ice$statePayload(), value);
        }
        Object quiver(FixtureStack stack, int slots) throws Exception { return quiverClass.getConstructor(FixtureStack.class, int.class).newInstance(stack, slots); }
        FixtureInventory inventory(Object provider) { return (FixtureInventory) ((CapabilityStateProviderAccess) provider).ice$statePayload(); }
        Object dispatch(Object... providers) throws Exception {
            Map<ResourceLocation, ICapabilityProvider> map = new LinkedHashMap<ResourceLocation, ICapabilityProvider>();
            for (int i = 0; i < providers.length; i++) map.put(new ResourceLocation("ice", "p" + i), (ICapabilityProvider) providers[i]);
            return dispatcherClass.getConstructor(Map.class).newInstance(map);
        }
        boolean compatible(Object left, Object right) throws Exception {
            return (Boolean) dispatcherClass.getMethod("areCompatible", dispatcherClass).invoke(left, right);
        }
        int compare(Object left, Object right) throws Exception { return (Integer) bridgeClass.getMethod("compare", Object.class, Object.class).invoke(null, left, right); }
        boolean baseline(Object left, Object right) throws Exception {
            int total = totalSerializations;
            Map<Object, Integer> counts = new java.util.IdentityHashMap<Object, Integer>(serializationCounts);
            try {
                return dispatcherClass.getMethod("serializeNBT").invoke(left).equals(dispatcherClass.getMethod("serializeNBT").invoke(right));
            } finally {
                totalSerializations = total;
                serializationCounts.clear();
                serializationCounts.putAll(counts);
            }
        }
        NBTTagCompound serialize(Object provider) throws Exception { return (NBTTagCompound) provider.getClass().getMethod("serializeNBT").invoke(provider); }
        int serializations(Object provider) { Integer count = serializationCounts.get(provider); return count == null ? 0 : count; }

        private byte[] remap(byte[] source) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            new ClassReader(source).accept(node, ClassReader.EXPAND_FRAMES);
            if (ENERGY.equals(node.name) || QUIVER.equals(node.name)) {
                for (MethodNode method : node.methods) if ("serializeNBT".equals(method.name)
                    && "()Lnet/minecraft/nbt/NBTTagCompound;".equals(method.desc)) {
                    org.objectweb.asm.tree.InsnList count = new org.objectweb.asm.tree.InsnList();
                    count.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    count.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(CapabilityStateAdapterTest.class), "recordSerialization", "(Ljava/lang/Object;)V", false));
                    method.instructions.insert(count);
                }
            }
            node.accept(new ClassRemapper(writer, new Remapper() {
                @Override public String map(String name) {
                    if (CapabilityStateProviderAdapter.BRIDGE.equals(name)) return BRIDGE_FIXTURE;
                    if ("net/minecraft/item/ItemStack".equals(name)) return Type.getInternalName(FixtureStack.class);
                    if ("net/minecraftforge/items/ItemStackHandler".equals(name)) return Type.getInternalName(FixtureInventory.class);
                    return name;
                }
                @Override public String mapMethodName(String owner, String name, String descriptor) {
                    if ("func_77942_o".equals(name)) return "hasTagCompound";
                    if ("func_77982_d".equals(name)) return "setTagCompound";
                    if ("func_74768_a".equals(name)) return "setInteger";
                    if ("func_74764_b".equals(name)) return "hasKey";
                    if ("func_74762_e".equals(name)) return "getInteger";
                    return name;
                }
            }));
            return writer.toByteArray();
        }
    }
}

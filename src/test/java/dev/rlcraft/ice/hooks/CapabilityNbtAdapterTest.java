package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import dev.rlcraft.ice.optimizer.compat.nbt.CapabilityNbtBridge;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.INBTSerializable;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Executes the transformed Forge class, including live providers and their side effects. */
public class CapabilityNbtAdapterTest {
    private boolean oldGlobal;
    private boolean oldModule;

    @Before public void enableComparison() {
        oldGlobal = OptimizerConfig.settings.enabled;
        oldModule = OptimizerConfig.settings.vanillaEquipmentNbt;
        configure(true);
        assertTrue(OptimizerBridge.isEnabled("vanilla-equipment-nbt"));
    }

    @After public void restoreConfiguration() {
        OptimizerConfig.settings.enabled = oldGlobal;
        OptimizerConfig.settings.vanillaEquipmentNbt = oldModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test public void actualSrgClassPreservesSerializationAndVerifies() throws Exception {
        String path = System.getProperty("ice.minecraft.srg.jar", "");
        Assume.assumeFalse("configure ice.minecraft.srg.jar", path.isEmpty());
        try (JarFile jar = new JarFile(path);
             InputStream input = jar.getInputStream(jar.getJarEntry(CapabilityNbtAdapter.TARGET + ".class"))) {
            verifyTransformation(read(input));
        }
    }

    @Test public void allProvidersSerializeInOriginalOrderEvenWhenFirstValueDiffers() throws Exception {
        for (boolean enabled : new boolean[] { true, false }) {
            configure(enabled);
            RuntimeDispatcher runtime = new RuntimeDispatcher();
            List<String> calls = new ArrayList<String>();
            Object left = runtime.create(new Provider("L0", compound(1), calls), new Provider("L1", compound(2), calls));
            Object right = runtime.create(new Provider("R0", compound(9), calls), new Provider("R1", compound(2), calls));
            assertFalse(runtime.compatible(left, right));
            assertEquals(Arrays.asList("L0", "L1", "R0", "R1"), calls);
        }
    }

    @Test public void nullAndEmptyBranchesDoNotIntroduceSerialization() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        Object empty = runtime.create();
        Object populated = runtime.create(new Provider("P", compound(1), calls));
        assertTrue(runtime.compatible(empty, null));
        assertTrue(runtime.compatible(empty, runtime.create()));
        assertFalse(runtime.compatible(populated, null));
        assertFalse(runtime.compatible(empty, populated));
        assertTrue(calls.isEmpty());
        assertFalse(runtime.compatible(populated, empty));
        assertEquals(Arrays.asList("P"), calls);
    }

    @Test public void comparisonObservesMutationsMadeByTheLastSerializer() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        final NBTTagCompound leftData = compound(1);
        Object left = runtime.create(new Provider("L0", leftData, calls), new Provider("L1", compound(2), calls));
        Provider mutating = new Provider("R1", compound(2), calls) {
            @Override public NBTBase serializeNBT() {
                leftData.setInteger("value", 9);
                return super.serializeNBT();
            }
        };
        Object right = runtime.create(new Provider("R0", compound(1), calls), mutating);
        assertFalse("all serialization finishes before comparing any provider result", runtime.compatible(left, right));
        assertEquals(Arrays.asList("L0", "L1", "R0", "R1"), calls);
    }

    @Test public void comparingTheSameDispatcherStillSerializesBothSides() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        Provider changing = new Provider("P", compound(0), calls) {
            int sequence;
            @Override public NBTBase serializeNBT() {
                super.serializeNBT();
                return compound(++sequence);
            }
        };
        Object dispatcher = runtime.create(changing);
        assertFalse(runtime.compatible(dispatcher, dispatcher));
        assertEquals(Arrays.asList("P", "P"), calls);
    }

    @Test public void nestedCompoundListAndArrayMutationsAreVisibleOnEveryCall() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        NBTTagCompound leftData = compound(1);
        NBTTagCompound child = compound(4);
        NBTTagList list = new NBTTagList();
        list.appendTag(child);
        leftData.setTag("list", list);
        leftData.setByteArray("bytes", new byte[] { 1, 2, 3 });
        NBTTagCompound rightData = leftData.copy();
        Object left = runtime.create(new Provider("L", leftData, calls));
        Object right = runtime.create(new Provider("R", rightData, calls));
        assertTrue(runtime.compatible(left, right));
        child.setInteger("value", 8);
        assertFalse(runtime.compatible(left, right));
        rightData.getTagList("list", 10).getCompoundTagAt(0).setInteger("value", 8);
        assertTrue(runtime.compatible(left, right));
        leftData.getByteArray("bytes")[0] = 7;
        assertFalse(runtime.compatible(left, right));
        rightData.getByteArray("bytes")[0] = 7;
        list.appendTag(compound(99));
        assertFalse(runtime.compatible(left, right));
        assertEquals(Arrays.asList("L", "R", "L", "R", "L", "R", "L", "R", "L", "R"), calls);
    }

    @Test public void providerExceptionsPropagateWithoutSerializingTheOtherSide() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        final IllegalStateException failure = new IllegalStateException("provider failed");
        Provider failing = new Provider("L1", compound(2), calls) {
            @Override public NBTBase serializeNBT() {
                super.serializeNBT();
                throw failure;
            }
        };
        Object left = runtime.create(new Provider("L0", compound(1), calls), failing);
        Object right = runtime.create(new Provider("R0", compound(1), calls));
        try {
            runtime.compatible(left, right);
            fail("provider failure must propagate");
        } catch (InvocationTargetException expected) {
            assertSame(failure, expected.getCause());
        }
        assertEquals(Arrays.asList("L0", "L1"), calls);
    }

    @Test public void liveWriterReplacementAndRemovalRemainVisible() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        Provider initial = new Provider("L0", compound(1), calls);
        Object left = runtime.create(initial);
        Object right = runtime.create(new Provider("R0", compound(1), calls));
        assertTrue(runtime.compatible(left, right));
        calls.clear();
        runtime.writers(left, new String[] { "ice:provider0", "ice:provider1" },
            initial, new Provider("injected", compound(2), calls));
        assertFalse(runtime.compatible(left, right));
        assertEquals(Arrays.asList("L0", "injected", "R0"), calls);
        runtime.writers(left, new String[0]);
        calls.clear();
        assertTrue(runtime.compatible(left, null));
        assertFalse(runtime.compatible(left, right));
        assertTrue(calls.isEmpty());
    }

    @Test public void customCompoundEqualityStillUsesTheSubclassImplementation() throws Exception {
        RuntimeDispatcher runtime = new RuntimeDispatcher();
        List<String> calls = new ArrayList<String>();
        final NBTTagCompound rightData = compound(2);
        final int[] comparisons = { 0 };
        NBTTagCompound custom = new NBTTagCompound() {
            @Override public boolean equals(Object other) {
                comparisons[0]++;
                return other == rightData;
            }
        };
        assertTrue(CapabilityNbtBridge.equal(custom, rightData));
        assertEquals(1, comparisons[0]);
        assertTrue(runtime.compatible(runtime.create(new Provider("L", custom, calls)),
            runtime.create(new Provider("R", rightData, calls))));
        assertEquals(2, comparisons[0]);
        assertEquals(Arrays.asList("L", "R"), calls);
    }

    @Test public void changedSerializationShapesAndAlreadyAdaptedClassesAreRejected() throws Exception {
        byte[] original = mappedBytes();
        byte[] adapted = verifyTransformation(original);
        assertRejected(adapted);
        ClassNode node = node(original);
        for (MethodNode method : node.methods) if ("areCompatible".equals(method.name)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && "serializeNBT".equals(((MethodInsnNode) instruction).name)) {
                    ((MethodInsnNode) instruction).name = "changedSerialization";
                    break;
                }
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        assertRejected(writer.toByteArray());
        node = node(original);
        node.name = "example/ChangedDispatcher";
        writer = new ClassWriter(0);
        node.accept(writer);
        assertRejected(writer.toByteArray());
    }

    private static void configure(boolean enabled) {
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.vanillaEquipmentNbt = enabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        if (enabled) {
            String hash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
            OptimizerRegistry.targetObserved("vanilla-equipment-nbt", "test.Capability", hash, true);
            OptimizerRegistry.patchInstalled("vanilla-equipment-nbt", "test.Capability", hash);
        }
    }

    private static byte[] verifyTransformation(byte[] original) throws Exception {
        byte[] changed = new CapabilityNbtAdapter().transform(CapabilityNbtAdapter.TARGET.replace('/', '.'), original, null);
        ClassNode node = node(changed);
        int serializations = 0, comparisons = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer(new BasicVerifier()).analyze(node.name, method);
            }
            if (!"areCompatible".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (CapabilityNbtAdapter.TARGET.equals(call.owner) && "serializeNBT".equals(call.name)) serializations++;
                if (CapabilityNbtAdapter.BRIDGE.equals(call.owner) && "equal".equals(call.name)) comparisons++;
            }
        }
        assertEquals(2, serializations);
        assertEquals(1, comparisons);
        return changed;
    }

    private static void assertRejected(byte[] original) {
        try {
            new CapabilityNbtAdapter().transform(CapabilityNbtAdapter.TARGET.replace('/', '.'), original, null);
            fail("changed comparison shape must be rejected");
        } catch (IllegalStateException expected) { }
    }

    private static NBTTagCompound compound(int value) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("value", value);
        return tag;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static byte[] mappedBytes() throws Exception {
        try (InputStream input = CapabilityDispatcher.class.getResourceAsStream("/" + CapabilityNbtAdapter.TARGET + ".class")) {
            return read(input);
        }
    }

    private static byte[] read(InputStream input) throws Exception {
        assertNotNull(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static class Provider implements ICapabilitySerializable<NBTBase> {
        final String name;
        final NBTBase value;
        final List<String> calls;
        Provider(String name, NBTBase value, List<String> calls) { this.name = name; this.value = value; this.calls = calls; }
        @Override public boolean hasCapability(Capability<?> capability, EnumFacing facing) { return false; }
        @Override public <T> T getCapability(Capability<T> capability, EnumFacing facing) { return null; }
        @Override public NBTBase serializeNBT() { calls.add(name); return value; }
        @Override public void deserializeNBT(NBTBase value) { }
    }

    private static final class RuntimeDispatcher {
        final Class<?> type;
        final Method compatible;
        RuntimeDispatcher() throws Exception {
            final byte[] changed = verifyTransformation(mappedBytes());
            type = new ClassLoader(CapabilityNbtAdapterTest.class.getClassLoader()) {
                Class<?> define() {
                    return defineClass(CapabilityNbtAdapter.TARGET.replace('/', '.'), changed, 0, changed.length);
                }
            }.define();
            compatible = type.getMethod("areCompatible", type);
        }
        Object create(Provider... providers) throws Exception {
            Map<ResourceLocation, ICapabilityProvider> values = new LinkedHashMap<ResourceLocation, ICapabilityProvider>();
            for (int i = 0; i < providers.length; i++) values.put(new ResourceLocation("ice", "provider" + i), providers[i]);
            return type.getConstructor(Map.class).newInstance(values);
        }
        boolean compatible(Object left, Object right) throws Exception {
            return (Boolean) compatible.invoke(left, right);
        }
        void writers(Object dispatcher, String[] names, Provider... providers) throws Exception {
            Field writerField = type.getDeclaredField("writers");
            Field nameField = type.getDeclaredField("names");
            writerField.setAccessible(true);
            nameField.setAccessible(true);
            writerField.set(dispatcher, Arrays.copyOf(providers, providers.length, INBTSerializable[].class));
            nameField.set(dispatcher, names);
        }
    }
}

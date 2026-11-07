package dev.rlcraft.ice.hooks;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mirror.normalasm.api.datastructures.NormalTagMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Offline only: production NBT/capability hooks and actual NormalASM maps at threshold five. */
public final class CapabilityNbtProbe {
    private static boolean normalMaps;
    private static volatile boolean compoundTransformed;
    private static volatile boolean capabilityTransformed;

    public static void premain(String arguments, Instrumentation instrumentation) {
        normalMaps = "normal".equals(arguments);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                try {
                    if (NbtCompoundAdapter.TARGET.equals(name)) {
                        byte[] result = new NbtCompoundAdapter().transform(name.replace('/', '.'), bytes, null);
                        if (normalMaps) result = normalConstructor(result);
                        compoundTransformed = true;
                        return result;
                    }
                    if (CapabilityNbtAdapter.TARGET.equals(name)) {
                        byte[] result = new CapabilityNbtAdapter().transform(name.replace('/', '.'), bytes, null);
                        capabilityTransformed = true;
                        return result;
                    }
                    return null;
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    throw new AssertionError("probe transformation failed", failure);
                }
            }
        });
    }

    private static byte[] normalConstructor(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) if ("<init>".equals(method.name) && "()V".equals(method.desc)) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC && "com/google/common/collect/Maps".equals(call.owner)
                    && "newHashMap".equals(call.name) && "()Ljava/util/HashMap;".equals(call.desc)) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/rlcraft/ice/hooks/CapabilityNbtProbe", "backingMap", "()Ljava/util/Map;", false));
                    changed++;
                }
            }
        }
        if (changed != 1) throw new AssertionError("unexpected compound constructor: " + changed);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static Map backingMap() {
        return new NormalTagMap<Object, Object>(5, true, true);
    }

    public static void main(String[] arguments) throws Exception { Measurement.run(); }

    // Keep Minecraft signatures out of the agent entry class so its premain can
    // register the transformer before Minecraft's NBT classes are resolved.
    private static final class Measurement {
        private static volatile int sink;
        private static long serializations;
        private static final com.sun.management.ThreadMXBean BEAN =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        static void run() throws Exception {
            // Do not let the rejected deep-copy experiment contaminate this probe
            // when run against an older build/classes directory.
            try {
                dev.rlcraft.ice.optimizer.OptimizerConfig.Settings.class.getField("nbtCompoundCopy")
                    .setBoolean(dev.rlcraft.ice.optimizer.OptimizerConfig.settings, false);
            } catch (NoSuchFieldException ignored) { }
            dev.rlcraft.ice.optimizer.OptimizerRegistry.breaker(
                dev.rlcraft.ice.optimizer.OptimizationModule.VANILLA_EQUIPMENT_NBT).patchInstalled("probe", "mapped");
            configure(true);
            net.minecraft.nbt.NBTTagCompound check = fixture(1, 4);
            net.minecraftforge.common.capabilities.CapabilityDispatcher dispatcher = dispatcher(1, 1, 4, false);
            if (!compoundTransformed || !capabilityTransformed || !dispatcher.areCompatible(dispatcher)) {
                throw new AssertionError("production hooks missing");
            }
            if (normalMaps && !(((dev.rlcraft.ice.optimizer.compat.nbt.NbtCompoundAccess) check).ice$tagMap() instanceof NormalTagMap)) {
                throw new AssertionError("NormalASM map constructor missing");
            }
            verifyUnknownMapFallback();
            BEAN.setThreadAllocatedMemoryEnabled(true);
            System.out.println("map=" + (normalMaps ? "NormalTagMap threshold=5 canonicalization=true" : "vanilla HashMap")
                + " compound_and_capability_hooks=verified unknown_map_fallback=verified");
            for (int fields : new int[] { 4, 5, 6 }) for (int width : new int[] { 1, 32 }) {
                final net.minecraft.nbt.NBTTagCompound left = fixture(width, fields), right = fixture(width, fields);
                benchmark("compare width=" + width + " child_fields=" + fields, new Operation() {
                    @Override public boolean evaluate() {
                        return dev.rlcraft.ice.optimizer.compat.nbt.CapabilityNbtBridge.equal(left, right);
                    }
                }, Math.max(1500, 50000 / width), 0);
                for (int providers : new int[] { 1, 4 }) for (boolean copy : new boolean[] { false, true }) {
                    final net.minecraftforge.common.capabilities.CapabilityDispatcher a = dispatcher(providers, width, fields, copy);
                    final net.minecraftforge.common.capabilities.CapabilityDispatcher b = dispatcher(providers, width, fields, copy);
                    benchmark("capability providers=" + providers + " width=" + width + " child_fields=" + fields
                        + " provider=" + (copy ? "copy_snapshot" : "live_tag"), new Operation() {
                        @Override public boolean evaluate() { return a.areCompatible(b); }
                    }, Math.max(500, 12000 / (width * providers)), providers * 2);
                }
            }
        }

        private static void verifyUnknownMapFallback() throws Exception {
            net.minecraft.nbt.NBTTagCompound left = new net.minecraft.nbt.NBTTagCompound();
            net.minecraft.nbt.NBTTagCompound right = new net.minecraft.nbt.NBTTagCompound();
            for (java.lang.reflect.Field field : net.minecraft.nbt.NBTTagCompound.class.getDeclaredFields()) {
                if (field.getType() != Map.class) continue;
                field.setAccessible(true);
                // Original compound.equals delegates to this entry-set equality.
                // A broad iterator replacement would instead return true for the
                // two empty maps and silently discard this custom map behavior.
                field.set(left, new OpaqueEqualityMap());
                if (left.equals(right) || dev.rlcraft.ice.optimizer.compat.nbt.CapabilityNbtBridge.equal(left, right)) {
                    throw new AssertionError("unknown map did not retain original equals");
                }
                return;
            }
            throw new AssertionError("compound map field missing");
        }

        private static void configure(boolean enabled) {
            dev.rlcraft.ice.optimizer.OptimizerConfig.settings.enabled = true;
            dev.rlcraft.ice.optimizer.OptimizerConfig.settings.vanillaEquipmentNbt = enabled;
            dev.rlcraft.ice.optimizer.OptimizerRegistry.configure(dev.rlcraft.ice.optimizer.ClientOptimizerConfig.capture());
            if (dev.rlcraft.ice.optimizer.bridge.OptimizerBridge.isEnabled("vanilla-equipment-nbt") != enabled) {
                throw new AssertionError("comparison switch did not apply");
            }
        }

        private static net.minecraft.nbt.NBTTagCompound fixture(int width, int fields) {
            net.minecraft.nbt.NBTTagCompound root = new net.minecraft.nbt.NBTTagCompound();
            for (int i = 0; i < width; i++) {
                net.minecraft.nbt.NBTTagCompound child = new net.minecraft.nbt.NBTTagCompound();
                child.setString("name", "attribute" + i);
                child.setDouble("amount", i + 0.25);
                child.setInteger("operation", i % 3);
                child.setLong("UUIDMost", i);
                if (fields >= 5) child.setLong("UUIDLeast", i * 31L);
                if (fields >= 6) child.setByteArray("bytes", new byte[] { 1, 2, 3 });
                net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
                list.appendTag(child);
                root.setTag("key" + i, list);
            }
            return root;
        }

        private static net.minecraftforge.common.capabilities.CapabilityDispatcher dispatcher(int providers, int width, int fields, boolean copy) {
            Map<net.minecraft.util.ResourceLocation, net.minecraftforge.common.capabilities.ICapabilityProvider> values =
                new LinkedHashMap<net.minecraft.util.ResourceLocation, net.minecraftforge.common.capabilities.ICapabilityProvider>();
            for (int i = 0; i < providers; i++) values.put(new net.minecraft.util.ResourceLocation("ice", "provider" + i),
                new Provider(fixture(width, fields), copy));
            return new net.minecraftforge.common.capabilities.CapabilityDispatcher(values);
        }

        private static void benchmark(String label, Operation operation, int repetitions, int writers) {
            for (int round = 0; round < 6; round++) {
                configure((round & 1) != 0);
                execute(operation, repetitions, writers);
            }
            long[][] time = new long[2][9], allocation = new long[2][9];
            for (int round = 0; round < 9; round++) for (int step = 0; step < 2; step++) {
                int arm = (round + step) & 1;
                configure(arm == 1);
                long beforeBytes = BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
                long beforeTime = System.nanoTime();
                execute(operation, repetitions, writers);
                time[arm][round] = (System.nanoTime() - beforeTime) / repetitions;
                allocation[arm][round] = (BEAN.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeBytes) / repetitions;
            }
            for (int arm = 0; arm < 2; arm++) { Arrays.sort(time[arm]); Arrays.sort(allocation[arm]); }
            System.out.println(label + " baseline_ns=" + time[0][4] + " candidate_ns=" + time[1][4]
                + " baseline_bytes=" + allocation[0][4] + " candidate_bytes=" + allocation[1][4]);
        }

        private static void execute(Operation operation, int repetitions, int writers) {
            long before = serializations;
            int equal = 0;
            for (int i = 0; i < repetitions; i++) if (operation.evaluate()) equal++;
            if (equal != repetitions || serializations - before != (long) writers * repetitions) {
                throw new AssertionError("comparison result or serialization count changed");
            }
            sink = equal;
        }

        private interface Operation { boolean evaluate(); }

        private static final class OpaqueEqualityMap extends HashMap<String, net.minecraft.nbt.NBTBase> {
            @Override public Set<Map.Entry<String, net.minecraft.nbt.NBTBase>> entrySet() {
                final Set<Map.Entry<String, net.minecraft.nbt.NBTBase>> values = super.entrySet();
                return new AbstractSet<Map.Entry<String, net.minecraft.nbt.NBTBase>>() {
                    @Override public Iterator<Map.Entry<String, net.minecraft.nbt.NBTBase>> iterator() { return values.iterator(); }
                    @Override public int size() { return values.size(); }
                    @Override public boolean equals(Object other) { return false; }
                };
            }
        }

        private static final class Provider implements net.minecraftforge.common.capabilities.ICapabilitySerializable<net.minecraft.nbt.NBTTagCompound> {
            private final net.minecraft.nbt.NBTTagCompound data;
            private final boolean copy;
            Provider(net.minecraft.nbt.NBTTagCompound data, boolean copy) { this.data = data; this.copy = copy; }
            @Override public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, net.minecraft.util.EnumFacing facing) { return false; }
            @Override public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.util.EnumFacing facing) { return null; }
            @Override public net.minecraft.nbt.NBTTagCompound serializeNBT() { serializations++; return copy ? data.copy() : data; }
            @Override public void deserializeNBT(net.minecraft.nbt.NBTTagCompound nbt) { }
        }
    }
}

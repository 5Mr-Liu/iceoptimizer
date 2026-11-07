package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.compat.trinkets.TrinketsDeltaSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs the original 0.32.5 wire codec and BAUBLES callbacks with observable accessory state. */
public final class TrinketsSyncPacketContractTest {
    private static final String PACKET = "xzeroair/trinkets/network/SyncItemDataPacket";
    private static final String PROPERTIES = "xzeroair/trinkets/capabilities/Trinket/TrinketProperties";
    private static final List<String> EVENTS = new ArrayList<String>();
    private static final Map<ItemStack, FixtureProperties> STATES = new IdentityHashMap<ItemStack, FixtureProperties>();
    private static FixtureAccessory accessory;
    private static int shareWrites;

    @Before public void setup() {
        Bootstrap.register();
        if (accessory == null) {
            FixtureAccessory candidate = new FixtureAccessory();
            candidate.setRegistryName(new ResourceLocation("ice_test", "trinkets_sync_accessory"));
            ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
            boolean locked = registry.isLocked();
            if (locked) registry.unfreeze();
            try { registry.register(candidate); }
            finally { if (locked) registry.freeze(); }
            assertSame(candidate, new ItemStack(new ItemStack(candidate).writeToNBT(new NBTTagCompound())).getItem());
            accessory = candidate;
        }
        EVENTS.clear(); STATES.clear(); shareWrites = 0;
    }

    @After public void clear() { EVENTS.clear(); STATES.clear(); }

    /** Optional local transport probe using bytes produced by the actual 0.32.5 codec. */
    public static void main(String[] arguments) throws Exception {
        TrinketsSyncPacketContractTest fixture = new TrinketsSyncPacketContractTest();
        fixture.setup();
        try {
            Runtime runtime = runtime();
            byte[][] samples = new byte[64][];
            byte[][] broadChanges = new byte[samples.length][];
            java.util.Random random = new java.util.Random(32_005);
            for (int index = 0; index < samples.length; index++) {
                NBTTagCompound independent = tag(index); ItemStack item = item(index + 1000);
                for (int field = 0; field < 30; field++) {
                    String value = "unchanged accessory state field " + field;
                    independent.setString("field" + field, value); item.getTagCompound().setString("field" + field, value);
                }
                byte[] original = runtime.encode(runtime.packet(independent, item, true, true));
                samples[index] = new byte[original.length + 1]; samples[index][0] = 2;
                System.arraycopy(original, 0, samples[index], 1, original.length);
                for (int field = 0; field < 30; field++) {
                    char[] value = new char[32];
                    for (int letter = 0; letter < value.length; letter++) value[letter] = (char) ('a' + random.nextInt(26));
                    independent.setString("field" + field, new String(value));
                    item.getTagCompound().setString("field" + field, new String(value));
                }
                original = runtime.encode(runtime.packet(independent, item, true, true));
                broadChanges[index] = new byte[original.length + 1]; broadChanges[index][0] = 2;
                System.arraycopy(original, 0, broadChanges[index], 1, original.length);
            }
            com.sun.management.ThreadMXBean allocations =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            allocations.setThreadAllocatedMemoryEnabled(true);
            System.out.println("Actual 0.32.5 SyncItemDataPacket codec; synthetic accessory state with 30 fields in each distinct snapshot:");
            reportProbe("Identical snapshots", new byte[][] { samples[0] }, allocations);
            reportProbe("Two integer fields changed", samples, allocations);
            reportProbe("All 30 string fields changed", broadChanges, allocations);
            System.out.println("Original NBT encoding/decoding and callbacks remain; payload numbers exclude TCP/channel-name overhead and Minecraft compression. Not gameplay FPS.");
        } finally { fixture.clear(); }
    }

    private static void reportProbe(String scenario, byte[][] samples, com.sun.management.ThreadMXBean allocations) {
            protocolProbe(samples, allocations, 2000);
            long[][] rounds = new long[7][];
            for (int round = 0; round < rounds.length; round++) rounds[round] = protocolProbe(samples, allocations, 5000);
            long[] last = rounds[rounds.length - 1];
            System.out.println(scenario + ":");
            System.out.println("FML payload original=" + last[0] / 5000D + " B/message, delta=" + last[1] / 5000D
                + " B/message including 45-byte frame header and first FULL; FULL frames=" + last[5]);
            System.out.println("Additional transport median of 7 rounds: encode+frame-write=" + median(rounds, 2) / 5000D
                + " ns/message, frame-read+reconstruct=" + median(rounds, 3) / 5000D + " ns/message, allocation="
                + median(rounds, 4) / 5000D + " B/message.");
    }

    private static long[] protocolProbe(byte[][] samples, com.sun.management.ThreadMXBean allocations, int count) {
        TrinketsDeltaSession.Sender sender = new TrinketsDeltaSession.Sender(1, 64, 2 * 1024 * 1024);
        TrinketsDeltaSession.Receiver receiver = new TrinketsDeltaSession.Receiver(64, 2 * 1024 * 1024);
        TrinketsDeltaSession.Key key = new TrinketsDeltaSession.Key(42, 2, 3);
        long originalBytes = 0, frameBytes = 0, encodeNanos = 0, decodeNanos = 0, full = 0;
        long thread = Thread.currentThread().getId(), allocated = allocations.getThreadAllocatedBytes(thread);
        for (int index = 0; index < count; index++) {
            byte[] payload = samples[index % samples.length];
            long start = System.nanoTime();
            TrinketsDeltaSession.Frame frame = sender.encode(key, payload);
            int size = TrinketsDeltaSession.Frame.HEADER_BYTES + frame.bodyLength();
            ByteBuf wire = Unpooled.buffer(size, size);
            try {
                frame.write(wire); long encoded = System.nanoTime();
                byte[] restored = receiver.decode(TrinketsDeltaSession.Frame.read(wire));
                long decoded = System.nanoTime();
                if (!Arrays.equals(payload, restored)) throw new AssertionError("transport changed original bytes");
                encodeNanos += encoded - start; decodeNanos += decoded - encoded;
                originalBytes += payload.length; frameBytes += size; if (frame.full) full++;
            } finally { wire.release(); }
        }
        return new long[] { originalBytes, frameBytes, encodeNanos, decodeNanos,
            allocations.getThreadAllocatedBytes(thread) - allocated, full };
    }

    private static long median(long[][] samples, int column) {
        long[] values = new long[samples.length];
        for (int index = 0; index < samples.length; index++) values[index] = samples[index][column];
        Arrays.sort(values); return values[values.length / 2];
    }

    @Test public void actualAccessoryAndThreadSafePacketKeepTheirLoadAndSchedulingBoundaries() throws Exception {
        ClassNode accessory = original("xzeroair/trinkets/items/base/AccessoryBase");
        List<String> read = calls(method(accessory, "readNBTShareTag"));
        assertEquals("net/minecraft/item/Item.readNBTShareTag", read.get(0));
        assertEquals("xzeroair/trinkets/capabilities/Capabilities.getTrinketProperties", read.get(1));
        List<String> load = calls(method(accessory, "lambda$readNBTShareTag$9"));
        assertEquals(Arrays.asList(PROPERTIES + ".loadFromNBT"), load);
        assertTrue(calls(method(accessory, "lambda$getNBTShareTag$8")).contains(PROPERTIES + ".saveToNBT"));
        List<String> schedule = calls(method(original("xzeroair/trinkets/network/ThreadSafePacket"), "handleClient"));
        assertTrue(schedule.contains("net/minecraftforge/fml/common/FMLCommonHandler.getWorldThread"));
        assertEquals("net/minecraft/util/IThreadListener.func_152344_a", schedule.get(schedule.size() - 1));
        List<String> run = calls(method(original("xzeroair/trinkets/network/ThreadSafePacket$1"), "run"));
        assertTrue(run.contains("xzeroair/trinkets/network/ThreadSafePacket.handleClientSafe"));
    }

    @Test public void originalPacketPreservesBothSnapshotsAndShareEquipIndependentLoadOrder() throws Exception {
        Runtime runtime = runtime();
        ItemStack item = item(202);
        Object outgoing = runtime.packet(tag(101), item, true, true);
        // The actual constructor retains references: the independent tag and
        // item share state are sampled at distinct points, and must not alias.
        ((FixturePacketBase) outgoing).tag = tag(999);
        byte[] bytes = runtime.encode(outgoing);
        assertEquals(1, shareWrites);
        assertEquals(101, ((NBTTagCompound) runtime.field(outgoing, "tag")).getInteger("value"));
        EVENTS.clear();
        Object incoming = runtime.decode(bytes);
        assertEquals(Arrays.asList("share-load:202"), EVENTS);
        assertEquals(101, ((NBTTagCompound) runtime.field(incoming, "tag")).getInteger("value"));
        ItemStack received = (ItemStack) runtime.field(incoming, "item");
        assertEquals(202, received.getTagCompound().getInteger("value"));
        TestHandler handler = new TestHandler(ItemStack.EMPTY);
        runtime.apply(incoming, Side.CLIENT, handler);
        assertEquals(Arrays.asList("share-load:202", "slot-set:3", "slot-get:3", "equip:202", "independent-load:101"), EVENTS);
        assertSame(received, handler.stack);
        assertEquals(101, STATES.get(received).value);
        assertEquals(202, received.getTagCompound().getInteger("value"));
    }

    @Test public void originalWireSeesAnItemMutationAfterTheIndependentSnapshotWasCaptured() throws Exception {
        Runtime runtime = runtime();
        NBTTagCompound independent = tag(10);
        ItemStack item = item(20);
        Object outgoing = runtime.packet(independent, item, true, true);
        item.getTagCompound().setInteger("value", 30);
        Object incoming = runtime.decode(runtime.encode(outgoing));
        assertEquals(10, ((NBTTagCompound) runtime.field(incoming, "tag")).getInteger("value"));
        assertEquals(30, ((ItemStack) runtime.field(incoming, "item")).getTagCompound().getInteger("value"));
        assertEquals(1, shareWrites);
    }

    @Test public void originalUnequipAndNoStackBranchesDoNotInventCallbacks() throws Exception {
        Runtime runtime = runtime();
        ItemStack existing = item(202);
        STATES.put(existing, new FixtureProperties(202));
        Object incoming = runtime.decode(runtime.encode(runtime.packet(tag(101), ItemStack.EMPTY, true, false)));
        EVENTS.clear();
        TestHandler handler = new TestHandler(existing);
        runtime.apply(incoming, Side.CLIENT, handler);
        assertEquals(Arrays.asList("slot-set:3"), EVENTS);
        assertSame(ItemStack.EMPTY, handler.stack);

        incoming = runtime.decode(runtime.encode(runtime.packet(tag(101), null, false, true)));
        EVENTS.clear();
        handler = new TestHandler(existing);
        runtime.apply(incoming, Side.CLIENT, handler);
        assertEquals(Arrays.asList("slot-get:3", "equip:202", "independent-load:101"), EVENTS);
        assertSame(existing, handler.stack);
        assertEquals(0, shareWrites);

        incoming = runtime.decode(runtime.encode(runtime.packet(tag(99), null, false, false)));
        EVENTS.clear();
        runtime.apply(incoming, Side.CLIENT, handler);
        assertTrue(EVENTS.isEmpty());
    }

    @Test public void serverSideCallbackSchedulesResyncWithoutApplyingClientState() throws Exception {
        Runtime runtime = runtime();
        ItemStack existing = item(202);
        STATES.put(existing, new FixtureProperties(202));
        Object packet = runtime.packet(tag(101), null, false, true);
        runtime.apply(packet, Side.SERVER, new TestHandler(existing));
        assertEquals(Arrays.asList("slot-get:3", "schedule-resync"), EVENTS);
        assertEquals(202, STATES.get(existing).value);
    }

    @Test public void fullDeltaAndRepeatedPayloadRestoreTheOriginalPacketAndEveryClientCallback() throws Exception {
        Runtime runtime = runtime();
        TrinketsDeltaSession.Key key = new TrinketsDeltaSession.Key(42, 2, 3);
        TrinketsDeltaSession.Sender sender = new TrinketsDeltaSession.Sender(31, 4, 65536);
        TrinketsDeltaSession.Receiver receiver = new TrinketsDeltaSession.Receiver(4, 65536);
        ItemStack item = item(202); NBTTagCompound independent = tag(101);
        for (int index = 0; index < 30; index++) {
            String text = "unchanged accessory data " + index;
            independent.setString("field" + index, text); item.getTagCompound().setString("field" + index, text);
        }
        byte[] first = runtime.encode(runtime.packet(independent, item, true, true));
        assertArrayEquals(first, receiver.decode(sender.encode(key, first)));
        independent.setInteger("value", 102); item.getTagCompound().setInteger("value", 203);
        byte[] next = runtime.encode(runtime.packet(independent, item, true, true));
        EVENTS.clear();
        runtime.apply(runtime.decode(next), Side.CLIENT, new TestHandler(ItemStack.EMPTY));
        List<String> expected = new ArrayList<String>(EVENTS);
        assertEquals(Arrays.asList("share-load:203", "slot-set:3", "slot-get:3", "equip:203", "independent-load:102"), expected);
        for (int repeat = 0; repeat < 2; repeat++) {
            TrinketsDeltaSession.Frame frame = sender.encode(key, next);
            assertFalse(frame.full);
            ByteBuf envelope = Unpooled.buffer();
            byte[] restored;
            try { frame.write(envelope); restored = receiver.decode(TrinketsDeltaSession.Frame.read(envelope)); }
            finally { envelope.release(); }
            assertArrayEquals(next, restored);
            EVENTS.clear();
            runtime.apply(runtime.decode(restored), Side.CLIENT, new TestHandler(ItemStack.EMPTY));
            assertEquals("each frame, including a repeated one, invokes the original callbacks", expected, EVENTS);
        }
        assertEquals("transport does not call the accessory serializer", 2, shareWrites);
    }

    private static NBTTagCompound tag(int value) {
        NBTTagCompound tag = new NBTTagCompound(); tag.setInteger("value", value); return tag;
    }

    private static ItemStack item(int value) {
        ItemStack item = new ItemStack(accessory); item.setTagCompound(tag(value)); return item;
    }

    private static Runtime runtime() throws Exception {
        ClassNode node = original(PACKET);
        for (Iterator<MethodNode> iterator = node.methods.iterator(); iterator.hasNext();) {
            MethodNode method = iterator.next();
            boolean keep = ("<init>".equals(method.name) && "()V".equals(method.desc))
                || "toBytes".equals(method.name) || "fromBytes".equals(method.name)
                || "lambda$handleBaubles$3".equals(method.name) || "lambda$null$2".equals(method.name);
            if (!keep) iterator.remove();
        }
        final Map<String, String> types = new HashMap<String, String>();
        types.put("xzeroair/trinkets/network/ThreadSafePacket", Type.getInternalName(FixturePacketBase.class));
        types.put(PROPERTIES, Type.getInternalName(FixtureProperties.class));
        types.put("xzeroair/trinkets/capabilities/Capabilities", Type.getInternalName(FixtureCapabilities.class));
        types.put("baubles/api/cap/IBaublesItemHandler", Type.getInternalName(FixtureHandler.class));
        ClassWriter writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) { return types.containsKey(name) ? types.get(name) : name; }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return "func_190926_b".equals(name) ? "isEmpty" : name;
            }
            @Override public String mapFieldName(String owner, String name, String descriptor) {
                return "field_190927_a".equals(name) ? "EMPTY" : name;
            }
        }));
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(TrinketsSyncPacketContractTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return new Runtime(type);
    }

    private static ClassNode original(String name) throws Exception {
        String path = System.getProperty("ice.trinkets.jar", "");
        Assume.assumeFalse("configure ice.trinkets.jar", path.isEmpty());
        ClassNode node = new ClassNode(Opcodes.ASM5);
        try (JarFile jar = new JarFile(path); InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) if (name.equals(method.name)) return method;
        throw new AssertionError(owner.name + '.' + name);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<String>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; calls.add(call.owner + '.' + call.name);
        }
        return calls;
    }

    private static final class Runtime {
        final Class<?> type;
        final Method apply;
        Runtime(Class<?> type) throws Exception {
            this.type = type;
            apply = type.getDeclaredMethod("lambda$handleBaubles$3", Side.class, EntityLivingBase.class, FixtureHandler.class);
            apply.setAccessible(true);
        }
        Object packet(NBTTagCompound tag, ItemStack item, boolean syncStacks, boolean equipped) throws Exception {
            Object packet = type.newInstance();
            field(packet, "slot", 3); field(packet, "handler", 2); field(packet, "entityID", 42);
            field(packet, "tag", tag); field(packet, "item", item);
            field(packet, "syncStacks", syncStacks); field(packet, "equipped", equipped); return packet;
        }
        Object field(Object packet, String name) throws Exception {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(packet);
        }
        void field(Object packet, String name, Object value) throws Exception {
            Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(packet, value);
        }
        byte[] encode(Object packet) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                ((IMessage) packet).toBytes(buffer);
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes;
            } finally { buffer.release(); }
        }
        Object decode(byte[] bytes) throws Exception {
            Object packet = type.newInstance(); ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            try { ((IMessage) packet).fromBytes(buffer); assertFalse(buffer.isReadable()); return packet; }
            finally { buffer.release(); }
        }
        void apply(Object packet, Side side, FixtureHandler handler) throws Exception {
            apply.invoke(packet, side, null, handler);
        }
    }

    public static abstract class FixturePacketBase implements IMessage { public NBTTagCompound tag; }

    public interface FixtureHandler {
        void setStackInSlot(int slot, ItemStack stack);
        ItemStack getStackInSlot(int slot);
    }

    private static final class TestHandler implements FixtureHandler {
        ItemStack stack;
        TestHandler(ItemStack stack) { this.stack = stack; }
        @Override public void setStackInSlot(int slot, ItemStack stack) { EVENTS.add("slot-set:" + slot); this.stack = stack; }
        @Override public ItemStack getStackInSlot(int slot) { EVENTS.add("slot-get:" + slot); return stack; }
    }

    public static final class FixtureAccessory extends Item {
        @Override public boolean getShareTag() { return true; }
        @Override public NBTTagCompound getNBTShareTag(ItemStack stack) {
            shareWrites++; return stack.getTagCompound().copy();
        }
        @Override public void readNBTShareTag(ItemStack stack, NBTTagCompound tag) {
            super.readNBTShareTag(stack, tag);
            int value = tag.getInteger("value"); EVENTS.add("share-load:" + value);
            STATES.put(stack, new FixtureProperties(value));
        }
    }

    public static final class FixtureCapabilities {
        public static FixtureProperties getTrinketProperties(ItemStack stack, Consumer<FixtureProperties> consumer) {
            FixtureProperties properties = STATES.get(stack);
            if (properties != null) consumer.accept(properties);
            return properties;
        }
    }

    public static final class FixtureProperties {
        int value;
        FixtureProperties() { }
        FixtureProperties(int value) { this.value = value; }
        public void itemEquipped(ItemStack stack, EntityLivingBase entity) { EVENTS.add("equip:" + value); }
        public void loadFromNBT(NBTTagCompound tag) { value = tag.getInteger("value"); EVENTS.add("independent-load:" + value); }
        public void scheduleResync() { EVENTS.add("schedule-resync"); }
    }
}

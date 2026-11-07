package dev.rlcraft.ice.optimizer.compat.trinkets;

import static org.junit.Assert.*;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.eventhandler.ASMEventHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import net.minecraftforge.fml.common.eventhandler.ListenerList;
import net.minecraftforge.fml.common.network.FMLEmbeddedChannel;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import sun.misc.Unsafe;

/** Real Forge channel routing; the dispatcher records outgoing packets in place of a socket. */
public final class TrinketsSyncTransportTest {
    private static final List<byte[]> DELIVERED = new ArrayList<byte[]>();
    private static int writes;

    @Test public void registrationRoutesFullDeltaLegacyAndReRegistrationThroughTheOriginalHandler() throws Exception {
        Bootstrap.register();
        assertSame("Forge 1.12 registration events use the Forge bus", MinecraftForge.EVENT_BUS, FMLCommonHandler.instance().bus());
        final Field registryField = field(NetworkRegistry.class, "channels");
        @SuppressWarnings("unchecked")
        EnumMap<Side, Map<String, FMLEmbeddedChannel>> registry =
            (EnumMap<Side, Map<String, FMLEmbeddedChannel>>) registryField.get(NetworkRegistry.INSTANCE);
        EnumMap<Side, FMLEmbeddedChannel[]> previous = new EnumMap<Side, FMLEmbeddedChannel[]>(Side.class);
        EnumSet<Side> createdSides = EnumSet.noneOf(Side.class);
        for (Side side : Side.values()) if (registry.get(side) == null) {
            registry.put(side, new HashMap<String, FMLEmbeddedChannel>()); createdSides.add(side);
        }
        for (Side side : Side.values()) previous.put(side, new FMLEmbeddedChannel[] {
            registry.get(side).remove(TrinketsSyncBridge.CHANNEL), registry.get(side).remove(TrinketsSyncBridge.ORIGINAL_CHANNEL) });
        Field bridgeChannel = field(TrinketsSyncBridge.class, "channel");
        Object oldBridgeChannel = bridgeChannel.get(null);
        Field sided = field(FMLCommonHandler.class, "sidedDelegate");
        Object oldSided = sided.get(FMLCommonHandler.instance());
        if (oldSided == null) sided.set(FMLCommonHandler.instance(), Proxy.newProxyInstance(
            IFMLSidedHandler.class.getClassLoader(), new Class<?>[] { IFMLSidedHandler.class }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
                    if ("getSide".equals(method.getName())) return Side.CLIENT;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
            }));
        Field factory = field(FMLEventChannel.class, "factory");
        Object oldFactory = factory.get(null);
        for (Object value : factory.getType().getEnumConstants()) if ("CLIENT".equals(value.toString())) factory.set(null, value);
        Object bridge = field(TrinketsSyncBridge.class, "EVENTS").get(null);
        List<Subscription> subscriptions = new ArrayList<Subscription>();
        Peer first = null, second = null, local = null;
        try {
            FMLEventChannel transport = NetworkRegistry.INSTANCE.newEventDrivenChannel(TrinketsSyncBridge.CHANNEL);
            bridgeChannel.set(null, transport);
            // Plain JUnit does not run Forge's EventSubscriptionTransformer,
            // which supplies event constructors and per-type listener lists.
            // Install the equivalent typed ASM subscribers, then use the real
            // EventBus.post / FMLEventChannel.fireRead paths for every message.
            subscriptions.add(subscribe((EventBus) field(FMLEventChannel.class, "eventBus").get(transport),
                bridge, "received", FMLNetworkEvent.ClientCustomPacketEvent.class));
            subscriptions.add(subscribe(MinecraftForge.EVENT_BUS, bridge, "registered",
                FMLNetworkEvent.CustomPacketRegistrationEvent.class));
            OptimizationModule module = OptimizationModule.byId("trinkets-sync-delta");
            assertNotNull(module); OptimizerRegistry.breaker(module).configure(true, 3);
            OptimizerRegistry.breaker(module).patchInstalled("xzeroair.trinkets.network.NetworkHandler", "test");
            SimpleNetworkWrapper original = NetworkRegistry.INSTANCE.newSimpleChannel(TrinketsSyncBridge.ORIGINAL_CHANNEL);
            Class<? extends WireMessage> messageClass = messageClass();
            register(original, messageClass);
            first = new Peer(); second = new Peer();
            writes = 0; DELIVERED.clear();
            byte[] body = body(1024);

            // An unregistered peer receives the original channel and codec.
            send(original, messageClass, second, body);
            assertEquals(TrinketsSyncBridge.ORIGINAL_CHANNEL, second.peek().channel());
            second.deliver(); assertArrayEquals(body, DELIVERED.get(0));

            first.register("REGISTER");
            send(original, messageClass, first, body);
            assertEquals(TrinketsSyncBridge.CHANNEL, first.peek().channel());
            assertTrue(frame(first.peek()).full); first.deliver();
            body[200]++;
            send(original, messageClass, first, body);
            assertFalse(frame(first.peek()).full); first.deliver();
            send(original, messageClass, first, body);
            assertFalse(frame(first.peek()).full); first.deliver();
            assertEquals("unchanged payload still reaches the original handler", 4, DELIVERED.size());
            assertArrayEquals(body, DELIVERED.get(3));

            // A second consumer starts with its own full baseline.
            second.register("REGISTER"); send(original, messageClass, second, body);
            assertTrue(frame(second.peek()).full); second.deliver();
            first.register("UNREGISTER"); send(original, messageClass, first, body);
            assertEquals(TrinketsSyncBridge.ORIGINAL_CHANNEL, first.peek().channel()); first.deliver();
            first.register("REGISTER"); send(original, messageClass, first, body);
            assertTrue(frame(first.peek()).full); first.deliver();
            assertEquals(7, writes); assertEquals(7, DELIVERED.size());
            for (int index = 2; index < DELIVERED.size(); index++) assertArrayEquals(body, DELIVERED.get(index));

            byte[] small = body(32);
            send(original, messageClass, first, small);
            assertEquals(TrinketsSyncBridge.ORIGINAL_CHANNEL, first.peek().channel()); first.deliver();
            assertArrayEquals(small, DELIVERED.get(7));
            send(original, messageClass, first, body);
            assertTrue("legacy bypass retires the prior sender baseline", frame(first.peek()).full); first.deliver();

            OptimizerRegistry.breaker(module).configure(false, 3);
            send(original, messageClass, first, body);
            assertEquals(TrinketsSyncBridge.ORIGINAL_CHANNEL, first.peek().channel()); first.deliver();
            OptimizerRegistry.breaker(module).configure(true, 3);
            OptimizerRegistry.breaker(module).patchInstalled("xzeroair.trinkets.network.NetworkHandler", "test");
            send(original, messageClass, first, body); first.deliver();
            assertArrayEquals("pause/resume must not desynchronize the byte stream", body, DELIVERED.get(10));

            first.serverChannel.attr(NetworkDispatcher.FML_DISPATCHER).set(null);
            WireMessage dropped = messageClass.newInstance(); dropped.body = body;
            TrinketsSyncBridge.sendTo(original, dropped, first.player);
            assertTrue("a connection removed during routing is not sent a packet", first.capture.packets.isEmpty());
            assertEquals(11, DELIVERED.size());
            first.serverChannel.attr(NetworkDispatcher.FML_DISPATCHER).set(first.capture);
            send(original, messageClass, first, body);
            assertTrue("a drop retires the old sender baseline", frame(first.peek()).full); first.deliver();
            assertArrayEquals(body, DELIVERED.get(11));
            assertEquals(13, writes); assertEquals(12, DELIVERED.size());

            // Integrated-server memory channels have no network bandwidth to save.
            local = new Peer(); local.useLocalTransport(); local.register("REGISTER");
            send(original, messageClass, local, body);
            assertEquals("a registered local peer keeps the original codec", TrinketsSyncBridge.ORIGINAL_CHANNEL, local.peek().channel());
            local.deliver(); assertArrayEquals(body, DELIVERED.get(12));
            send(original, messageClass, local, body);
            assertEquals(TrinketsSyncBridge.ORIGINAL_CHANNEL, local.peek().channel());
            local.deliver(); assertArrayEquals(body, DELIVERED.get(13));
            assertEquals(15, writes); assertEquals(14, DELIVERED.size());
        } finally {
            if (first != null) first.close(); if (second != null) second.close(); if (local != null) local.close();
            for (Subscription subscription : subscriptions) subscription.close();
            bridgeChannel.set(null, oldBridgeChannel); factory.set(null, oldFactory);
            sided.set(FMLCommonHandler.instance(), oldSided);
            for (Side side : Side.values()) {
                restore(registry.get(side), TrinketsSyncBridge.CHANNEL, previous.get(side)[0]);
                restore(registry.get(side), TrinketsSyncBridge.ORIGINAL_CHANNEL, previous.get(side)[1]);
                if (createdSides.contains(side)) registry.remove(side);
            }
            OptimizerRegistry.configure(ClientOptimizerConfig.capture()); DELIVERED.clear();
        }
    }

    private static void restore(Map<String, FMLEmbeddedChannel> map, String name, FMLEmbeddedChannel previous) {
        FMLEmbeddedChannel removed = map.remove(name); if (removed != null) removed.finishAndReleaseAll();
        if (previous != null) map.put(name, previous);
    }

    private static Subscription subscribe(EventBus bus, Object target, String name, final Class<? extends Event> type)
            throws Exception {
        ModMetadata metadata = new ModMetadata(); metadata.modId = "ice_test"; metadata.name = "ICE transport test"; metadata.version = "1";
        final ASMEventHandler handler = new ASMEventHandler(target, target.getClass().getMethod(name, type),
            new DummyModContainer(metadata), false);
        IEventListener listener = new IEventListener() {
            @Override public void invoke(Event event) { if (type.isInstance(event)) handler.invoke(event); }
        };
        int id = field(EventBus.class, "busID").getInt(bus);
        Event sample = (Event) unsafe().allocateInstance(type);
        sample.getListenerList().register(id, handler.getPriority(), listener);
        return new Subscription(id, listener);
    }

    private static final class Subscription {
        final int bus;
        final IEventListener listener;
        Subscription(int bus, IEventListener listener) { this.bus = bus; this.listener = listener; }
        void close() { ListenerList.unregisterAll(bus, listener); }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void register(SimpleNetworkWrapper original, Class<? extends WireMessage> messageClass) {
        original.registerMessage(new IMessageHandler() {
            @Override public IMessage onMessage(IMessage message, MessageContext context) {
                assertEquals(Side.CLIENT, context.side); DELIVERED.add(((WireMessage) message).body.clone()); return null;
            }
        }, (Class) messageClass, 2, Side.CLIENT);
    }

    private static void send(SimpleNetworkWrapper original, Class<? extends WireMessage> type, Peer peer, byte[] body) throws Exception {
        WireMessage message = type.newInstance(); message.body = body;
        TrinketsSyncBridge.sendTo(original, message, peer.player);
        assertEquals(1, peer.capture.packets.size());
    }

    private static TrinketsDeltaSession.Frame frame(FMLProxyPacket packet) {
        return TrinketsDeltaSession.Frame.read(packet.payload().duplicate());
    }

    private static byte[] body(int size) {
        ByteBuf buffer = Unpooled.buffer(size, size);
        try {
            buffer.writeInt(3).writeInt(2).writeInt(42).writeBoolean(true).writeBoolean(true);
            while (buffer.writerIndex() < size) buffer.writeByte(buffer.writerIndex() & 127);
            byte[] bytes = new byte[size]; buffer.readBytes(bytes); return bytes;
        } finally { buffer.release(); }
    }

    @SuppressWarnings("unchecked") private static Class<? extends WireMessage> messageClass() {
        String name = "xzeroair/trinkets/network/SyncItemDataPacket";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(WireMessage.class), null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(WireMessage.class), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd(); writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        return (Class<? extends WireMessage>) new ClassLoader(TrinketsSyncTransportTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    public static class WireMessage implements IMessage {
        public byte[] body;
        @Override public void toBytes(ByteBuf output) { writes++; output.writeBytes(body); }
        @Override public void fromBytes(ByteBuf input) { body = new byte[input.readableBytes()]; input.readBytes(body); }
    }

    public static final class CapturingDispatcher extends NetworkDispatcher {
        List<FMLProxyPacket> packets;
        private CapturingDispatcher(NetworkManager manager) { super(manager); }
        @Override public void sendProxy(FMLProxyPacket packet) { packets.add(packet); }
    }

    private static final class Peer {
        final NetworkManager server = new NetworkManager(EnumPacketDirection.SERVERBOUND);
        final NetworkManager client = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
        final EmbeddedChannel serverChannel = new EmbeddedChannel(server);
        final EmbeddedChannel clientChannel = new EmbeddedChannel(client);
        final EntityPlayerMP player;
        final NetHandlerPlayClient clientHandler;
        final CapturingDispatcher capture;
        final CapturingDispatcher clientDispatcher;
        LocalChannel localChannel;
        Peer() throws Exception {
            Unsafe unsafe = unsafe();
            player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            NetHandlerPlayServer serverHandler = (NetHandlerPlayServer) unsafe.allocateInstance(NetHandlerPlayServer.class);
            field(NetHandlerPlayServer.class, "netManager").set(serverHandler, server);
            player.connection = serverHandler; server.setNetHandler(serverHandler);
            clientHandler = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            field(NetHandlerPlayClient.class, "netManager").set(clientHandler, client); client.setNetHandler(clientHandler);
            capture = dispatcher(server); clientDispatcher = dispatcher(client);
            serverChannel.attr(NetworkDispatcher.FML_DISPATCHER).set(capture);
            clientChannel.attr(NetworkDispatcher.FML_DISPATCHER).set(clientDispatcher);
        }
        void useLocalTransport() throws Exception {
            localChannel = new LocalChannel();
            field(NetworkManager.class, "channel").set(server, localChannel);
            localChannel.attr(NetworkDispatcher.FML_DISPATCHER).set(capture);
            assertTrue(server.isLocalChannel());
        }
        private CapturingDispatcher dispatcher(NetworkManager manager) throws Exception {
            CapturingDispatcher dispatcher = (CapturingDispatcher) unsafe().allocateInstance(CapturingDispatcher.class);
            field(NetworkDispatcher.class, "manager").set(dispatcher, manager);
            dispatcher.packets = new ArrayList<FMLProxyPacket>(); return dispatcher;
        }
        void register(String operation) {
            MinecraftForge.EVENT_BUS.post(new FMLNetworkEvent.CustomPacketRegistrationEvent<NetHandlerPlayServer>(
                server, Collections.singleton(TrinketsSyncBridge.CHANNEL), operation, Side.SERVER, NetHandlerPlayServer.class));
        }
        FMLProxyPacket peek() { return capture.packets.get(0); }
        void deliver() {
            FMLProxyPacket packet = capture.packets.remove(0);
            packet.setTarget(Side.CLIENT); packet.setDispatcher(clientDispatcher); packet.processPacket(clientHandler);
            assertEquals("the receive path must consume its owned buffer", 0, packet.payload().refCnt());
        }
        void close() {
            for (FMLProxyPacket packet : capture.packets) if (packet.payload().refCnt() > 0) packet.payload().release();
            if (localChannel != null) localChannel.unsafe().closeForcibly();
            serverChannel.finishAndReleaseAll(); clientChannel.finishAndReleaseAll();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Unsafe unsafe() throws Exception { return (Unsafe) field(Unsafe.class, "theUnsafe").get(null); }
}

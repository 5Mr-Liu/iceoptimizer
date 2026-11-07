package dev.rlcraft.ice.optimizer.compat.trinkets;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** An optional transport for exact original item-sync bytes, scoped to each connection. */
public final class TrinketsSyncBridge {
    static final String CHANNEL = "ice_nbt_v1";
    static final String ORIGINAL_CHANNEL = "xat";
    private static final String ITEM_MESSAGE = "xzeroair.trinkets.network.SyncItemDataPacket";
    private static final int MODULE = OptimizationModule.TRINKETS_SYNC_DELTA.ordinal();
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MIN_DELTA_PAYLOAD = TrinketsDeltaSession.Frame.HEADER_BYTES + 9 + 16;
    private static final AttributeKey<Connection> CONNECTION = AttributeKey.valueOf("ice.trinkets.sync.v1");
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final TrinketsSyncBridge EVENTS = new TrinketsSyncBridge();
    private static FMLEventChannel channel;
    private static volatile boolean activated;

    private TrinketsSyncBridge() { }

    public static void initialize() {
        if (channel != null || !Loader.isModLoaded(ORIGINAL_CHANNEL)) return;
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        channel.register(EVENTS);
        MinecraftForge.EVENT_BUS.register(EVENTS);
    }

    public static void sendTo(SimpleNetworkWrapper original, IMessage message, EntityPlayerMP player) {
        if (!handles(message) || player.connection.netManager.isLocalChannel()
            || !connection(player.connection.netManager).supported) {
            original.sendTo(message, player);
            return;
        }
        FMLProxyPacket packet = (FMLProxyPacket) original.getPacketFrom(message);
        try { sendEncoded(player, packet, payload(packet)); }
        finally { packet.payload().release(); }
    }

    public static void sendToTracking(SimpleNetworkWrapper original, IMessage message, Entity entity) {
        if (!handles(message)) { original.sendToAllTracking(message, entity); return; }
        Set<? extends EntityPlayer> players = ((WorldServer) entity.world).getEntityTracker().getTrackingPlayers(entity);
        boolean supported = false;
        for (EntityPlayer tracked : players) {
            EntityPlayerMP player = (EntityPlayerMP) tracked;
            if (NetworkDispatcher.get(player.connection.netManager) != null
                && !player.connection.netManager.isLocalChannel()
                && connection(player.connection.netManager).supported) { supported = true; break; }
        }
        if (!supported) { original.sendToAllTracking(message, entity); return; }
        // The original tracking send also encodes once for all observers. In particular,
        // an old peer must not cause a second call to the item's share-tag writer.
        FMLProxyPacket packet = (FMLProxyPacket) original.getPacketFrom(message);
        try {
            byte[] encoded = payload(packet);
            // Match Forge's recipient selection after encoding, including changes made by share-tag callbacks.
            players = ((WorldServer) entity.world).getEntityTracker().getTrackingPlayers(entity);
            for (EntityPlayer tracked : players) {
                EntityPlayerMP player = (EntityPlayerMP) tracked;
                if (NetworkDispatcher.get(player.connection.netManager) != null) sendEncoded(player, packet, encoded);
            }
        } finally { packet.payload().release(); }
    }

    private static boolean handles(IMessage message) {
        return channel != null && OptimizerBridge.isEnabled(MODULE)
            && ITEM_MESSAGE.equals(message.getClass().getName());
    }

    private static byte[] payload(FMLProxyPacket packet) {
        ByteBuf buffer = packet.payload();
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void sendEncoded(EntityPlayerMP player, FMLProxyPacket original, byte[] bytes) {
        Connection state = connection(player.connection.netManager);
        synchronized (state) {
            NetworkDispatcher dispatcher = NetworkDispatcher.get(player.connection.netManager);
            if (dispatcher == null) { state.sender = null; return; }
            if (!state.supported || player.connection.netManager.isLocalChannel()
                || bytes.length <= MIN_DELTA_PAYLOAD || bytes.length > MAX_BYTES
                || !ORIGINAL_CHANNEL.equals(original.channel())) {
                state.sender = null;
                // Each peer owns its buffer indices and reference count. The byte snapshot is read-only.
                dispatcher.sendProxy(
                    new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(bytes)), original.channel()));
                return;
            }
            if (state.sender == null) state.sender = new TrinketsDeltaSession.Sender(EPOCH.incrementAndGet(), MAX_ENTRIES, MAX_BYTES);
            TrinketsDeltaSession.Key key = key(bytes);
            TrinketsDeltaSession.Frame frame = state.sender.encode(key, bytes);
            int length = TrinketsDeltaSession.Frame.HEADER_BYTES + frame.bodyLength();
            PacketBuffer wire = new PacketBuffer(Unpooled.buffer(length, length));
            try {
                frame.write(wire);
                channel.sendTo(new FMLProxyPacket(wire, CHANNEL), player);
                if (!activated) {
                    activated = true;
                    OptimizerBridge.activate(MODULE, "Trinkets 按连接差量传输原消息，保留全部装备与 NBT 回调");
                }
            } catch (RuntimeException error) {
                state.sender = null;
                throw error;
            }
        }
    }

    /** Original FML discriminator followed by slot, handler and entity ID. */
    static TrinketsDeltaSession.Key key(byte[] bytes) {
        if (bytes.length < 15) throw new IllegalArgumentException("Incomplete Trinkets item-sync header");
        return new TrinketsDeltaSession.Key(integer(bytes, 9), integer(bytes, 5), integer(bytes, 1));
    }

    private static int integer(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
            | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }

    private static Connection connection(NetworkManager manager) {
        Attribute<Connection> attribute = manager.channel().attr(CONNECTION);
        Connection state = attribute.get();
        if (state != null) return state;
        Connection fresh = new Connection();
        Connection previous = attribute.setIfAbsent(fresh);
        return previous == null ? fresh : previous;
    }

    @SubscribeEvent public void registered(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER || !event.getRegistrations().contains(CHANNEL)) return;
        Connection state = connection(event.getManager());
        synchronized (state) {
            state.supported = "REGISTER".equals(event.getOperation());
            if (!state.supported) state.sender = null;
        }
    }

    @SubscribeEvent public void received(FMLNetworkEvent.ClientCustomPacketEvent event) {
        if (!CHANNEL.equals(event.getPacket().channel())) return;
        Connection state = connection(event.getManager());
        byte[] original;
        ByteBuf incoming = event.getPacket().payload();
        try {
            TrinketsDeltaSession.Frame frame = TrinketsDeltaSession.Frame.read(incoming);
            if (incoming.isReadable()) throw new IllegalArgumentException("Trailing Trinkets delta data");
            synchronized (state) {
                if (state.receiver == null) state.receiver = new TrinketsDeltaSession.Receiver(MAX_ENTRIES, MAX_BYTES);
                original = state.receiver.decode(frame);
            }
        } finally { incoming.release(); }
        FMLProxyPacket restored = new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(original)), ORIGINAL_CHANNEL);
        restored.setTarget(Side.CLIENT);
        restored.setDispatcher(NetworkDispatcher.get(event.getManager()));
        // The original codec and ThreadSafePacket still perform both NBT loads,
        // equipment callbacks and normal main-thread scheduling, once per message.
        try { restored.processPacket(event.getHandler()); }
        finally {
            // The original indexed codec releases normal input; clean up only an early rejection.
            if (restored.payload().refCnt() > 0) restored.payload().release();
        }
    }

    private static final class Connection {
        private volatile boolean supported;
        private TrinketsDeltaSession.Sender sender;
        private TrinketsDeltaSession.Receiver receiver;
    }
}

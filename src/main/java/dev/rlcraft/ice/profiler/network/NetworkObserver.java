package dev.rlcraft.ice.profiler.network;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

public final class NetworkObserver {
    private static final String WIRE_HANDLER = "ice_profiler_wire";
    private static final String PACKET_HANDLER = "ice_profiler_packets";

    private NetworkObserver() {
    }

    public static void attach(NetworkManager manager) {
        if (!IceConfig.general.networkMetrics) return;
        if (manager == null || manager.hasNoChannel()) return;
        final Channel channel = manager.channel();
        if (channel == null || !channel.isOpen()) return;
        channel.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    if (channel.pipeline().get(WIRE_HANDLER) == null) {
                        channel.pipeline().addFirst(WIRE_HANDLER, new WireCounter());
                    }
                    if (channel.pipeline().get(PACKET_HANDLER) == null && channel.pipeline().get("packet_handler") != null) {
                        channel.pipeline().addBefore("packet_handler", PACKET_HANDLER, new PacketCounter());
                    }
                } catch (Throwable error) {
                    IceProfilerMod.LOGGER.debug("无法安装只读网络计数器", error);
                }
            }
        });
    }

    private static final class WireCounter extends ChannelDuplexHandler {
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            try { if (message instanceof ByteBuf) ProfilerRuntime.INSTANCE.metrics().recordNetworkBytes(true, ((ByteBuf) message).readableBytes()); }
            catch (Throwable ignored) { }
            super.channelRead(context, message);
        }

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { if (message instanceof ByteBuf) ProfilerRuntime.INSTANCE.metrics().recordNetworkBytes(false, ((ByteBuf) message).readableBytes()); }
            catch (Throwable ignored) { }
            super.write(context, message, promise);
        }
    }

    private static final class PacketCounter extends ChannelDuplexHandler {
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            try { if (message instanceof Packet) ProfilerRuntime.INSTANCE.metrics().recordPacket(true); }
            catch (Throwable ignored) { }
            super.channelRead(context, message);
        }

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { if (message instanceof Packet) ProfilerRuntime.INSTANCE.metrics().recordPacket(false); }
            catch (Throwable ignored) { }
            super.write(context, message, promise);
        }
    }
}

package dev.rlcraft.ice.profiler.network;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.FatalErrors;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

public final class NetworkObserver {
    private static final String WIRE_HANDLER = "ice_profiler_wire";
    private static final String PACKET_HANDLER = "ice_profiler_packets";
    private static final AttributeKey<Object> INSTALLED = AttributeKey.valueOf("ice_profiler_counters_installed");

    private NetworkObserver() {
    }

    public static void attach(NetworkManager manager) {
        if (!IceConfig.general.networkMetrics) return;
        if (manager == null || manager.hasNoChannel()) return;
        attach(manager.channel());
    }

    static void attach(final Channel channel) {
        if (channel == null || !channel.isOpen()) return;
        final Attribute<Object> installed = channel.attr(INSTALLED);
        if (installed.get() != null) return;
        final Object attempt = new Object();
        if (!installed.compareAndSet(null, attempt)) return;
        try {
            channel.eventLoop().execute(new Runnable() {
                @Override public void run() {
                    boolean complete = false;
                    try {
                        if (channel.pipeline().get(WIRE_HANDLER) == null) {
                            channel.pipeline().addFirst(WIRE_HANDLER, new WireCounter());
                        }
                        if (channel.pipeline().get(PACKET_HANDLER) == null && channel.pipeline().get("packet_handler") != null) {
                            channel.pipeline().addBefore("packet_handler", PACKET_HANDLER, new PacketCounter());
                        }
                        complete = channel.pipeline().get(WIRE_HANDLER) != null
                            && channel.pipeline().get(PACKET_HANDLER) != null;
                    } catch (Throwable error) {
                        FatalErrors.rethrowIfFatal(error);
                        IceProfilerMod.LOGGER.debug("无法安装只读网络计数器", error);
                    } finally {
                        if (!complete) installed.compareAndSet(attempt, null);
                    }
                }
            });
        } catch (Throwable error) {
            installed.compareAndSet(attempt, null);
            FatalErrors.rethrowIfFatal(error);
            IceProfilerMod.LOGGER.debug("无法提交只读网络计数器安装", error);
        }
    }

    private static class Counter extends ChannelDuplexHandler {
        @Override public void handlerRemoved(ChannelHandlerContext context) throws Exception {
            context.channel().attr(INSTALLED).set(null);
            super.handlerRemoved(context);
        }
    }

    private static final class WireCounter extends Counter {
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            try { if (message instanceof ByteBuf) ProfilerRuntime.INSTANCE.metrics().recordNetworkBytes(true, ((ByteBuf) message).readableBytes()); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
            super.channelRead(context, message);
        }

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { if (message instanceof ByteBuf) ProfilerRuntime.INSTANCE.metrics().recordNetworkBytes(false, ((ByteBuf) message).readableBytes()); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
            super.write(context, message, promise);
        }
    }

    private static final class PacketCounter extends Counter {
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            try { if (message instanceof Packet) ProfilerRuntime.INSTANCE.metrics().recordPacket(true); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
            super.channelRead(context, message);
        }

        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try { if (message instanceof Packet) ProfilerRuntime.INSTANCE.metrics().recordPacket(false); }
            catch (Throwable ignored) { FatalErrors.rethrowIfFatal(ignored); }
            super.write(context, message, promise);
        }
    }
}

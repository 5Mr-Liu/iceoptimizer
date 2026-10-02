package dev.rlcraft.ice.profiler.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class NetworkObserverInstallationTest {
    @Test
    public void queuesOneAttemptPerChannelAndKeepsMessagesUnchanged() {
        Fixture fixture = new Fixture(true);
        try {
            for (int index = 0; index < 200; index++) NetworkObserver.attach(fixture.channel);
            assertEquals(1, fixture.submissions);
            fixture.drain();
            assertNotNull(fixture.delegate.pipeline().get("ice_profiler_wire"));
            assertNotNull(fixture.delegate.pipeline().get("ice_profiler_packets"));
            for (int index = 0; index < 200; index++) NetworkObserver.attach(fixture.channel);
            assertEquals(1, fixture.submissions);
            Object message = new Object();
            fixture.delegate.writeInbound(message);
            assertSame(message, fixture.delegate.readInbound());
            fixture.delegate.writeOutbound(message);
            assertSame(message, fixture.delegate.readOutbound());
        } finally {
            fixture.delegate.finishAndReleaseAll();
        }
    }

    @Test
    public void retriesIncompletePipelineAndInstallsOnReplacementChannel() {
        Fixture old = new Fixture(false);
        Fixture replacement = new Fixture(true);
        try {
            NetworkObserver.attach(old.channel);
            old.drain();
            old.delegate.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
            NetworkObserver.attach(old.channel);
            old.drain();
            assertEquals(2, old.submissions);
            assertNotNull(old.delegate.pipeline().get("ice_profiler_packets"));
            old.delegate.close();
            NetworkObserver.attach(old.channel);
            assertEquals(2, old.submissions);
            NetworkObserver.attach(replacement.channel);
            replacement.drain();
            assertEquals(1, replacement.submissions);
            assertNotNull(replacement.delegate.pipeline().get("ice_profiler_packets"));
        } finally {
            old.delegate.finishAndReleaseAll();
            replacement.delegate.finishAndReleaseAll();
        }
    }

    @Test
    public void retriesAfterRejectedSubmission() {
        Fixture fixture = new Fixture(true);
        try {
            fixture.rejectNext = true;
            NetworkObserver.attach(fixture.channel);
            NetworkObserver.attach(fixture.channel);
            fixture.drain();
            assertEquals(2, fixture.submissions);
            assertNotNull(fixture.delegate.pipeline().get("ice_profiler_packets"));
        } finally {
            fixture.delegate.finishAndReleaseAll();
        }
    }

    @Test
    public void concurrentAttachDoesNotDuplicatePendingTasksAndRemovedCountersAreReinstalled() throws Exception {
        final Fixture fixture = new Fixture(true);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(8);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            for (int index = 0; index < 8; index++) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            start.await();
                            NetworkObserver.attach(fixture.channel);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            failure.compareAndSet(null, error);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            done.countDown();
                        }
                    }
                }).start();
            }
            start.countDown();
            org.junit.Assert.assertTrue(done.await(5L, TimeUnit.SECONDS));
            org.junit.Assert.assertNull(failure.get());
            assertEquals(1, fixture.submissions);
            fixture.drain();
            fixture.delegate.pipeline().remove("ice_profiler_packets");
            NetworkObserver.attach(fixture.channel);
            fixture.drain();
            assertEquals(2, fixture.submissions);
            assertNotNull(fixture.delegate.pipeline().get("ice_profiler_packets"));
        } finally {
            fixture.delegate.finishAndReleaseAll();
        }
    }

    private static final class Fixture {
        final EmbeddedChannel delegate = new EmbeddedChannel();
        final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
        final Channel channel;
        int submissions;
        boolean rejectNext;

        Fixture(boolean packetHandler) {
            if (packetHandler) delegate.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
            final EventLoop loop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(),
                new Class<?>[] { EventLoop.class }, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("execute")) {
                            submissions++;
                            if (rejectNext) {
                                rejectNext = false;
                                throw new java.util.concurrent.RejectedExecutionException("test");
                            }
                            tasks.add((Runnable) args[0]);
                            return null;
                        }
                        return delegate(method, delegate.eventLoop(), args);
                    }
                });
            channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[] { Channel.class }, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("eventLoop")) return loop;
                        return delegate(method, delegate, args);
                    }
                });
        }

        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }

        private static Object delegate(Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
    }
}

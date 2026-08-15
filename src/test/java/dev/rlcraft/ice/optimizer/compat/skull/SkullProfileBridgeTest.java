package dev.rlcraft.ice.optimizer.compat.skull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SkullProfileBridgeTest {
    private boolean previousEnabled;
    private boolean previousModule;

    @Before
    public void setUp() {
        previousEnabled = OptimizerConfig.settings.enabled;
        previousModule = OptimizerConfig.settings.skullProfileAsync;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.skullProfileAsync = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OptimizerRegistry.breaker(OptimizationModule.SKULL_PROFILE_ASYNC)
            .patchInstalled("synthetic", "test");
        SkullProfileBridge.configure(64, 5, 10, 16);
    }

    @After
    public void tearDown() {
        SkullProfileBridge.resetForTests();
        OptimizerConfig.settings.enabled = previousEnabled;
        OptimizerConfig.settings.skullProfileAsync = previousModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test
    public void renderLookupReturnsImmediatelyAndDeduplicatesInflightNetworkWork() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GameProfile resolved = texturedProfile("AsyncHead");
        SkullProfileBridge.installLookupForTests(new SkullProfileBridge.ProfileLookup() {
            @Override public GameProfile lookup(GameProfile input) throws Exception {
                calls.incrementAndGet();
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return resolved;
            }
        });

        GameProfile incomplete = new GameProfile(null, "AsyncHead");
        long start = System.nanoTime();
        assertSame(incomplete, SkullProfileBridge.resolveForRenderLookup(incomplete));
        assertTrue("render lookup must not wait for the resolver",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100L);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) {
            assertSame(incomplete, SkullProfileBridge.decorateForRender(incomplete));
        }
        assertEquals(1, calls.get());

        release.countDown();
        GameProfile rendered = awaitResolved(incomplete);
        assertSame(resolved, rendered);
        assertEquals(1, calls.get());
    }

    @Test
    public void failedLookupIsNegativeCachedInsteadOfRetriedEveryFrame() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        SkullProfileBridge.installLookupForTests(new SkullProfileBridge.ProfileLookup() {
            @Override public GameProfile lookup(GameProfile input) {
                calls.incrementAndGet();
                return input;
            }
        });
        GameProfile incomplete = new GameProfile(null, "MissingHead");
        SkullProfileBridge.resolveForRenderLookup(incomplete);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (calls.get() == 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(1, calls.get());
        Thread.sleep(30L);
        for (int i = 0; i < 20; i++) SkullProfileBridge.decorateForRender(incomplete);
        assertEquals(1, calls.get());
    }

    @Test
    public void reconfigureDiscardsAResultFromThePreviousExecutorGeneration() throws Exception {
        final CountDownLatch oldStarted = new CountDownLatch(1);
        final CountDownLatch releaseOld = new CountDownLatch(1);
        final GameProfile oldResult = texturedProfile("GenerationHead");
        final GameProfile newResult = texturedProfile("GenerationHead");
        SkullProfileBridge.installLookupForTests(new SkullProfileBridge.ProfileLookup() {
            @Override public GameProfile lookup(GameProfile input) {
                oldStarted.countDown();
                while (releaseOld.getCount() != 0L) {
                    try {
                        releaseOld.await(20L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Simulate an Authlib/socket operation that does not finish on interruption.
                    }
                }
                return oldResult;
            }
        });

        GameProfile incomplete = new GameProfile(null, "GenerationHead");
        SkullProfileBridge.resolveForRenderLookup(incomplete);
        assertTrue(oldStarted.await(2L, TimeUnit.SECONDS));

        SkullProfileBridge.configure(64, 5, 10, 16);
        SkullProfileBridge.installLookupForTests(new SkullProfileBridge.ProfileLookup() {
            @Override public GameProfile lookup(GameProfile input) {
                return newResult;
            }
        });
        SkullProfileBridge.resolveForRenderLookup(incomplete);
        assertSame(newResult, awaitResolved(incomplete));

        releaseOld.countDown();
        Thread.sleep(30L);
        assertSame(newResult, SkullProfileBridge.decorateForRender(incomplete));
    }

    private static GameProfile awaitResolved(GameProfile incomplete) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        GameProfile current = incomplete;
        while (current == incomplete && System.nanoTime() < deadline) {
            Thread.sleep(5L);
            current = SkullProfileBridge.decorateForRender(incomplete);
        }
        return current;
    }

    private static GameProfile texturedProfile(String name) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        profile.getProperties().put("textures", new Property("textures", "payload"));
        return profile;
    }
}

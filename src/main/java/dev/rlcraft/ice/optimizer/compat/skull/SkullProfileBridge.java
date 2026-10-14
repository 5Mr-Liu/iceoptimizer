package dev.rlcraft.ice.optimizer.compat.skull;

import dev.rlcraft.ice.optimizer.FatalErrors;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.tileentity.TileEntitySkull;

/**
 * Applies the configured player-skull profile policy without ever waiting on
 * Mojang/Authlib from the render thread. Existing-only mode performs no
 * profile lookup; asynchronous mode preserves the eventual vanilla result.
 */
public final class SkullProfileBridge {
    private static final ConcurrentMap<String, Long> IN_FLIGHT =
        new ConcurrentHashMap<String, Long>();
    private static final Map<Type, MinecraftProfileTexture> NO_TEXTURES =
        Collections.emptyMap();
    private static final ProfileLookup VANILLA_LOOKUP = new ProfileLookup() {
        @Override public GameProfile lookup(GameProfile input) {
            return TileEntitySkull.updateGameProfile(input);
        }
    };

    private static volatile Cache<String, GameProfile> positive;
    private static volatile Cache<String, Boolean> negative;
    private static volatile ThreadPoolExecutor executor;
    private static volatile ProfileLookup lookup = VANILLA_LOOKUP;
    private static volatile long configurationGeneration;
    private static volatile LookupMode lookupMode = LookupMode.VANILLA;

    private SkullProfileBridge() {
    }

    public static void configure(int entries, int positiveTtlMinutes,
                                 int negativeTtlSeconds, int queueCapacity) {
        configure(true, true, entries, positiveTtlMinutes, negativeTtlSeconds, queueCapacity);
    }

    public static synchronized void configure(boolean allowIncompleteLookup, boolean asynchronous,
                                              int entries, int positiveTtlMinutes,
                                              int negativeTtlSeconds, int queueCapacity) {
        LookupMode nextMode = !allowIncompleteLookup ? LookupMode.EXISTING_ONLY
            : asynchronous ? LookupMode.ASYNC : LookupMode.VANILLA;
        configurationGeneration++;
        // Publish a non-querying transition before retiring an old worker. A
        // render racing reconfiguration must never fall into synchronous I/O.
        lookupMode = nextMode == LookupMode.ASYNC ? LookupMode.EXISTING_ONLY : nextMode;
        shutdownExecutor();
        Cache<String, GameProfile> oldPositive = positive;
        Cache<String, Boolean> oldNegative = negative;
        if (oldPositive != null) oldPositive.invalidateAll();
        if (oldNegative != null) oldNegative.invalidateAll();
        positive = null;
        negative = null;
        IN_FLIGHT.clear();
        if (nextMode != LookupMode.ASYNC) return;

        Cache<String, GameProfile> newPositive = Caffeine.newBuilder()
            .maximumSize(Math.max(64, entries))
            .expireAfterAccess(Math.max(5, positiveTtlMinutes), TimeUnit.MINUTES)
            .build();
        Cache<String, Boolean> newNegative = Caffeine.newBuilder()
            .maximumSize(Math.max(64, entries))
            .expireAfterWrite(Math.max(10, negativeTtlSeconds), TimeUnit.SECONDS)
            .build();
        ThreadPoolExecutor newExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(Math.max(16, queueCapacity)),
            new SkullLookupThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        newExecutor.prestartAllCoreThreads();
        positive = newPositive;
        negative = newNegative;
        executor = newExecutor;
        lookupMode = LookupMode.ASYNC;
    }

    /** Replaces LayerCustomHead's original synchronous TileEntitySkull call. */
    public static GameProfile resolveForRenderLookup(GameProfile input) {
        LookupMode currentMode = lookupMode;
        if (currentMode == LookupMode.EXISTING_ONLY) return input;
        if (currentMode != LookupMode.ASYNC || !enabled()) {
            return TileEntitySkull.updateGameProfile(input);
        }
        return resolveWithoutBlocking(input);
    }

    /** Rechecks the bounded cache immediately before the head is rendered. */
    public static GameProfile decorateForRender(GameProfile input) {
        if (lookupMode != LookupMode.ASYNC || !enabled()) return input;
        return resolveWithoutBlocking(input);
    }

    /**
     * Replaces TileEntitySkullRenderer's direct SkinManager call. The default
     * existing-only policy must stop before CustomSkinLoader or vanilla's
     * SkinManager can schedule work for an incomplete profile.
     */
    public static Map<Type, MinecraftProfileTexture> loadSkinFromCacheForRender(
        SkinManager skinManager, GameProfile input) {
        if (input == null) return NO_TEXTURES;
        LookupMode currentMode = lookupMode;
        if (hasTextureData(input)) return skinManager.loadSkinFromCache(input);
        if (currentMode == LookupMode.EXISTING_ONLY) return NO_TEXTURES;
        if (currentMode == LookupMode.ASYNC && enabled()) return NO_TEXTURES;
        return skinManager.loadSkinFromCache(input);
    }

    public static synchronized void shutdown() {
        lookupMode = LookupMode.VANILLA;
        configurationGeneration++;
        shutdownExecutor();
        Cache<String, GameProfile> positiveCache = positive;
        Cache<String, Boolean> negativeCache = negative;
        if (positiveCache != null) positiveCache.invalidateAll();
        if (negativeCache != null) negativeCache.invalidateAll();
        positive = null;
        negative = null;
        IN_FLIGHT.clear();
    }

    private static GameProfile resolveWithoutBlocking(final GameProfile input) {
        if (input == null || input.getName() == null || input.getName().isEmpty()) return input;
        if (hasTextureData(input)) return input;
        final String key = input.getName().toLowerCase(Locale.ROOT);
        Cache<String, GameProfile> positiveCache = positive;
        Cache<String, Boolean> negativeCache = negative;
        GameProfile cached = positiveCache == null ? null : positiveCache.getIfPresent(key);
        if (cached != null) return cached;
        if (negativeCache != null && negativeCache.getIfPresent(key) != null) return input;
        long generation = configurationGeneration;
        Long existing = IN_FLIGHT.putIfAbsent(key, Long.valueOf(generation));
        if (existing == null) {
            submit(key, input, generation);
        } else if (existing.longValue() != generation
            && IN_FLIGHT.replace(key, existing, Long.valueOf(generation))) {
            submit(key, input, generation);
        }
        return input;
    }

    private static void submit(final String key, final GameProfile input, final long generation) {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            IN_FLIGHT.remove(key, Long.valueOf(generation));
            return;
        }
        try {
            current.execute(new Runnable() {
                @Override public void run() {
                    try {
                        GameProfile resolved = lookup.lookup(input);
                        if (configurationGeneration != generation) return;
                        if (useful(resolved)) {
                            Cache<String, GameProfile> cache = positive;
                            if (cache != null) cache.put(key, resolved);
                            OptimizerRegistry.breaker(OptimizationModule.SKULL_PROFILE_ASYNC).recordSuccess();
                        } else {
                            cacheNegative(key);
                            OptimizerRegistry.breaker(OptimizationModule.SKULL_PROFILE_ASYNC)
                                .recordRejected("头颅资料未返回有效 UUID/纹理，已短期负缓存");
                        }
                    } catch (Throwable error) {
                        FatalErrors.rethrowIfFatal(error);
                        if (configurationGeneration == generation) {
                            cacheNegative(key);
                            OptimizerRegistry.breaker(OptimizationModule.SKULL_PROFILE_ASYNC)
                                .recordRejected("头颅资料联网失败，渲染继续使用默认皮肤");
                        }
                    } finally {
                        IN_FLIGHT.remove(key, Long.valueOf(generation));
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            IN_FLIGHT.remove(key, Long.valueOf(generation));
            if (configurationGeneration == generation) {
                cacheNegative(key);
                OptimizerRegistry.breaker(OptimizationModule.SKULL_PROFILE_ASYNC)
                    .recordRejected("头颅资料队列已满，渲染继续使用默认皮肤");
            }
        }
    }

    private static boolean useful(GameProfile profile) {
        return profile != null && (profile.getId() != null ||
            hasTextureData(profile));
    }

    private static boolean hasTextureData(GameProfile profile) {
        return profile != null && profile.getProperties().containsKey("textures");
    }

    private static void cacheNegative(String key) {
        Cache<String, Boolean> cache = negative;
        if (cache != null) cache.put(key, Boolean.TRUE);
    }

    private static boolean enabled() {
        try {
            return OptimizerRegistry.isOperational(OptimizationModule.SKULL_PROFILE_ASYNC);
        } catch (LinkageError | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private static void shutdownExecutor() {
        ThreadPoolExecutor current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
    }

    static synchronized void installLookupForTests(ProfileLookup testLookup) {
        lookup = testLookup == null ? VANILLA_LOOKUP : testLookup;
    }

    static synchronized void resetForTests() {
        shutdown();
        lookup = VANILLA_LOOKUP;
    }

    static boolean hasExecutorForTests() {
        return executor != null;
    }

    interface ProfileLookup {
        GameProfile lookup(GameProfile input) throws Exception;
    }

    private enum LookupMode {
        EXISTING_ONLY,
        ASYNC,
        VANILLA
    }

    private static final class SkullLookupThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ICE-Skull-Resolver-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }
}

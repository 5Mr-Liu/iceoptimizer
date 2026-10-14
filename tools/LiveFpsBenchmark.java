package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/** Isolated-game measurement fixture. Never included in the optimizer release. */
@Mod(modid = "ice_live_benchmark", name = "ICE isolated benchmark fixture", version = "1",
    clientSideOnly = true, acceptableRemoteVersions = "*")
public final class LiveFpsBenchmark {
    private final long[] frames = new long[300000];
    private final long[] renders = new long[300000];
    private final long[] ticks = new long[20000];
    private final long[] gpuTimes = new long[300000];
    private final int[] queryStart = new int[8], queryEnd = new int[8], queryScene = new int[8];
    private final boolean[] queryPending = new boolean[8];
    private int queryCursor, activeQuery = -1, gpuCount;
    private boolean gpuSupported;
    private int count, tickCount, scene;
    private volatile long connected;
    private long previousFrame, renderStart, tickStart;
    private volatile long sceneStart;
    private boolean launched, finished;
    private volatile boolean survivalApplied;
    private boolean walkingAbilitiesApplied;
    private float originalYaw, originalPitch;
    private int minEntities = Integer.MAX_VALUE, maxEntities;
    private boolean allActive = true;
    private final LiveGameplayScenario gameplay = new LiveGameplayScenario();
    private final int warmSeconds = Integer.getInteger("ice.bench.warmSeconds", 60);
    private final int sampleSeconds = Integer.getInteger("ice.bench.sampleSeconds", 60);
    private final boolean survival = Boolean.getBoolean("ice.bench.survival");
    private final boolean walk = Boolean.getBoolean("ice.bench.walk");
    private final boolean ablation = Boolean.getBoolean("ice.bench.ablation");
    private final boolean runtimeAblation = Boolean.getBoolean("ice.bench.runtimeAblation");
    private final boolean terrainAblation = Boolean.getBoolean("ice.bench.terrainAblation");
    private final boolean modelAblation = Boolean.getBoolean("ice.bench.modelAblation");
    private final boolean entityAblation = Boolean.getBoolean("ice.bench.entityAblation");
    private final boolean anyAblation = ablation || runtimeAblation || terrainAblation || modelAblation || entityAblation;
    private final boolean profileSections = Boolean.getBoolean("ice.bench.profileSections");
    private final boolean stableCreative = Boolean.getBoolean("ice.bench.stableCreative");
    private final boolean fixedCameraPath = Boolean.getBoolean("ice.bench.fixedCameraPath");
    private final FixedRenderCamera renderCamera = new FixedRenderCamera();
    private final float requestedYaw = Float.parseFloat(System.getProperty("ice.bench.cameraYaw", "NaN"));
    private final float requestedPitch = Float.parseFloat(System.getProperty("ice.bench.cameraPitch", "NaN"));
    private final boolean fixedNaturalCamera = Float.isFinite(requestedYaw) && Float.isFinite(requestedPitch);
    private volatile boolean stableCreativeApplied;
    private boolean pendingDiagnostic;
    private Boolean pendingKeyPause;
    private Object[] initialChunks, initialChunkSnapshot;
    private Boolean pendingRebuildPause;
    private int pendingRebuildTicks;
    private boolean sectionPending;
    private long shutdownAfterCapture;
    private final boolean fixedFinalCapture = Boolean.getBoolean("ice.bench.fixedFinalCapture");
    private String pendingFixedCapture;
    private int fixedCaptureFrames;
    private double captureX, captureY, captureZ;
    private float captureYaw, capturePitch;
    private final boolean groundStart = Boolean.getBoolean("ice.bench.groundStart");
    // Mirrored order controls slow drift. Bits disable software occlusion / primitive BFS.
    private final int[] ablationMasks = entityAblation ? new int[] {0, 32, 32, 0}
        : modelAblation ? new int[] {0, 16, 16, 0}
        : terrainAblation ? new int[] {0, 8, 8, 0}
        : runtimeAblation && Boolean.getBoolean("ice.bench.keyToggleStress") ? new int[] {0, 4, 0, 4, 0, 4, 0}
        : runtimeAblation ? new int[] {0, 4, 4, 0}
        : new int[] {0, 1, 3, 2, 2, 3, 1, 0};

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        File game = Minecraft.getMinecraft().gameDir;
        if (!new File(game, "ICE_ISOLATED_BENCHMARK.marker").isFile())
            throw new IllegalStateException("Benchmark fixture refuses a non-isolated game directory");
        if(fixedCameraPath && (!fixedNaturalCamera || !stableCreative || survival || gameplay.enabled || anyAblation))
            throw new IllegalStateException("Fixed camera path requires the ordinary fixed-view creative fixture");
        MinecraftForge.EVENT_BUS.register(this);
        System.out.println("ICE_BENCH fixture ready; isolated saves only");
    }

    @SubscribeEvent public void client(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (pendingKeyPause != null) {
            try {
                Class<?> runtime = Class.forName("dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime");
                boolean paused = (Boolean) runtime.getMethod("isRuntimePaused").invoke(runtime.getField("INSTANCE").get(null));
                if (paused != pendingKeyPause.booleanValue()) throw new IllegalStateException("F6 key did not change pause state");
                if (initialChunks != null) {
                    Object[] chunks = (Object[]) Class.forName("dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor")
                        .getMethod("ice$renderChunks").invoke(mc.renderGlobal);
                    if (chunks != initialChunks) throw new IllegalStateException("F6 replaced the chunk array");
                    for (int i = 0; i < chunks.length; i++)
                        if (chunks[i] != initialChunkSnapshot[i]) throw new IllegalStateException("F6 replaced chunk " + i);
                    System.out.println("ICE_BENCH retained_chunks=" + chunks.length);
                }
                System.out.println("ICE_BENCH key_toggle_completed=" + paused);
                pendingKeyPause = null;
            } catch (Exception failure) { throw new IllegalStateException("F6 key verification failed", failure); }
        }
        if (pendingRebuildPause != null && --pendingRebuildTicks == 0) {
            net.minecraft.client.settings.KeyBinding.onTick(org.lwjgl.input.Keyboard.KEY_F6);
            pendingKeyPause = pendingRebuildPause;
            pendingRebuildPause = null;
            System.out.println("ICE_BENCH stressed_key_toggle_requested=" + pendingKeyPause
                + " terrain=" + mc.renderGlobal.getDebugInfoRenders());
        }
        if (pendingDiagnostic) {
            applyAblation(ablationMasks[scene]);
            pendingDiagnostic = false;
            sceneStart = System.nanoTime() + 5000000000L;
        }
        if (groundStart && survivalApplied && !walkingAbilitiesApplied && mc.player != null) {
            mc.player.capabilities.isFlying = false;
            mc.player.sendPlayerAbilities();
            walkingAbilitiesApplied = true;
        }
        if (!launched && mc.currentScreen instanceof GuiMainMenu) {
            launched = true;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.enableVsync = false;
            mc.gameSettings.limitFramerate = 260; // Minecraft's uncapped setting.
            mc.getSession().setProperties(new com.mojang.authlib.properties.PropertyMap());
            mc.launchIntegratedServer(System.getProperty("ice.bench.world", "icebench"), "ICE isolated benchmark", null);
            return;
        }
        if (mc.world == null || mc.player == null) return;
        if (connected == 0 && (mc.player.ticksExisted < 60 || mc.currentScreen != null
            || stableCreative && !stableCreativeApplied
            || gameplay.enabled && (!gameplay.ready || Math.abs(mc.player.posY - 80) > .25))) return;
        long now = System.nanoTime();
        if (connected == 0) {
            if (Boolean.getBoolean("ice.bench.requireChunkReuse")) {
                try {
                    initialChunks = (Object[]) Class.forName("dev.rlcraft.ice.optimizer.compat.chunk.TerrainVisibilityAccessor")
                        .getMethod("ice$renderChunks").invoke(mc.renderGlobal);
                    initialChunkSnapshot = initialChunks.clone();
                } catch (Exception failure) { throw new IllegalStateException("Chunk reuse probe setup failed", failure); }
            }
            originalYaw = groundStart || gameplay.enabled ? 180F : mc.player.rotationYaw;
            originalPitch = groundStart || gameplay.enabled ? 8F : mc.player.rotationPitch;
            if (fixedNaturalCamera && !gameplay.enabled) {
                originalYaw = requestedYaw;
                originalPitch = requestedPitch;
            }
            mc.displayGuiScreen(null);
            mc.setIngameNotInFocus();
            if (survival || walk || anyAblation || gameplay.enabled) mc.player.movementInput = new net.minecraft.util.MovementInput() {
                @Override public void updatePlayerMoveState() {
                    moveStrafe = 0; jump = false;
                    // Hold the ordinary sneak control on the natural ledge.
                    // Physics, knockback, AI and damage remain in the game.
                    sneak = survival && !gameplay.enabled;
                    moveForward = finished || fixedCameraPath ? 0 : gameplay.enabled ? gameplay.forward() : walk && scene == 2 && sceneStart != 0
                        ? (((System.nanoTime() - sceneStart) / 1000000000L & 3L) < 2L ? .15F : -.15F) : 0;
                }
            };
            gpuSupported = GLContext.getCapabilities().OpenGL33;
            if (gpuSupported) for (int slot = 0; slot < queryStart.length; slot++) {
                queryStart[slot] = GL15.glGenQueries(); queryEnd[slot] = GL15.glGenQueries();
            }
            Display.setTitle("ICE isolated benchmark - " + System.getProperty("ice.bench.arm", "unknown"));
            System.out.println("ICE_BENCH world connected; warm_seconds=" + warmSeconds
                + " sample_seconds=" + sampleSeconds + " resolution=" + mc.displayWidth + "x" + mc.displayHeight
                + " render_distance=" + mc.gameSettings.renderDistanceChunks);
            if (Boolean.getBoolean("ice.bench.emptyUploadProbe")) EmptyTerrainUploadProbe.run();
            connected = now;
        }
        // Deterministic looking around inside the loaded area; no teleporting,
        // entity suppression, tick skipping, or reduced draw distances.
        if (sceneStart == 0 || Boolean.getBoolean("ice.bench.keyToggleRotation") || !gameplay.enabled && !anyAblation && scene == 1) {
            double seconds = (now - (sceneStart == 0 ? connected : sceneStart)) / 1e9;
            mc.player.prevRotationYaw = mc.player.rotationYaw;
            mc.player.rotationYaw = originalYaw + (float) (seconds * 18.0);
            mc.player.prevRotationPitch = mc.player.rotationPitch;
            mc.player.rotationPitch = originalPitch;
        }
        if (gameplay.enabled) gameplay.client(mc);
        if (Mouse.isGrabbed()) Mouse.setGrabbed(false);
    }

    @SubscribeEvent public void render(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START && Boolean.getBoolean("ice.bench.matchVanillaEntityDistance")) {
            // Match vanilla's quality at the selected chunk distance. Nothirium
            // cancels setupTerrain before vanilla normally writes this value.
            net.minecraft.entity.Entity.setRenderDistanceWeight(net.minecraft.util.math.MathHelper.clamp(
                Minecraft.getMinecraft().gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D));
        }
        if (!finished && connected != 0 && fixedNaturalCamera && !gameplay.enabled
            && event.phase == TickEvent.Phase.START && Minecraft.getMinecraft().player != null) {
            // The starting mouse delta can arrive after login or during rendering.
            // Control the requested view at the render boundary on both arms.
            net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().player;
            float yaw = originalYaw;
            if (sceneStart == 0 || Boolean.getBoolean("ice.bench.keyToggleRotation") || !anyAblation && scene == 1) {
                yaw += (float) ((System.nanoTime() - (sceneStart == 0 ? connected : sceneStart)) / 1e9 * 18.0);
            }
            player.rotationYaw = player.prevRotationYaw = yaw;
            player.rotationPitch = player.prevRotationPitch = originalPitch;
            if(fixedCameraPath) renderCamera.update(Minecraft.getMinecraft(),yaw,originalPitch,originalYaw,
                sceneStart==0?0:(System.nanoTime()-sceneStart)/1e9,walk && scene==2 && sceneStart!=0);
        }
        if (finished) {
            if (pendingFixedCapture != null) {
                Minecraft mc = Minecraft.getMinecraft();
                if (event.phase == TickEvent.Phase.START) {
                    // Only after timing and scenario accounting have closed.
                    // Equal endpoint yaw alone is insufficient when a route
                    // corner leaves a large previous/current interpolation.
                    mc.player.posX = mc.player.prevPosX = mc.player.lastTickPosX = captureX;
                    mc.player.posY = mc.player.prevPosY = mc.player.lastTickPosY = captureY;
                    mc.player.posZ = mc.player.prevPosZ = mc.player.lastTickPosZ = captureZ;
                    mc.player.rotationYaw = mc.player.prevRotationYaw = captureYaw;
                    mc.player.rotationPitch = mc.player.prevRotationPitch = capturePitch;
                } else if (++fixedCaptureFrames >= 2) {
                    net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.gameDir,
                        pendingFixedCapture + "-fixed-view.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    File proof = new File(new File(mc.gameDir, "ice-benchmark"), pendingFixedCapture + "-fixed-view.txt");
                    try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(proof), StandardCharsets.UTF_8))) {
                        out.println("capture_after_measurement=true");
                        out.println("render_frames_after_measurement=" + fixedCaptureFrames);
                        out.println("requested_position=" + captureX + "," + captureY + "," + captureZ);
                        out.println("requested_camera=" + captureYaw + "," + capturePitch);
                        out.println("position=" + mc.player.posX + "," + mc.player.posY + "," + mc.player.posZ);
                        out.println("previous_position=" + mc.player.prevPosX + "," + mc.player.prevPosY + "," + mc.player.prevPosZ);
                        out.println("last_tick_position=" + mc.player.lastTickPosX + "," + mc.player.lastTickPosY + "," + mc.player.lastTickPosZ);
                        out.println("camera=" + mc.player.rotationYaw + "," + mc.player.rotationPitch);
                        out.println("previous_camera=" + mc.player.prevRotationYaw + "," + mc.player.prevRotationPitch);
                        out.println("requested_pose_preserved=" + (mc.player.posX == captureX && mc.player.posY == captureY && mc.player.posZ == captureZ
                            && mc.player.prevPosX == captureX && mc.player.prevPosY == captureY && mc.player.prevPosZ == captureZ
                            && mc.player.lastTickPosX == captureX && mc.player.lastTickPosY == captureY && mc.player.lastTickPosZ == captureZ
                            && mc.player.rotationYaw == captureYaw && mc.player.rotationPitch == capturePitch
                            && mc.player.prevRotationYaw == captureYaw && mc.player.prevRotationPitch == capturePitch));
                        out.println("partial_tick=" + event.renderTickTime);
                        writeTerrainBackend(out);
                    } catch (IOException failure) { throw new IllegalStateException(failure); }
                    pendingFixedCapture = null;
                    System.out.println("ICE_BENCH fixed final view captured after measurement; COMPLETE");
                    System.out.println("ICE_BENCH COMPLETE"); mc.shutdown();
                }
                return;
            }
            if (shutdownAfterCapture != 0 && System.nanoTime() >= shutdownAfterCapture) {
                shutdownAfterCapture = 0; TerrainSceneCapture.disarm();
                System.out.println("ICE_BENCH COMPLETE"); Minecraft.getMinecraft().shutdown();
            }
            return;
        }
        if (connected == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) { abort("world_disconnected"); return; }
        if (mc.player.isDead || mc.currentScreen != null) { abort("player_dead_or_overlay"); return; }
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            if (sceneStart == 0) {
                if (now - connected < warmSeconds * 1000000000L) return;
                if (groundStart && displacedFromGround(mc)) { abort("ground_displaced_during_warmup"); return; }
                beginScene(now);
            }
            if (pendingDiagnostic || now < sceneStart) return; // Switch only between frames.
            if (sectionPending) {
                if (Boolean.getBoolean("ice.bench.captureTerrain")) TerrainSceneCapture.disarm();
                sectionClock("reset"); sectionPending = false;
            }
            if (groundStart && displacedFromGround(mc)) { abort("ground_displaced_during_measurement"); return; }
            if (previousFrame != 0 && count < frames.length) {
                frames[count] = now - previousFrame;
                count++;
            }
            previousFrame = now; renderStart = now;
            beginGpu();
            int entities = mc.world.loadedEntityList.size();
            minEntities = Math.min(minEntities, entities); maxEntities = Math.max(maxEntities, entities);
            allActive &= Display.isActive();
        } else if (!pendingDiagnostic && sceneStart != 0 && now >= sceneStart) {
            endGpu();
            // Render durations have their own sample slot, paired with the
            // following start-to-start wall frame interval.
            if (count < renders.length) renders[count] = now - renderStart;
            if (now - sceneStart >= sampleSeconds * 1000000000L) {
                writeScene();
                if (gameplay.enabled && !gameplay.validActivity()) { abort("insufficient_gameplay_activity"); return; }
                // Capture only after timing closes. This is evidence from the
                // isolated game framebuffer, never a desktop screenshot.
                if (fixedFinalCapture && gameplay.enabled) {
                    pendingFixedCapture = sceneName();
                    captureX = mc.player.posX; captureY = mc.player.posY; captureZ = mc.player.posZ;
                    captureYaw = mc.player.rotationYaw; capturePitch = mc.player.rotationPitch;
                } else net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.gameDir,
                    sceneName() + "-view.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                scene++;
                if (scene == (gameplay.enabled ? 1 : anyAblation ? ablationMasks.length : walk ? 3 : 2)) {
                    if (gpuSupported) for (int slot = 0; slot < queryStart.length; slot++) {
                        GL15.glDeleteQueries(queryStart[slot]); GL15.glDeleteQueries(queryEnd[slot]);
                    }
                    finished = true;
                    if (Boolean.getBoolean("ice.bench.captureTerrain")) {
                        if (!profileSections || anyAblation) throw new IllegalStateException("Capture requires plain section diagnostic");
                        TerrainSceneCapture.arm(); shutdownAfterCapture = System.nanoTime() + 10000000000L;
                    } else if (pendingFixedCapture == null) { System.out.println("ICE_BENCH COMPLETE"); mc.shutdown(); }
                } else beginScene(System.nanoTime());
            }
        }
    }

    private boolean displacedFromGround(Minecraft mc) {
        double dx = mc.player.posX - 511.5, dz = mc.player.posZ - 414.5;
        return dx * dx + dz * dz > 4D || Math.abs(mc.player.posY - 68D) > .25D;
    }

    @SubscribeEvent public void hideCameraPlayer(net.minecraftforge.client.event.RenderPlayerEvent.Pre event) {
        // The detached measurement camera must preserve first-person self-body exclusion.
        if (fixedCameraPath && event.getEntityPlayer() == Minecraft.getMinecraft().player)
            event.setCanceled(true);
    }

    @SubscribeEvent public void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        gameplay.hurt(event);
    }
    @SubscribeEvent public void died(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        gameplay.died(event);
    }

    @SubscribeEvent public void server(TickEvent.ServerTickEvent event) {
        if (stableCreative && !stableCreativeApplied && event.phase == TickEvent.Phase.START) {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null && server.getPlayerList() != null && !server.getPlayerList().getPlayers().isEmpty()) {
                net.minecraft.entity.player.EntityPlayerMP player = server.getPlayerList().getPlayers().get(0);
                if (player.ticksExisted >= 40) {
                    if (!player.capabilities.isCreativeMode) throw new IllegalStateException("Stable creative fixture requires the creative source save");
                    player.clearActivePotions();
                    player.capabilities.isFlying = true;
                    player.sendPlayerAbilities();
                    player.world.setWorldTime(6000L);
                    player.world.getWorldInfo().setRaining(false);
                    player.world.getWorldInfo().setThundering(false);
                    player.world.getWorldInfo().setCleanWeatherTime(12000);
                    stableCreativeApplied = true;
                    System.out.println("ICE_BENCH stable_creative_setup=clear_potions_midday_clear_weather");
                }
            }
        }
        if (gameplay.enabled && event.phase == TickEvent.Phase.START) {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null && server.getPlayerList() != null && !server.getPlayerList().getPlayers().isEmpty()) {
                net.minecraft.entity.player.EntityPlayerMP player = server.getPlayerList().getPlayers().get(0);
                if (player.ticksExisted >= 40) gameplay.server(player);
            }
        }
        if (survival && connected != 0 && !survivalApplied && event.phase == TickEvent.Phase.START) {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null && server.getPlayerList() != null && !server.getPlayerList().getPlayers().isEmpty()) {
                net.minecraft.entity.player.EntityPlayerMP player = server.getPlayerList().getPlayers().get(0);
                player.setGameType(net.minecraft.world.GameType.SURVIVAL);
                if (groundStart) {
                    placeOnBenchmarkGround(player);
                    player.capabilities.isFlying = false;
                    player.sendPlayerAbilities();
                }
                survivalApplied = true;
            }
        }
        if (event.phase == TickEvent.Phase.START) tickStart = System.nanoTime();
        else if (sceneStart != 0) synchronized (ticks) {
            if (tickCount < ticks.length) ticks[tickCount++] = System.nanoTime() - tickStart;
        }
    }

    private void placeOnBenchmarkGround(net.minecraft.entity.player.EntityPlayerMP player) {
        // One reproducible setup move in the cloned save, before warmup. Avoid
        // measuring the source creative player's fall against a ravine wall.
        net.minecraft.world.World world = player.world;
        // Verified directly from DregoraRL4's Anvil data: a natural 3x3 ledge,
        // only three blocks from the source camera, with an open northward view.
        net.minecraft.util.math.BlockPos selected = new net.minecraft.util.math.BlockPos(511, 68, 414);
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            net.minecraft.util.math.BlockPos floor = selected.add(ox, -1, oz);
            net.minecraft.block.state.IBlockState state = world.getBlockState(floor);
            net.minecraft.block.material.Material material = state.getMaterial();
            boolean flat = state.isFullCube() && (material == net.minecraft.block.material.Material.GROUND
                || material == net.minecraft.block.material.Material.GRASS || material == net.minecraft.block.material.Material.ROCK);
            for (int height = 1; height <= 2; height++) {
                net.minecraft.util.math.BlockPos air = floor.up(height);
                net.minecraft.block.state.IBlockState above = world.getBlockState(air);
                flat &= !above.getMaterial().isLiquid()
                    && above.getCollisionBoundingBox(world, air) == net.minecraft.block.Block.NULL_AABB;
            }
            if (!flat) throw new IllegalStateException("Benchmark ground no longer matches the verified snapshot: " + floor
                + " floor=" + state + " above=" + world.getBlockState(floor.up()) + "/" + world.getBlockState(floor.up(2)));
        }
        player.motionX = player.motionY = player.motionZ = 0;
        player.fallDistance = 0;
        player.connection.setPlayerLocation(selected.getX() + .5, selected.getY(), selected.getZ() + .5, 180F, 8F);
        System.out.println("ICE_BENCH ground_start=" + selected.getX() + "," + selected.getY() + "," + selected.getZ());
    }

    private void beginScene(long now) {
        // Start at the first measured frame, excluding ablation settling frames.
        sectionPending = profileSections;
        if (gameplay.enabled) gameplay.begin(Minecraft.getMinecraft());
        pendingDiagnostic = anyAblation;
        sceneStart = now + (pendingDiagnostic ? 5000000000L : 0L); count = gpuCount = 0; previousFrame = 0;
        synchronized (ticks) { tickCount = 0; }
        if (anyAblation || scene != 1) {
            Minecraft.getMinecraft().player.rotationYaw = originalYaw;
            Minecraft.getMinecraft().player.prevRotationYaw = originalYaw;
        }
        minEntities = Integer.MAX_VALUE; maxEntities = 0; allActive = true;
        System.out.println("ICE_BENCH SCENE " + sceneName());
    }
    private String sceneName() {
        if (gameplay.enabled) return gameplay.name();
        return anyAblation ? "ablation_" + scene + "_mask_" + ablationMasks[scene]
            : scene == 0 ? "loaded_static" : scene == 1 ? "loaded_camera_rotation"
                : survival ? "loaded_sneak_walk" : "loaded_local_movement";
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyAblation(int mask) {
        try {
            if (entityAblation) {
                Class<?> clock = Class.forName("dev.rlcraft.ice.benchmark.timing.RenderSectionClock", true, ClassLoader.getSystemClassLoader());
                boolean enabled = (mask & 32) == 0;
                clock.getMethod("setEntityAdapter", boolean.class).invoke(null, enabled);
                if (((Boolean) clock.getMethod("getEntityAdapter").invoke(null)) != enabled) throw new IllegalStateException("Entity gate rejected");
                System.out.println("ICE_BENCH diagnostic_entity_adapter=" + enabled);
                return;
            }
            if (runtimeAblation) {
                Class<?> runtime = Class.forName("dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime");
                Object instance = runtime.getField("INSTANCE").get(null);
                if (Boolean.getBoolean("ice.bench.keyToggle")) {
                    boolean wanted = (mask & 4) != 0;
                    boolean before = (Boolean) runtime.getMethod("isRuntimePaused").invoke(instance);
                    if (before != wanted) {
                        if (Boolean.getBoolean("ice.bench.keyToggleStress")) {
                            Object[] chunks = (Object[]) Minecraft.getMinecraft().renderGlobal.getClass()
                                .getMethod("ice$renderChunks").invoke(Minecraft.getMinecraft().renderGlobal);
                            for (Object chunk : chunks) if (chunk != null)
                                ((net.minecraft.client.renderer.chunk.RenderChunk) chunk).setNeedsUpdate(false);
                            pendingRebuildPause = Boolean.valueOf(wanted);
                            pendingRebuildTicks = 3;
                            System.out.println("ICE_BENCH forced_chunk_rebuilds=" + chunks.length);
                            return;
                        }
                        net.minecraft.client.settings.KeyBinding.onTick(org.lwjgl.input.Keyboard.KEY_F6);
                        pendingKeyPause = Boolean.valueOf(wanted);
                        System.out.println("ICE_BENCH key_toggle_requested=" + wanted);
                    } else System.out.println("ICE_BENCH key_toggle_already=" + wanted);
                    return;
                }
                runtime.getMethod("setRuntimePaused", boolean.class).invoke(instance, (mask & 4) != 0);
                boolean paused = (Boolean) runtime.getMethod("isRuntimePaused").invoke(instance);
                if (paused != ((mask & 4) != 0)) throw new IllegalStateException("Pause state rejected");
                System.out.println("ICE_BENCH diagnostic_runtime_paused=" + paused);
                return;
            }
            Class<?> config = Class.forName("dev.rlcraft.ice.optimizer.OptimizerConfig");
            Object settings = config.getField("settings").get(null);
            if (modelAblation) {
                boolean enabled = (mask & 16) == 0;
                Class<?> runtimeClass = Class.forName("dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime");
                Object runtime = runtimeClass.getMethod("modernRenderer").invoke(runtimeClass.getField("INSTANCE").get(null));
                java.lang.reflect.Field graphField = runtime.getClass().getDeclaredField("components");
                graphField.setAccessible(true);
                Object graph = graphField.get(runtime);
                java.lang.reflect.Field cacheField = graph.getClass().getDeclaredField("modelMeshes");
                cacheField.setAccessible(true);
                Object cache = cacheField.get(graph);
                cache.getClass().getMethod("configurePoseBatching", boolean.class).invoke(cache, enabled);
                java.lang.reflect.Field enabledField = cache.getClass().getDeclaredField("poseBatching");
                enabledField.setAccessible(true);
                if (enabledField.getBoolean(cache) != enabled) throw new IllegalStateException("Pose switch rejected");
                System.out.println("ICE_BENCH diagnostic_model_pose_batching=" + enabled);
                return;
            }
            if (terrainAblation) {
                settings.getClass().getField("terrainDirectionalCulling").setBoolean(settings, (mask & 8) == 0);
                System.out.println("ICE_BENCH diagnostic_directional_culling=" + ((mask & 8) == 0));
                return;
            }
            settings.getClass().getField("terrainSoftwareOcclusion").setBoolean(settings, (mask & 1) == 0);
            Class<? extends Enum> modules = (Class<? extends Enum>) Class.forName("dev.rlcraft.ice.optimizer.OptimizationModule");
            Object module = Enum.valueOf(modules, "MODERN_VISIBILITY_GRID");
            Class<?> registry = Class.forName("dev.rlcraft.ice.optimizer.OptimizerRegistry");
            Object breaker = registry.getMethod("breaker", modules).invoke(null, module);
            breaker.getClass().getMethod("configure", boolean.class, int.class).invoke(breaker, (mask & 2) == 0, 3);
            System.out.println("ICE_BENCH diagnostic_mask=" + mask);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Diagnostic switch failed", failure); }
    }
    private void writeScene() {
        Minecraft mc = Minecraft.getMinecraft();
        File output = new File(mc.gameDir, "ice-benchmark"); output.mkdirs();
        if (profileSections) try {
            java.nio.file.Files.write(new File(output, sceneName() + "-sections.csv").toPath(),
                ((String) sectionClock("stop")).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        try (PrintWriter csv = new PrintWriter(new OutputStreamWriter(
            new FileOutputStream(new File(output, sceneName() + ".csv")), StandardCharsets.UTF_8))) {
            csv.println("frame_ns,render_ns");
            long total = 0;
            for (int i = 0; i < count; i++) { csv.println(frames[i] + "," + renders[i]); total += frames[i]; }
            long[] sorted = Arrays.copyOf(frames, count); Arrays.sort(sorted);
            long[] gpu = Arrays.copyOf(gpuTimes, gpuCount); Arrays.sort(gpu);
            long[] server;
            synchronized (ticks) { server = Arrays.copyOf(ticks, tickCount); }
            Arrays.sort(server);
            try (PrintWriter summary = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(new File(output, sceneName() + "-summary.txt")), StandardCharsets.UTF_8))) {
                summary.println("arm=" + System.getProperty("ice.bench.arm", "unknown"));
                summary.println("scene=" + sceneName()); summary.println("frames=" + count);
                summary.println("fps=" + (count * 1e9 / total));
                summary.println("frame_p50_ms=" + percentile(sorted, .50));
                summary.println("frame_p95_ms=" + percentile(sorted, .95));
                summary.println("frame_p99_ms=" + percentile(sorted, .99));
                summary.println("server_p95_ms=" + percentile(server, .95));
                summary.println("gpu_samples=" + gpuCount);
                summary.println("gpu_p50_ms=" + percentile(gpu, .50));
                summary.println("gpu_p95_ms=" + percentile(gpu, .95));
                summary.println("entities_min=" + minEntities); summary.println("entities_max=" + maxEntities);
                summary.println("entity_render_distance_weight=" + net.minecraft.entity.Entity.getRenderDistanceWeight());
                summary.println("entity_distance_policy=" + (Boolean.getBoolean("ice.bench.matchVanillaEntityDistance") ? "vanilla" : "renderer_default"));
                summary.println("display_active_all_frames=" + allActive);
                summary.println("terrain=" + mc.renderGlobal.getDebugInfoRenders());
                writeTerrainBackend(summary);
                summary.println("position=" + mc.player.posX + "," + mc.player.posY + "," + mc.player.posZ);
                summary.println("dimension=" + mc.player.dimension);
                summary.println("camera=" + mc.player.rotationYaw + "," + mc.player.rotationPitch);
                if(fixedCameraPath) renderCamera.summary(summary);
                summary.println("fov=" + mc.gameSettings.fovSetting);
                summary.println("creative=" + mc.player.capabilities.isCreativeMode);
                summary.println("sneaking=" + mc.player.isSneaking());
                summary.println("flying=" + mc.player.capabilities.isFlying);
                summary.println("world_time=" + mc.world.getWorldTime());
                summary.println("pilot_only=" + (anyAblation || profileSections || warmSeconds < 60 || sampleSeconds < 60));
                if (gameplay.enabled) gameplay.summary(summary);
            }
            try {
                Class<?> diagnostics = Class.forName("dev.rlcraft.ice.optimizer.client.ModernRendererDiagnostics");
                String report = (String) diagnostics.getMethod("report").invoke(null);
                java.nio.file.Files.write(new File(output, sceneName() + "-renderer.txt").toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (ClassNotFoundException absent) { /* The no-ICE control has no diagnostics class. */ }
            catch (ReflectiveOperationException unavailable) { System.out.println("ICE_BENCH diagnostics_unavailable=" + unavailable); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    /** Read the replacement's public counters only after timing has closed. */
    private static void writeTerrainBackend(PrintWriter out) {
        if (!net.minecraftforge.fml.common.Loader.isModLoaded("nothirium")) {
            out.println("terrain_backend_observation=legacy_debug_counters");
            return;
        }
        try {
            Class<?> manager = Class.forName("meldexun.nothirium.mc.renderer.ChunkRenderManager");
            Object renderer = manager.getMethod("getRenderer").invoke(null);
            if (renderer == null) throw new IllegalStateException("Nothirium renderer absent");
            out.println("terrain_backend_class=" + renderer.getClass().getName());
            out.println("terrain_backend_name=" + renderer.getClass().getMethod("name").invoke(renderer));
            out.println("terrain_backend_engine=" + renderer.getClass().getMethod("getRenderEngine").invoke(renderer));
            out.println("terrain_backend_sections=" + manager.getMethod("renderedSections").invoke(null));
            out.println("terrain_backend_total_sections=" + manager.getMethod("totalSections").invoke(null));
            Class<?> passType = Class.forName("meldexun.nothirium.api.renderer.chunk.ChunkRenderPass");
            java.lang.reflect.Method count = manager.getMethod("renderedSections", passType);
            for (Object pass : passType.getEnumConstants())
                out.println("terrain_backend_sections_" + ((Enum<?>) pass).name() + "=" + count.invoke(null, pass));
            out.println("terrain_backend_observation=available");
        } catch (ReflectiveOperationException | IllegalStateException failure) {
            // A missing diagnostic API is evidence to report, not a zero count.
            out.println("terrain_backend_observation=unavailable:" + failure);
        }
    }
    private void beginGpu() {
        activeQuery = -1;
        if (!gpuSupported) return;
        int slot = queryCursor; queryCursor = (queryCursor + 1) % queryStart.length;
        if (queryPending[slot]) {
            if (GL15.glGetQueryObjecti(queryEnd[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
            long elapsed = GL33.glGetQueryObjectui64(queryEnd[slot], GL15.GL_QUERY_RESULT)
                - GL33.glGetQueryObjectui64(queryStart[slot], GL15.GL_QUERY_RESULT);
            if (queryScene[slot] == scene && elapsed >= 0 && gpuCount < gpuTimes.length) gpuTimes[gpuCount++] = elapsed;
            queryPending[slot] = false;
        }
        GL33.glQueryCounter(queryStart[slot], GL33.GL_TIMESTAMP);
        queryScene[slot] = scene; activeQuery = slot;
    }
    private void endGpu() {
        if (activeQuery < 0) return;
        GL33.glQueryCounter(queryEnd[activeQuery], GL33.GL_TIMESTAMP);
        queryPending[activeQuery] = true; activeQuery = -1;
    }
    private static double percentile(long[] values, double p) {
        return values.length == 0 ? Double.NaN : values[(int)Math.ceil(values.length * p) - 1] / 1e6;
    }
    private Object sectionClock(String method) {
        try {
            return Class.forName("dev.rlcraft.ice.benchmark.timing.RenderSectionClock", true,
                ClassLoader.getSystemClassLoader()).getMethod(method).invoke(null);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Section timing agent missing", failure); }
    }
    private void abort(String reason) {
        Minecraft mc = Minecraft.getMinecraft();
        if (gameplay.enabled) try {
            File output = new File(mc.gameDir, "ice-benchmark"); output.mkdirs();
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(new File(output,"invalid-gameplay.txt")), StandardCharsets.UTF_8))) {
                out.println("invalid_reason="+reason); out.println("accepted_performance_result=false");
                gameplay.summary(out);
            }
        } catch (IOException failure) { System.out.println("ICE_BENCH invalid_diagnostic_failed="+failure); }
        finished = true; System.out.println("ICE_BENCH INVALID " + reason
            + (mc.player == null ? "" : " position=" + mc.player.posX + "," + mc.player.posY + "," + mc.player.posZ
                + " flying=" + mc.player.capabilities.isFlying)); mc.shutdown();
    }
}

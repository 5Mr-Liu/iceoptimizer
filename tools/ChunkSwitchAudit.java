package dev.rlcraft.ice.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.input.Keyboard;

/** External correctness fixture; never included in the optimizer artifact. */
final class ChunkSwitchAudit {
    private static final String PACK = "Complementary v4.6 - RLCraft Edit v1.0";
    private static final List<String> STEPS = steps();
    private static int cursor, delay = 100;
    private static Boolean pendingPause;
    private static boolean complete;
    private static boolean captureSettled;

    private static List<String> steps() {
        List<String> result = new ArrayList<>();
        result.add("off");
        cycles(result, 4);
        result.add("capture-no-shader-f6");
        result.add("on");
        cycles(result, 4);
        result.add("capture-shader-f6");
        // Change the vertex layout while the original renderer owns geometry,
        // then resume ICE. Exercise both directions and alternating ownership.
        for (int i = 0; i < 3; i++) {
            result.add("rebuild"); result.add("key"); result.add("off");
            result.add("key"); result.add("rebuild"); result.add("key");
            result.add("on"); result.add("key");
        }
        result.add("capture-crossed-switches");
        for (int i = 0; i < 3; i++) {
            result.add("rebuild"); result.add("off");
            result.add("rebuild"); result.add("on");
        }
        result.add("capture-final"); result.add("complete");
        return result;
    }

    private static void cycles(List<String> result, int count) {
        for (int i = 0; i < count; i++) {
            result.add("rebuild"); result.add("key");
            result.add("rebuild"); result.add("key");
        }
    }

    static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("ice.bench.switchAudit") || complete) return;
        try {
            if (!new File(mc.gameDir, "ICE_ISOLATED_BENCHMARK.marker").isFile())
                throw new IllegalStateException("Switch audit requires an isolated game");
            if (mc.currentScreen != null) throw new IllegalStateException("Unexpected screen during switch audit");
            Class<?> runtime = Class.forName("dev.rlcraft.ice.optimizer.client.ClientOptimizerRuntime");
            Object instance = runtime.getField("INSTANCE").get(null);
            boolean paused = (Boolean) runtime.getMethod("isRuntimePaused").invoke(instance);
            if (pendingPause != null) {
                if (paused != pendingPause) throw new IllegalStateException("F6 did not reach requested state");
                System.out.println("ICE_SWITCH_AUDIT F6_COMPLETED paused=" + paused);
                pendingPause = null;
            }
            if (--delay > 0) return;
            if (STEPS.get(cursor).startsWith("capture-") && !captureSettled) {
                captureSettled = true;
                delay = 200;
                System.out.println("ICE_SWITCH_AUDIT settling before screenshot");
                return;
            }
            String step = STEPS.get(cursor++);
            captureSettled = false;
            System.out.println("ICE_SWITCH_AUDIT START step=" + cursor + "/" + STEPS.size()
                + " action=" + step + " paused=" + paused + " terrain=" + mc.renderGlobal.getDebugInfoRenders());
            long started = System.nanoTime();
            if (step.equals("rebuild")) {
                Object[] chunks = (Object[]) mc.renderGlobal.getClass().getMethod("ice$renderChunks").invoke(mc.renderGlobal);
                for (Object chunk : chunks) if (chunk != null) ((RenderChunk) chunk).setNeedsUpdate(false);
                delay = 3;
            } else if (step.equals("key")) {
                pendingPause = !paused;
                KeyBinding.onTick(Keyboard.KEY_F6);
                delay = 20;
            } else if (step.equals("on") || step.equals("off")) {
                Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
                // Exact operational order of OptiFine G5 GuiSlotShaders.selectIndex.
                shaders.getMethod("setShaderPack", String.class).invoke(null, step.equals("on") ? PACK : "OFF");
                shaders.getMethod("uninit").invoke(null);
                if (shaders.getField("shaderPackLoaded").getBoolean(null) != step.equals("on"))
                    throw new IllegalStateException("Shader selection was not applied");
                delay = 40;
            } else if (step.startsWith("capture-")) {
                ScreenShotHelper.saveScreenshot(mc.gameDir, "switch-audit-" + step + ".png",
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                delay = 40;
            } else if (step.equals("complete")) {
                if (paused) throw new IllegalStateException("Final state must have ICE enabled");
                complete = true;
                System.out.println("ICE_SWITCH_AUDIT COMPLETE steps=" + STEPS.size());
                mc.shutdown();
            } else throw new IllegalStateException("Unknown switch audit step: " + step);
            System.out.println("ICE_SWITCH_AUDIT RETURN step=" + cursor + " elapsed_ms="
                + (System.nanoTime() - started) / 1000000L);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Switch audit invocation failed", failure);
        }
    }
}

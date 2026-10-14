import dev.rlcraft.ice.hooks.HudRenderBootstrap;
import dev.rlcraft.ice.optimizer.compat.hud.HudRenderBridge;
import java.util.Arrays;

/** Real core/runtime bridge, outside HUD; no OpenGL work or game FPS claim. */
public final class HudBarrierDispatchProbe {
    public static void main(String[] args) {
        if (!HudRenderBootstrap.install(HudRenderBridge.class)) throw new AssertionError("HUD bridge installation");
        for (int i = 0; i < 8; i++) measure();
        double[] times = new double[11];
        for (int i = 0; i < times.length; i++) times[i] = measure();
        Arrays.sort(times);
        System.out.println("empty_barrier_pair_ns=" + times[times.length / 2]);
        System.out.println("Local CPU dispatch only; not game FPS");
    }
    private static double measure() {
        long start = System.nanoTime();
        for (int i = 0; i < 2000000; i++) {
            HudRenderBootstrap.barrier();
            HudRenderBootstrap.textureBarrier(i & 127);
        }
        return (System.nanoTime() - start) / 2000000.0;
    }
}

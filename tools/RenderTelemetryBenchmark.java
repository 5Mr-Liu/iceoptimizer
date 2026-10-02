import com.sun.management.ThreadMXBean;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.frame.FrameStamp;
import dev.rlcraft.ice.optimizer.render.frame.RenderPass;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.telemetry.PassProfile;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderCounter;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Standalone Java 8 CPU/allocation benchmark; does not claim in-game FPS. */
public final class RenderTelemetryBenchmark {
    private static final int FRAMES = 240;
    private static final int DRAWS_PER_FRAME = 10000;
    private static final int ROUNDS = 7;
    private static volatile long checksum;

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 4; i++) run(bean);
        double[] nanos = new double[ROUNDS];
        double[] bytes = new double[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            double[] result = run(bean);
            nanos[i] = result[0];
            bytes[i] = result[1];
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT,
            "counter_ns_per_call=%.2f counter_bytes_per_call=%.4f calls_per_round=%d checksum=%d%n",
            nanos[ROUNDS / 2], bytes[ROUNDS / 2], FRAMES * DRAWS_PER_FRAME, checksum);
    }

    private static double[] run(ThreadMXBean bean) {
        ClientEpochs epochs = new ClientEpochs();
        CorrelatedRenderProfiler profiler = new CorrelatedRenderProfiler(120);
        long thread = Thread.currentThread().getId();
        long beforeBytes = bean.getThreadAllocatedBytes(thread);
        long beforeNanos = System.nanoTime();
        for (long frame = 1L; frame <= FRAMES; frame++) {
            FrameStamp stamp = new FrameStamp(frame, 1L, epochs.snapshot());
            for (int draw = 0; draw < DRAWS_PER_FRAME; draw++) {
                profiler.addCounter(stamp, (draw & 1) == 0
                        ? RenderPass.ENTITY_PASS_0 : RenderPass.TESR_PASS_0,
                    RenderBackendId.ICE_NATIVE, RenderCounter.DRAW, 1L);
            }
        }
        long elapsed = System.nanoTime() - beforeNanos;
        long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
        long total = 0L;
        for (PassProfile profile : profiler.snapshot().getProfiles().values()) {
            total += profile.getCounters().get(RenderCounter.DRAW).longValue();
        }
        if (total != 120L * DRAWS_PER_FRAME) throw new AssertionError("counter retention");
        checksum = total;
        double operations = (double) FRAMES * DRAWS_PER_FRAME;
        return new double[] {elapsed / operations, allocated / operations};
    }
}

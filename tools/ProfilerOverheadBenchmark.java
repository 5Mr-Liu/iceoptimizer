import com.sun.management.ThreadMXBean;
import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerEngine;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.core.ProfilerStatus;
import dev.rlcraft.ice.profiler.jvm.JvmSnapshot;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.metrics.WorldGauge;
import dev.rlcraft.ice.profiler.probe.ProbeMetric;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadSampler;
import dev.rlcraft.ice.profiler.session.RecordingSession;
import dev.rlcraft.ice.profiler.session.ReportExporter;
import dev.rlcraft.ice.profiler.session.SessionManager;
import dev.rlcraft.ice.profiler.stats.DistributionSnapshot;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

/** Runs against old/new classpaths; it does not launch Minecraft or claim FPS gains. */
public final class ProfilerOverheadBenchmark {
    private static volatile long blackhole;
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int OPERATIONS = 25000;

    public static void main(String[] arguments) throws Throwable {
        BEAN.setThreadAllocatedMemoryEnabled(true);
        IceConfig.capture.autoCapture = true;
        final TriggerEngine engine = new TriggerEngine(new TriggerEngine.Sink() {
            @Override public void onTrigger(HitchTrigger trigger) { blackhole += trigger.getDurationNanos(); }
        });
        final long[] observations = new long[997];
        Random random = new Random(109L);
        for (int index = 0; index < observations.length; index++) observations[index] = 8_000_000L + random.nextInt(12_000_000);
        measure("normal_frame_detection", new Work() {
            @Override public void run(int index) { engine.clientFrame(observations[index % observations.length]); }
        });
        measure("frame_detection_0.2pct_spikes", new Work() {
            @Override public void run(int index) { engine.clientFrame(index % 500 == 0 ? 300_000_000L : observations[index % observations.length]); }
        });
        Constructor<ProfilerRuntime> constructor = ProfilerRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final ProfilerRuntime runtime = constructor.newInstance();
        final SessionManager sessions = new SessionManager(new StackTraceRepository(32, 16), new ReportExporter() {
            @Override public File export(RecordingSession session) { return null; }
        });
        try {
            set(runtime, "sessions", sessions);
            set(runtime, "sampler", new ThreadSampler(runtime, runtime));
            set(runtime, "initialized", Boolean.TRUE);
            set(runtime, "latest", point());
            sessions.start(true, "benchmark");
            MethodHandle getter;
            try {
                getter = MethodHandles.lookup().findVirtual(ProfilerRuntime.class, "getHudLine", MethodType.methodType(String.class));
            } catch (NoSuchMethodException baseline) {
                getter = MethodHandles.lookup().findStatic(ProfilerOverheadBenchmark.class, "oldHudLine", MethodType.methodType(String.class, ProfilerRuntime.class));
            }
            final MethodHandle hud = getter;
            measure("unchanged_hud_frame", new Work() {
                @Override public void run(int index) throws Throwable { blackhole += ((String) hud.invokeExact(runtime)).length(); }
            });
            sessions.stop("benchmark", false);
        } finally {
            sessions.shutdown();
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static String oldHudLine(ProfilerRuntime runtime) {
        ProfilerStatus status = runtime.status();
        TimelinePoint point = status.getLatest();
        StringBuilder line = new StringBuilder("§b[ICE] ");
        line.append(status.isRecording() ? (status.isActiveCapture() ? "§cCAPTURE" : "§aREC") : "§7PASSIVE");
        if (point != null) line.append(String.format(Locale.ROOT, " §f帧P95 %.1fms  MSPT P95 %.1fms  GC %dms",
            point.getClientFrames().getP95Ms(), point.getServerTicks().getP95Ms(), point.getJvm().getGcPauseMillisDelta()));
        return line.toString();
    }

    private static void measure(String name, Work work) throws Throwable {
        for (int index = 0; index < 5000; index++) work.run(index);
        long[] nanos = new long[5];
        long[] allocations = new long[5];
        long thread = Thread.currentThread().getId();
        for (int round = 0; round < nanos.length; round++) {
            long allocated = BEAN.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            for (int index = 0; index < OPERATIONS; index++) work.run(index);
            nanos[round] = System.nanoTime() - started;
            allocations[round] = BEAN.getThreadAllocatedBytes(thread) - allocated;
        }
        Arrays.sort(nanos);
        Arrays.sort(allocations);
        System.out.printf(Locale.ROOT, "%s median_ns_per_call=%.1f median_allocated_bytes_per_call=%.1f%n",
            name, (double) nanos[2] / OPERATIONS, (double) allocations[2] / OPERATIONS);
    }

    private static TimelinePoint point() {
        DistributionSnapshot frames = new DistributionSnapshot(100L, 12.5D, 12.5D, 12.5D, 12.5D, 12.5D);
        return new TimelinePoint(1L, 1L, frames, DistributionSnapshot.EMPTY, DistributionSnapshot.EMPTY,
            100, 0, 0, 0.0D, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            Collections.<WorldGauge>emptyList(), new JvmSnapshot(0L, 0L, 0L, 0L, 0L, 0.0D), Collections.<ProbeMetric>emptyList());
    }

    private static void set(ProfilerRuntime runtime, String name, Object value) throws Exception {
        Field field = ProfilerRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private interface Work { void run(int index) throws Throwable; }
}

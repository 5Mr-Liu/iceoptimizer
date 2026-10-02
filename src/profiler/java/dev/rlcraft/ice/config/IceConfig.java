package dev.rlcraft.ice.config;

import dev.rlcraft.ice.IceProfilerMod;
import net.minecraftforge.common.config.Config;

@Config(modid = IceProfilerMod.MOD_ID, name = "ice-profiler")
public final class IceConfig {
    @Config.Comment("Core lifecycle and bounded-memory settings.")
    public static General general = new General();

    @Config.Comment("Automatic hitch detection and capture windows.")
    public static Capture capture = new Capture();

    @Config.Comment("Stack sampling frequency and dictionary limits.")
    public static Sampling sampling = new Sampling();

    @Config.Comment("Client-side metrics and user interface.")
    public static Client client = new Client();

    @Config.Comment("Server-side counters and world statistics.")
    public static Server server = new Server();

    @Config.Comment("Deep probe aggregation. Probes never alter return values or game state.")
    public static Probes probes = new Probes();

    @Config.Comment("Report files, retention, and export behavior.")
    public static Reports reports = new Reports();

    @Config.Comment("Local-data privacy and redaction.")
    public static Privacy privacy = new Privacy();

    @Config.Comment("Fail-open compatibility behavior.")
    public static Compatibility compatibility = new Compatibility();

    private IceConfig() {
    }

    public static final class General {
        @Config.Comment("Keep low-overhead metrics and the pre-hitch ring buffer active while the game is running.")
        public boolean passiveMonitoring = true;

        @Config.Comment("Start a full-world session when a world/server starts. This is ignored while automatic incident sessions are enabled.")
        public boolean autoStartSession = true;

        @Config.Comment("Maximum profiler-owned memory. Old samples are discarded before this limit is exceeded.")
        @Config.RangeInt(min = 16, max = 512)
        public int maxProfilerMemoryMiB = 64;

        @Config.Comment("Maximum duration of one automatic session before it is rotated.")
        @Config.RangeInt(min = 5, max = 360)
        public int maxSessionMinutes = 30;

        @Config.Comment("One-second timeline points retained in memory per session.")
        @Config.RangeInt(min = 300, max = 21600)
        public int maxTimelinePoints = 3600;

        @Config.Comment("Observe packet counts and Netty-visible byte totals without reading packet contents.")
        public boolean networkMetrics = true;
    }

    public static final class Capture {
        @Config.Comment("Automatically capture severe client frames, server ticks, and GC pauses.")
        public boolean autoCapture = true;

        @Config.Comment("Stay passive until a hitch, then create a bounded incident session and export it automatically.")
        public boolean automaticIncidentSessions = true;

        @Config.Comment("Additional quiet seconds after the post-capture window before an automatic incident is closed and exported.")
        @Config.RangeInt(min = 0, max = 30)
        public int automaticExportQuietSeconds = 2;

        @Config.Comment("Minimum interval between automatic report exports. New hitches are still recorded and grouped while waiting.")
        @Config.RangeInt(min = 10, max = 600)
        public int minimumAutomaticExportIntervalSeconds = 30;

        @Config.Comment("Absolute client-frame trigger threshold.")
        @Config.RangeInt(min = 20, max = 5000)
        public int frameThresholdMs = 80;

        @Config.Comment("Absolute server-tick trigger threshold.")
        @Config.RangeInt(min = 50, max = 10000)
        public int serverTickThresholdMs = 75;

        @Config.Comment("GC pause delta that creates a dedicated capture.")
        @Config.RangeInt(min = 10, max = 10000)
        public int gcPauseThresholdMs = 50;

        @Config.Comment("Seconds of stack samples retained before a trigger.")
        @Config.RangeInt(min = 3, max = 60)
        public int preCaptureSeconds = 15;

        @Config.Comment("Seconds of stack samples retained after a trigger.")
        @Config.RangeInt(min = 1, max = 30)
        public int postCaptureSeconds = 5;

        @Config.Comment("Merge triggers that occur within this interval into one hitch event.")
        @Config.RangeInt(min = 1, max = 30)
        public int mergeWindowSeconds = 3;

        @Config.Comment("Maximum distinct root-cause clusters retained in one session.")
        @Config.RangeInt(min = 4, max = 128)
        public int maxClusters = 32;

        @Config.Comment("Detailed representative captures retained for each repeated root cause.")
        @Config.RangeInt(min = 1, max = 10)
        public int representativesPerCluster = 3;

        @Config.Comment("Maximum stack samples retained across all detailed captures.")
        @Config.RangeInt(min = 5000, max = 500000)
        public int maxDetailedSamples = 50000;
    }

    public static final class Sampling {
        @Config.Comment("Sampling interval during passive and standard recording.")
        @Config.RangeInt(min = 20, max = 1000)
        public int passiveIntervalMs = 50;

        @Config.Comment("Sampling interval while a hitch capture is active or deep mode is enabled.")
        @Config.RangeInt(min = 5, max = 100)
        public int deepIntervalMs = 10;

        @Config.Comment("Maximum captured Java stack depth.")
        @Config.RangeInt(min = 16, max = 256)
        public int maxStackDepth = 64;

        @Config.Comment("Maximum unique stack traces in the in-memory dictionary.")
        @Config.RangeInt(min = 1024, max = 65536)
        public int maxUniqueStacks = 16384;

        @Config.Comment("Maximum stack-sample objects in the rolling ring buffer.")
        @Config.RangeInt(min = 2000, max = 100000)
        public int ringSamples = 20000;

        @Config.Comment("Seconds between worker-thread discovery passes.")
        @Config.RangeInt(min = 1, max = 30)
        public int workerDiscoverySeconds = 2;

        @Config.Comment("Sample Chunk Batcher, chunk I/O, file I/O, and Netty worker threads.")
        public boolean sampleWorkers = true;

        @Config.Comment("Collect per-thread CPU-time deltas when the JVM exposes them.")
        public boolean cpuTime = true;

        @Config.Comment("Collect per-thread allocation-byte deltas when supported by the current Java 8 VM.")
        public boolean allocatedBytes = true;
    }

    public static final class Client {
        @Config.Comment("Show the compact profiler HUD while recording or after a hitch.")
        public boolean hudEnabled = true;

        @Config.Comment("Keep automatic hitch recording silent. Manual F8/F9 feedback is not affected.")
        public boolean silentAutomaticRecording = true;

        @Config.Comment("Send a chat notice when a hitch capture completes. This is ignored while silent automatic recording is enabled.")
        public boolean hitchNotifications = true;

        @Config.Comment("Read vanilla chunk-render queue sizes through fail-open reflection.")
        public boolean renderQueueMetrics = true;

        @Config.Comment("Attempt asynchronous OpenGL timer queries when the GPU supports them.")
        public boolean gpuTimerQueries = true;

        @Config.Comment("Number of seconds between client world statistics updates.")
        @Config.RangeInt(min = 1, max = 10)
        public int worldStatsIntervalSeconds = 1;

        @Config.Comment("Count client-side chunk load and unload events when connected to a remote server.")
        public boolean chunkEventCounters = true;
    }

    public static final class Server {
        @Config.Comment("Collect loaded chunk, entity, and tile-entity gauges for every loaded dimension.")
        public boolean worldGauges = true;

        @Config.Comment("Count chunk load, unload, data-load, and data-save events.")
        public boolean chunkEventCounters = true;

        @Config.Comment("Number of seconds between potentially expensive world-count scans.")
        @Config.RangeInt(min = 1, max = 10)
        public int worldStatsIntervalSeconds = 1;
    }

    public static final class Probes {
        @Config.Comment("Enable high-resolution aggregation through ProbeBridge and optional hook JARs.")
        public boolean deepProfiling = false;

        @Config.Comment("Track per-class entity tick totals when exact hooks are installed.")
        public boolean entityTicks = true;

        @Config.Comment("Track per-class tile-entity tick totals when exact hooks are installed.")
        public boolean tileEntityTicks = true;

        @Config.Comment("Track Forge event-listener totals when exact hooks are installed.")
        public boolean eventHandlers = true;

        @Config.Comment("Maximum subject classes retained by one exact-probe family.")
        @Config.RangeInt(min = 32, max = 2048)
        public int maxSubjectsPerProbe = 256;
    }

    public static final class Reports {
        @Config.Comment("Generate a self-contained local HTML report.")
        public boolean html = true;

        @Config.Comment("Generate one-second timeline CSV data.")
        public boolean timelineCsv = true;

        @Config.Comment("Generate detailed JSON in addition to the compact binary capture.")
        public boolean detailedJson = false;

        @Config.Comment("Create a ZIP export after report generation.")
        public boolean zip = true;

        @Config.Comment("Maximum completed session directories retained on disk.")
        @Config.RangeInt(min = 1, max = 200)
        public int maxSessions = 20;

        @Config.Comment("Soft maximum report size per session. Optional raw detail is dropped first.")
        @Config.RangeInt(min = 5, max = 500)
        public int maxSessionMiB = 25;
    }

    public static final class Privacy {
        @Config.Comment("Replace absolute game and world paths with placeholders.")
        public boolean redactPaths = true;

        @Config.Comment("Do not include exact chunk or player coordinates in exported reports.")
        public boolean redactCoordinates = true;

        @Config.Comment("World seed collection is disabled and unsupported by default.")
        public boolean includeWorldSeed = false;

        @Config.Comment("Player names are never needed for performance attribution.")
        public boolean includePlayerNames = false;
    }

    public static final class Compatibility {
        @Config.Comment("Disable an individual collector or probe after an error instead of stopping the game.")
        public boolean failOpen = true;

        @Config.Comment("Log probe compatibility decisions at INFO instead of DEBUG.")
        public boolean logProbeDecisions = false;
    }
}

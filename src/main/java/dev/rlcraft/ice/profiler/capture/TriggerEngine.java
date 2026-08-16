package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.config.IceConfig;
import dev.rlcraft.ice.profiler.stats.AdaptiveThreshold;
import java.util.concurrent.TimeUnit;

public final class TriggerEngine {
    public interface Sink {
        void onTrigger(HitchTrigger trigger);
    }

    private final Sink sink;
    private final AdaptiveThreshold clientFrames = new AdaptiveThreshold(600);
    private final AdaptiveThreshold clientTicks = new AdaptiveThreshold(400);
    private final AdaptiveThreshold serverTicks = new AdaptiveThreshold(400);

    public TriggerEngine(Sink sink) {
        this.sink = sink;
    }

    public void clientFrame(long nanos) {
        evaluate(clientFrames, TriggerType.CLIENT_FRAME, nanos, TimeUnit.MILLISECONDS.toNanos(IceConfig.capture.frameThresholdMs), "render frame");
    }

    public void clientTick(long nanos) {
        evaluate(clientTicks, TriggerType.CLIENT_TICK, nanos, TimeUnit.MILLISECONDS.toNanos(IceConfig.capture.frameThresholdMs), "client tick");
    }

    public void serverTick(long nanos) {
        evaluate(serverTicks, TriggerType.SERVER_TICK, nanos, TimeUnit.MILLISECONDS.toNanos(IceConfig.capture.serverTickThresholdMs), "server tick");
    }

    public void gcPause(long pauseMillis) {
        if (!IceConfig.capture.autoCapture || pauseMillis < IceConfig.capture.gcPauseThresholdMs) return;
        emit(TriggerType.GC_PAUSE, TimeUnit.MILLISECONDS.toNanos(pauseMillis), TimeUnit.MILLISECONDS.toNanos(IceConfig.capture.gcPauseThresholdMs), "GC delta");
    }

    public void manual(String detail) {
        emit(TriggerType.MANUAL, 0L, 0L, detail);
    }

    private void evaluate(AdaptiveThreshold threshold, TriggerType type, long nanos, long absolute, String detail) {
        long effective = threshold.thresholdNanos(absolute);
        threshold.record(nanos);
        if (IceConfig.capture.autoCapture && nanos >= effective) emit(type, nanos, effective, detail);
    }

    private void emit(TriggerType type, long duration, long threshold, String detail) {
        sink.onTrigger(new HitchTrigger(type, System.nanoTime(), System.currentTimeMillis(), duration, threshold, detail));
    }
}

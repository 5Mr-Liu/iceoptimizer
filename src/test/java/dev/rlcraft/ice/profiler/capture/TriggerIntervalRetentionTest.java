package dev.rlcraft.ice.profiler.capture;

import static org.junit.Assert.*;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import org.junit.Test;

public class TriggerIntervalRetentionTest {
    @Test public void overlappingSeverityUpdatesDoNotDuplicateOrLoseHeapEntries() {
        RoleAwareSampleBuffer buffer = new RoleAwareSampleBuffer(6);
        for (int i = 1; i <= 3; i++) buffer.add(sample(i, ThreadRole.SERVER_MAIN, i));
        buffer.protect(trigger(3L, 3L));
        buffer.protect(trigger(3L, 6L));
        buffer.add(sample(4L, ThreadRole.SERVER_MAIN, 4));
        buffer.protect(trigger(4L, 1L));
        RoleAwareSampleBuffer.Selection selected = buffer.select(3);
        assertEquals(3, selected.getSamples().size());
        for (StackSample sample : selected.getSamples()) assertTrue(sample.getStackTraceId() <= 3);
    }

    @Test public void middleServerHitchSurvivesLongPostWindowWithinOriginalSampleLimit() {
        HitchCapture capture = new HitchCapture(1L, 0L, 0L, 10_000L);
        for (int i = 0; i < 10; i++) capture.addSample(sample(100L + i * 10L, ThreadRole.SERVER_MAIN, i), 20);
        capture.addTrigger(trigger(200L, 100L));
        for (int i = 0; i < 1_000; i++) capture.addSample(sample(300L + i, ThreadRole.SERVER_MAIN, 1_000 + i), 20);
        capture.seal();
        int critical = 0;
        for (StackSample sample : capture.getSamples()) if (sample.getStackTraceId() < 10) critical++;
        assertEquals(10, critical);
        assertTrue(capture.getSamples().size() <= 20);
        assertEquals(1_010L - capture.getSamples().size(), capture.getDroppedPostSampleCount());
    }

    @Test public void severeIntervalIsNotReplacedByManyLaterSmallTriggers() {
        HitchCapture capture = new HitchCapture(1L, 0L, 0L, 10_000L);
        for (int i = 0; i < 8; i++) capture.addSample(sample(100L + i * 10L, ThreadRole.SERVER_MAIN, i), 20);
        capture.addTrigger(trigger(200L, 150L));
        for (int i = 0; i < 100; i++) {
            long end = 300L + i * 20L;
            capture.addSample(sample(end - 1L, ThreadRole.SERVER_MAIN, 100 + i), 20);
            capture.addTrigger(trigger(end, 2L));
        }
        capture.seal();
        int severe = 0;
        for (StackSample sample : capture.getSamples()) if (sample.getStackTraceId() < 8) severe++;
        assertEquals(8, severe);
        assertTrue(capture.getSamples().size() <= 20);
    }

    @Test public void protectingServerIntervalDoesNotProtectClientWorkInSameTimeRange() {
        RoleAwareSampleBuffer buffer = new RoleAwareSampleBuffer(20);
        for (int i = 0; i < 5; i++) {
            buffer.add(sample(100L + i, ThreadRole.SERVER_MAIN, i));
            buffer.add(sample(100L + i, ThreadRole.CLIENT_MAIN, 100 + i));
        }
        buffer.protect(trigger(200L, 100L));
        for (int i = 0; i < 100; i++) buffer.add(sample(300L + i, ThreadRole.CLIENT_MAIN, 1_000 + i));
        RoleAwareSampleBuffer.Selection selected = buffer.select(5);
        assertEquals(5, selected.getSamples().size());
        for (StackSample sample : selected.getSamples()) assertEquals(ThreadRole.SERVER_MAIN, sample.getRole());
    }

    private static HitchTrigger trigger(long end, long duration) {
        return new HitchTrigger(TriggerType.SERVER_TICK, end, end, duration, 1L, "test");
    }
    private static StackSample sample(long time, ThreadRole role, int stack) {
        return new StackSample(time, role.ordinal() + 1L, role.name(), role, stack, 1L, 0L, Thread.State.RUNNABLE);
    }
}

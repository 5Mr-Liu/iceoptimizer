package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ContextLifecycleTrackerTest {
    @Test
    public void reportsNullLossOnceAndDoesNotDoubleCountRecovery() {
        ContextLifecycleTracker tracker = new ContextLifecycleTracker();
        Object first = new Object();
        Object second = new Object();
        assertFalse(tracker.observe(first));
        assertTrue(tracker.isPresent());
        assertTrue(tracker.observe(null));
        assertFalse(tracker.isPresent());
        assertFalse(tracker.observe(null));
        assertFalse(tracker.observe(second));
        assertTrue(tracker.isPresent());
        assertFalse(tracker.observe(second));
        assertTrue(tracker.observe(new Object()));
    }

    @Test
    public void initialAbsenceAndFirstContextAreNotAReset() {
        ContextLifecycleTracker tracker = new ContextLifecycleTracker();
        assertFalse(tracker.observe(null));
        assertFalse(tracker.observe(null));
        assertFalse(tracker.observe(new Object()));
    }
}

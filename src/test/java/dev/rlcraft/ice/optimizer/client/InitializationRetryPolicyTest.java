package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InitializationRetryPolicyTest {
    @Test
    public void backsOffExponentiallyWithinOneGenerationAndCapsDelay() {
        InitializationRetryPolicy policy = new InitializationRetryPolicy(10L, 40L);
        assertTrue(policy.canAttempt(1L, 100L));
        assertEquals(10L, policy.recordFailure(1L, 100L));
        assertFalse(policy.canAttempt(1L, 109L));
        assertTrue(policy.canAttempt(1L, 110L));
        assertEquals(20L, policy.recordFailure(1L, 110L));
        assertEquals(40L, policy.recordFailure(1L, 130L));
        assertEquals(40L, policy.recordFailure(1L, 170L));
        assertEquals(4, policy.failures());
    }

    @Test
    public void generationChangeImmediatelyCancelsOldBackoff() {
        InitializationRetryPolicy policy = new InitializationRetryPolicy(100L, 1000L);
        policy.recordFailure(3L, 1000L);
        assertFalse(policy.canAttempt(3L, 1001L));
        assertTrue(policy.canAttempt(4L, 1001L));
        assertEquals(0, policy.failures());
        policy.recordSuccess(4L);
        assertEquals(0L, policy.remainingNanos(4L, 1001L));
    }
}

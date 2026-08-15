package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleCircuitBreakerTest {
    @Test
    public void exactTargetActivatesAndRepeatedErrorsTripOnlyThatModule() {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(OptimizationModule.SRP_STATIC_MESH);
        breaker.configure(true, 2);
        assertEquals(ModuleState.WAITING_FOR_TARGET, breaker.snapshot().getState());
        breaker.targetObserved("example.Target", "0123456789abcdef", true);
        assertEquals(ModuleState.VERIFIED, breaker.snapshot().getState());
        breaker.patchInstalled("example.Target", "0123456789abcdef");
        assertTrue(breaker.isOperational());
        breaker.recordFailure(new IllegalStateException("first"));
        assertEquals(ModuleState.DEGRADED, breaker.snapshot().getState());
        breaker.recordFailure(new IllegalStateException("second"));
        assertEquals(ModuleState.TRIPPED, breaker.snapshot().getState());
        assertFalse(breaker.isOperational());
    }

    @Test
    public void strictPackLockRejectionCannotBeReversedByLatePatchEvents() {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(OptimizationModule.CHUNK_MESH_AO);
        breaker.configure(true, 3);
        breaker.targetObserved("example.Target", "first", true);
        breaker.patchInstalled("example.Target", "first");
        assertTrue(breaker.isOperational());

        breaker.rejectByPackLock("测试包锁拒绝");
        breaker.targetObserved("example.LateTarget", "second", true);
        breaker.patchInstalled("example.LateTarget", "second");
        breaker.activate("晚到激活");
        breaker.recordSuccess();

        assertEquals(ModuleState.INCOMPATIBLE, breaker.snapshot().getState());
        assertEquals("测试包锁拒绝", breaker.snapshot().getDetail());
        assertFalse(breaker.isOperational());

        breaker.configure(true, 3);
        assertEquals(ModuleState.INCOMPATIBLE, breaker.snapshot().getState());
        assertFalse(breaker.isOperational());
    }

    @Test
    public void rejectionWhileDisabledStillAppliesIfModuleIsLaterConfiguredOn() {
        ModuleCircuitBreaker breaker = new ModuleCircuitBreaker(OptimizationModule.RENDERLIB_VISIBILITY);
        breaker.configure(false, 3);
        breaker.rejectByPackLock("启动包锁失败");
        assertEquals(ModuleState.DISABLED, breaker.snapshot().getState());
        breaker.configure(true, 3);
        assertEquals(ModuleState.INCOMPATIBLE, breaker.snapshot().getState());
        assertFalse(breaker.isOperational());
    }
}

package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class OptimizerPauseTest {
    @Before public void setup() {
        OptimizerRegistry.beginRuntime();
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @After public void cleanup() { setup(); }

    @Test public void masksBothWordsWithoutRevivingDisabledOrRejectedModules() {
        ModuleCircuitBreaker active = OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND);
        ModuleCircuitBreaker high = OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_SHADER_IMAGE);
        ModuleCircuitBreaker disabled = OptimizerRegistry.breaker(OptimizationModule.MODERN_PARTICLE_BACKEND);
        ModuleCircuitBreaker incompatible = OptimizerRegistry.breaker(OptimizationModule.MODERN_TESR_BACKEND);
        ModuleCircuitBreaker locked = OptimizerRegistry.breaker(OptimizationModule.MODERN_TERRAIN_BACKEND);
        ModuleCircuitBreaker tripped = OptimizerRegistry.breaker(OptimizationModule.MODERN_HUD_STREAM);
        active.activate("active");
        high.activate("high");
        disabled.configure(false, 3);
        incompatible.forceIncompatible("incompatible");
        locked.rejectByPackLock("locked");
        tripped.configure(true, 1);
        tripped.recordFailure(new IllegalStateException("failed"));
        assertTrue(high.snapshot().getModule().ordinal() >= Long.SIZE);

        OptimizerRegistry.setRuntimePaused(true);
        assertEquals(0L, OptimizerRegistry.operationalMaskForTest());
        assertEquals(0L, OptimizerRegistry.operationalMaskHighForTest());
        assertEquals(ModuleState.ACTIVE, active.snapshot().getState());
        assertTrue(OptimizerRegistry.isConfiguredOperational(OptimizationModule.MODERN_ENTITY_BACKEND));
        assertFalse(OptimizerRegistry.isOperational(OptimizationModule.MODERN_ENTITY_BACKEND));
        OptimizerRegistry.setRuntimePaused(false);

        assertTrue(OptimizerRegistry.isOperational(OptimizationModule.MODERN_ENTITY_BACKEND));
        assertTrue(OptimizerRegistry.isOperational(OptimizationModule.OPTIFINE_SHADER_IMAGE));
        assertFalse(OptimizerRegistry.isOperational(OptimizationModule.MODERN_PARTICLE_BACKEND));
        assertEquals(ModuleState.INCOMPATIBLE, incompatible.snapshot().getState());
        assertEquals("locked", locked.snapshot().getDetail());
        assertEquals(ModuleState.TRIPPED, tripped.snapshot().getState());
        assertEquals(1L, tripped.snapshot().getFailures());
    }

    @Test public void latePatchAndSuccessCannotUnpauseOrResetFailureHistory() {
        ModuleCircuitBreaker late = OptimizerRegistry.breaker(OptimizationModule.FOAMFIX_TEXTURE_UPLOAD);
        late.configure(true, 2);
        ModuleCircuitBreaker degraded = OptimizerRegistry.breaker(OptimizationModule.MODERN_ENTITY_BACKEND);
        degraded.configure(true, 2);
        degraded.recordFailure(new IllegalStateException("first"));
        OptimizerRegistry.setRuntimePaused(true);
        late.patchInstalled("texture", "hash");
        late.recordSuccess();
        assertTrue(late.isOperational());
        assertFalse(OptimizerRegistry.isOperational(OptimizationModule.FOAMFIX_TEXTURE_UPLOAD));
        assertEquals(0L, OptimizerRegistry.operationalMaskForTest());
        OptimizerRegistry.setRuntimePaused(false);
        degraded.recordFailure(new IllegalStateException("second"));
        assertEquals(ModuleState.TRIPPED, degraded.snapshot().getState());
        assertTrue(OptimizerRegistry.isOperational(OptimizationModule.FOAMFIX_TEXTURE_UPLOAD));
    }
}

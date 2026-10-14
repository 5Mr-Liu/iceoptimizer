package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.*;
import java.lang.reflect.Method;
import org.junit.*;
import static org.junit.Assert.*;

public class RecurrentReflectionBridgeTest {
    private boolean oldEnabled, oldModule, oldPaused;
    @Before public void enable() {
        oldEnabled = OptimizerConfig.settings.enabled; oldModule = OptimizerConfig.settings.recurrentReflection;
        oldPaused = OptimizerRegistry.isRuntimePaused(); OptimizerConfig.settings.enabled = true; OptimizerConfig.settings.recurrentReflection = true;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture()); OptimizerRegistry.setRuntimePaused(false);
        OptimizerRegistry.breaker(OptimizationModule.RECURRENT_REFLECTION).patchInstalled("synthetic", "test");
    }
    @After public void restore() {
        OptimizerConfig.settings.enabled = oldEnabled; OptimizerConfig.settings.recurrentReflection = oldModule;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture()); OptimizerRegistry.setRuntimePaused(oldPaused);
    }
    @Test public void classMetadataIsReusedButValuesAreAlwaysLive() throws Exception {
        Event event = new Event(); assertTrue(RecurrentReflectionBridge.methodsAvailable(event, 3));
        Method get = RecurrentReflectionBridge.declaredMethod(Event.class, "getModifiedAmount", new Class<?>[0]);
        assertSame(get, RecurrentReflectionBridge.declaredMethod(Event.class, "getModifiedAmount", new Class<?>[0]));
        assertEquals(1, get.invoke(event)); event.amount = 7; assertEquals(7, get.invoke(event));
        RecurrentReflectionBridge.declaredMethod(Event.class, "setModifiedAmount", new Class<?>[] {int.class}).invoke(event, 9);
        assertEquals(9, get.invoke(event)); assertEquals(3, event.reads);
    }
    @Test public void missingAndInheritedMethodsKeepDeclaredOnlySemantics() {
        assertFalse(RecurrentReflectionBridge.methodsAvailable(new Object(), 3));
        assertFalse(RecurrentReflectionBridge.methodsAvailable(new ChildEvent(), 3));
        assertTrue(RecurrentReflectionBridge.methodsAvailable(new GetterOnly(), 1));
        assertFalse(RecurrentReflectionBridge.methodsAvailable(new GetterOnly(), 3));
    }
    @Test public void structureBindingsKeepArgumentsAndNamesDistinct() {
        Method first = RecurrentReflectionBridge.structureMethod(Event.class, "setModifiedAmount", "setModifiedAmount", new Class<?>[]{int.class});
        assertSame(first, RecurrentReflectionBridge.structureMethod(Event.class, "setModifiedAmount", "setModifiedAmount", new Class<?>[]{int.class}));
        assertNotSame(first, RecurrentReflectionBridge.structureMethod(Event.class, "getModifiedAmount", "getModifiedAmount", new Class<?>[0]));
    }
    public static class Event { int amount = 1, reads; public boolean hasAmountData() { return true; } public int getModifiedAmount() { reads++; return amount; } public void setModifiedAmount(int value) { amount = value; } }
    public static class ChildEvent extends Event { }
    public static class GetterOnly { public int getModifiedAmount() { return 4; } }
}

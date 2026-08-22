package dev.rlcraft.ice.optimizer.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineProgramState;
import dev.rlcraft.ice.optimizer.render.optifine.PreparedShaderPermutation;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationPipeline;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationRegistry;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ModernRendererRuntimeShaderBindingTest {
    @Before public void enableShaderDomains() {
        OptimizerRegistry.beginRuntime();
        OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_SHADER_STATE)
            .configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.OPTIFINE_SHADER_IMAGE)
            .configure(true, 3);
    }

    @After public void resetRegistry() {
        OptimizerRegistry.beginRuntime();
    }

    @Test
    public void exhaustedHeapWithoutCertificationRegistryFailsClosedWithoutNpe()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = new ModernRendererRuntime(epochs,
            new CacheBudget(1L, 1L, 1L));
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);

        assertNull(register(runtime, new FakeProgram(11), 11, 111,
            prepared(certifications, 0, 1L, 1L)));
        assertEquals(0, runtime.shaderBindingCountForTest());
        assertEquals(0L, runtime.cacheBudget().snapshot().getHeapUsed());
    }

    @Test
    public void staleGenerationAndProgramNameReplacementEvictBindings()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        FakeProgram program = new FakeProgram(17);
        Object binding = register(runtime, program, 17, 117,
            prepared(certifications, 1, 1L, 1L));
        assertTrue(current(runtime, binding, program, 17));
        assertTrue(runtime.cacheBudget().snapshot().getHeapUsed() > 0L);

        program.id = 18;
        assertFalse(current(runtime, binding, program, 18));
        assertEquals(0, runtime.shaderBindingCountForTest());
        assertEquals(0L, runtime.cacheBudget().snapshot().getHeapUsed());

        program.id = 21;
        binding = register(runtime, program, 21, 121,
            prepared(certifications, 2, 1L, 1L));
        epochs.invalidateResources();
        assertFalse(current(runtime, binding, program, 21));
        assertEquals(0, runtime.shaderBindingCountForTest());
    }

    @Test
    public void validationQueueHasAHardLimitAndRejectedEntryCanRetry()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(128);
        setField(runtime, "shaders", certifications);

        Object overflowBinding = null;
        OptifineProgramState overflowState = null;
        for (int index = 0; index < 65; index++) {
            int legacy = 1000 + index;
            PreparedShaderPermutation prepared = prepared(certifications,
                index, 1L, 1L);
            certifications.recordCompile(prepared.getKey(), true, "ok");
            FakeProgram program = new FakeProgram(legacy);
            Object binding = register(runtime, program, legacy, 2000 + index,
                prepared);
            OptifineProgramState state = state(legacy);
            queue(runtime, binding, state);
            if (index == 64) {
                overflowBinding = binding;
                overflowState = state;
            }
        }
        assertEquals(64, runtime.pendingShaderValidationCountForTest());

        validationQueue(runtime).clear();
        queue(runtime, overflowBinding, overflowState);
        assertEquals(1, runtime.pendingShaderValidationCountForTest());
    }

    @Test
    public void activeNativeBindingCannotBeForgottenBeforeRestoration()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        FakeProgram program = new FakeProgram(41);
        Object binding = register(runtime, program, 41, 141,
            prepared(certifications, 3, 1L, 1L));
        setField(runtime, "activeNativeShaderBinding", binding);

        Method clear = method("clearShaderBindings");
        clear.setAccessible(true);
        try {
            clear.invoke(runtime);
            throw new AssertionError("expected active shader ownership guard");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, runtime.shaderBindingCountForTest());

        setField(runtime, "activeNativeShaderBinding", null);
        clear.invoke(runtime);
        assertEquals(0, runtime.shaderBindingCountForTest());
        assertEquals(0L, runtime.cacheBudget().snapshot().getHeapUsed());
    }

    @Test
    public void activeNativeBindingCannotBeReplacedByQueuedCompilation()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        FakeProgram first = new FakeProgram(51);
        Object binding = register(runtime, first, 51, 151,
            prepared(certifications, 4, 1L, 1L));
        setField(runtime, "activeNativeShaderBinding", binding);

        try {
            register(runtime, new FakeProgram(52), 52, 152,
                prepared(certifications, 5, 1L, 1L));
            throw new AssertionError("expected active shader replacement guard");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, runtime.shaderBindingCountForTest());
    }

    @Test
    public void preservedUseProgramCanReconcileUncertainCandidateOwnership()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        FakeProgram program = new FakeProgram(71);
        Object binding = register(runtime, program, 71, 171,
            prepared(certifications, 6, 1L, 1L));
        setField(runtime, "activeNativeShaderBinding", binding);

        assertFalse("an unverified observation cannot release ownership",
            runtime.reconcileObservedShaderProgram(71, false));
        assertFalse("observing the candidate name cannot prove restoration",
            runtime.reconcileObservedShaderProgram(171, true));
        assertTrue("a synchronized legacy binding proves candidate retirement",
            runtime.reconcileObservedShaderProgram(71, true));

        Method clear = method("clearShaderBindings");
        clear.setAccessible(true);
        clear.invoke(runtime);
        assertEquals(0, runtime.shaderBindingCountForTest());
    }

    @Test
    public void pendingShutdownRejectsNewShaderCandidates() throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        setField(runtime, "shutdownRequested", Boolean.TRUE);
        assertFalse(runtime.queueOptifineShaderSources(new FakeProgram(61),
            "pack", "program", "base", 1L, 1L,
            "program.vsh", "void main(){gl_Position=gl_Vertex;}",
            null, null, "program.fsh",
            "void main(){gl_FragColor=vec4(1.0);}", ""));
        assertEquals(0, runtime.pendingShaderCandidateCountForTest());
    }

    @Test
    public void secondMapPublicationFailureRollsBackAndClosesNewReservation()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        register(runtime, new FakeProgram(81), 81, 181,
            prepared(certifications, 7, 1L, 1L));
        long retained = runtime.cacheBudget().snapshot().getHeapUsed();
        runtime.shaderBindingPublicationFaultForTest(
            new ModernRendererRuntime.ShaderBindingPublicationFault() {
                @Override public void checkpoint(String point) {
                    if ("after-identity".equals(point)) {
                        throw new IllegalStateException("second map failed");
                    }
                }
            });

        assertPublicationFailure(runtime, certifications, 82, 8);
        assertFalse(runtime.shaderBindingsPoisonedForTest());
        assertEquals(1, runtime.shaderBindingCountForTest());
        assertEquals(retained, runtime.cacheBudget().snapshot().getHeapUsed());
    }

    @Test
    public void rollbackFailurePoisonsBothTablesAndReleasesAllReservations()
        throws Exception {
        ClientEpochs epochs = new ClientEpochs();
        ModernRendererRuntime runtime = runtime(epochs);
        ShaderCertificationRegistry certifications =
            new ShaderCertificationRegistry(32);
        register(runtime, new FakeProgram(91), 91, 191,
            prepared(certifications, 9, 1L, 1L));
        runtime.shaderBindingPublicationFaultForTest(
            new ModernRendererRuntime.ShaderBindingPublicationFault() {
                @Override public void checkpoint(String point) {
                    if ("after-identity".equals(point)
                        || "before-identity-rollback".equals(point)) {
                        throw new IllegalStateException(point);
                    }
                }
            });

        assertPublicationFailure(runtime, certifications, 92, 10);
        assertTrue(runtime.shaderBindingsPoisonedForTest());
        assertEquals(0, runtime.shaderBindingCountForTest());
        assertEquals(0L, runtime.cacheBudget().snapshot().getHeapUsed());
    }

    private static void assertPublicationFailure(ModernRendererRuntime runtime,
        ShaderCertificationRegistry certifications, int legacy, int index)
        throws Exception {
        try {
            register(runtime, new FakeProgram(legacy), legacy, legacy + 100,
                prepared(certifications, index, 1L, 1L));
            throw new AssertionError("expected publication failure");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    private static ModernRendererRuntime runtime(ClientEpochs epochs) {
        return new ModernRendererRuntime(epochs,
            new CacheBudget(32L * 1024L * 1024L, 1L, 1L));
    }

    private static PreparedShaderPermutation prepared(
        ShaderCertificationRegistry certifications, int index,
        long resources, long shaders) {
        return new ShaderCertificationPipeline(certifications).prepareResolved(
            "pack", "program" + index, "base", resources, shaders,
            "program.vsh", "void main(){gl_Position=gl_Vertex;}",
            null, null, "program.fsh", "void main(){gl_FragColor=vec4(1.0);}",
            "");
    }

    private static OptifineProgramState state(int program) {
        return new OptifineProgramState("program", "GBUFFERS", program, 0,
            new int[0], 0, 0, null, null, null);
    }

    private static Object register(ModernRendererRuntime runtime,
                                   Object program, int legacy, int candidate,
                                   PreparedShaderPermutation prepared)
        throws Exception {
        Method method = ModernRendererRuntime.class.getDeclaredMethod(
            "registerShaderBinding", Object.class, Integer.TYPE, Integer.TYPE,
            PreparedShaderPermutation.class);
        method.setAccessible(true);
        return method.invoke(runtime, program, Integer.valueOf(legacy),
            Integer.valueOf(candidate), prepared);
    }

    private static boolean current(ModernRendererRuntime runtime, Object binding,
                                   Object program, int observed) throws Exception {
        Method method = method("bindingCurrent");
        method.setAccessible(true);
        return ((Boolean) method.invoke(runtime, binding, program,
            Integer.valueOf(observed))).booleanValue();
    }

    private static void queue(ModernRendererRuntime runtime, Object binding,
                              OptifineProgramState state) throws Exception {
        Method method = method("queueShaderValidation");
        method.setAccessible(true);
        method.invoke(runtime, binding, state);
    }

    private static Method method(String name) {
        for (Method method : ModernRendererRuntime.class.getDeclaredMethods()) {
            if (name.equals(method.getName())) return method;
        }
        throw new IllegalStateException("missing " + name);
    }

    private static void setField(Object target, String name, Object value)
        throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Object> validationQueue(ModernRendererRuntime runtime)
        throws Exception {
        Field field = ModernRendererRuntime.class.getDeclaredField(
            "pendingShaderValidations");
        field.setAccessible(true);
        return (ArrayDeque<Object>) field.get(runtime);
    }

    public static final class FakeProgram {
        private int id;
        private FakeProgram(int id) { this.id = id; }
        public String getName() { return "program"; }
        public Object getProgramStage() { return null; }
        public int getId() { return id; }
        public IntBuffer getDrawBuffers() { return null; }
        public int getCompositeMipmapSetting() { return 0; }
        public int getCountInstances() { return 0; }
        public Object getAlphaState() { return null; }
        public Object getBlendState() { return null; }
        public Object getRenderScale() { return null; }
    }
}

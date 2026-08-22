package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public final class LwjglOptifineShaderActivationTest {
    @Test
    public void productionUniformWorkspaceIsBudgetedAndReleased() {
        CacheBudget budget = new CacheBudget(1L, 1024L, 1L);
        LwjglOptifineShaderActivation activation =
            new LwjglOptifineShaderActivation(
                RenderThreadGuard.captureCurrent(), budget);
        assertTrue(activation.isWorkspaceAvailableForTest());
        assertEquals(LwjglOptifineShaderActivation.UNIFORM_WORKSPACE_BYTES,
            budget.snapshot().getDirectUsed());
        activation.close();
        activation.close();
        assertFalse(activation.isWorkspaceAvailableForTest());
        assertEquals(0L, budget.snapshot().getDirectUsed());
    }

    @Test
    public void publishesGlUniformValuesIdAndManagersAsOneTransaction() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(logical, 11);
        List<String> order = driver.events;
        LwjglOptifineShaderActivation activation = activation(driver, order);

        LwjglOptifineShaderActivation.Result result = activation.switchProgram(
            logical, 11, 22);
        assertTrue(result.getDetail(), result.isSwitched());
        assertEquals(22, driver.currentProgram);
        assertEquals(22, driver.activeProgram);
        assertEquals(22, driver.uniformProgram);
        assertEquals(Arrays.asList("snapshot:11:22", "use:22", "apply",
            "active:22", "uniforms:22"), order);
    }

    @Test
    public void managerFailureRestoresEveryPublishedProgramState() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(logical, 31);
        driver.failUniformTarget = 42;
        LwjglOptifineShaderActivation activation = activation(driver,
            driver.events);

        LwjglOptifineShaderActivation.Result result = activation.switchProgram(
            logical, 31, 42);
        assertFalse(result.isSwitched());
        assertTrue(result.isInfrastructureFailure());
        assertTrue(result.isRollbackSucceeded());
        assertEquals(31, driver.currentProgram);
        assertEquals(31, driver.activeProgram);
        assertEquals(31, driver.uniformProgram);
        assertTrue(driver.events.contains("use:31"));
        assertTrue(driver.events.contains("active:31"));
        assertTrue(driver.events.contains("uniforms:31"));
    }

    @Test
    public void rollbackFailureIsExplicitAndNeverReportedAsARejection() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(logical, 51);
        driver.failUniformTarget = 62;
        driver.failUniformRollback = 51;
        LwjglOptifineShaderActivation.Result result = activation(driver,
            driver.events).switchProgram(logical, 51, 62);
        assertFalse(result.isSwitched());
        assertTrue(result.isInfrastructureFailure());
        assertFalse(result.isRollbackSucceeded());
    }

    @Test
    public void inconsistentOwnerOrProgramRejectsBeforeAnyMutation() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(new Object(), 71);
        LwjglOptifineShaderActivation.Result result = activation(driver,
            driver.events).switchProgram(logical, 71, 82);
        assertFalse(result.isSwitched());
        assertFalse(result.isInfrastructureFailure());
        assertTrue(driver.events.isEmpty());

        driver.owner = logical;
        driver.currentProgram = 70;
        result = activation(driver, driver.events).switchProgram(logical, 71, 82);
        assertFalse(result.isSwitched());
        assertTrue(driver.events.isEmpty());
    }

    @Test
    public void reverseSwitchCopiesLatestCandidateUniformsBackToOriginal() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(logical, 92);
        LwjglOptifineShaderActivation.Result result = activation(driver,
            driver.events).switchProgram(logical, 92, 81);
        assertTrue(result.isSwitched());
        assertEquals("snapshot:92:81", driver.events.get(0));
        assertEquals(81, driver.uniformProgram);
    }

    @Test
    public void synchronizationRequiresBothOptifineAndGlToAgree() {
        Object logical = new Object();
        FakeDriver driver = new FakeDriver(logical, 101);
        LwjglOptifineShaderActivation activation = activation(driver,
            driver.events);
        assertTrue(activation.isProgramSynchronized(logical, 101));

        driver.currentProgram = 102;
        assertFalse(activation.isProgramSynchronized(logical, 101));
        driver.currentProgram = 101;
        driver.activeProgram = 102;
        assertFalse(activation.isProgramSynchronized(logical, 101));
        driver.activeProgram = 101;
        driver.owner = new Object();
        assertFalse(activation.isProgramSynchronized(logical, 101));
    }

    @Test
    public void reviewedG5ActivationAbiResolvesWithoutClassInitialization()
        throws Exception {
        String configured = System.getProperty("ice.optifine.jar", "").trim();
        String client = System.getProperty("ice.minecraft.client.jar", "").trim();
        Assume.assumeTrue("run with -PoptifineJar and -PminecraftClientJar",
            !configured.isEmpty() && !client.isEmpty());
        File fixture = new File(configured);
        File clientFixture = new File(client);
        Assume.assumeTrue(fixture.isFile() && clientFixture.isFile());
        URLClassLoader loader = new URLClassLoader(
            new URL[] { fixture.toURI().toURL(), clientFixture.toURI().toURL() },
            LwjglOptifineShaderActivationTest.class.getClassLoader());
        try {
            Class<?> program = Class.forName("net.optifine.shaders.Program",
                false, loader);
            LwjglOptifineShaderActivation.verifyAbi(program);
        } finally {
            loader.close();
        }
    }

    private static LwjglOptifineShaderActivation activation(
        FakeDriver driver, final List<String> events) {
        return new LwjglOptifineShaderActivation(RenderThreadGuard.captureCurrent(),
            driver, new LwjglOptifineShaderActivation.UniformTransfer() {
                @Override public LwjglOptifineShaderActivation.Transfer snapshot(
                    final int sourceProgram, final int targetProgram) {
                    events.add("snapshot:" + sourceProgram + ":" + targetProgram);
                    return new LwjglOptifineShaderActivation.Transfer() {
                        @Override public boolean isValid() { return true; }
                        @Override public String getDetail() { return "valid"; }
                        @Override public void apply() { events.add("apply"); }
                    };
                }
            });
    }

    private static final class FakeDriver
        implements LwjglOptifineShaderActivation.Driver {
        private Object owner;
        private int currentProgram;
        private int activeProgram;
        private int uniformProgram;
        private int failUniformTarget = Integer.MIN_VALUE;
        private int failUniformRollback = Integer.MIN_VALUE;
        private final List<String> events = new ArrayList<String>();

        private FakeDriver(Object owner, int program) {
            this.owner = owner;
            currentProgram = program;
            activeProgram = program;
            uniformProgram = program;
        }

        @Override public int currentGlProgram() { return currentProgram; }
        @Override public boolean ownsLogicalProgram(Object program) {
            return owner == program;
        }
        @Override public int activeProgramId(Object program) {
            return activeProgram;
        }
        @Override public void useProgram(int program) {
            events.add("use:" + program);
            currentProgram = program;
        }
        @Override public void setActiveProgramId(Object program, int value) {
            events.add("active:" + value);
            activeProgram = value;
        }
        @Override public void setUniformPrograms(Object program, int value) {
            events.add("uniforms:" + value);
            uniformProgram = value;
            if (value == failUniformTarget || value == failUniformRollback) {
                throw new IllegalStateException("injected uniform manager failure");
            }
        }
    }
}

package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.memory.CacheBudget;
import dev.rlcraft.ice.optimizer.render.resource.RenderResourceKind;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class LwjglShaderProgramInstallerTest {
    @Test
    public void installsOnceAndRequiresAllCertificationAndGenerations() {
        Fixture fixture = new Fixture(2, 512 * 1024L);
        PreparedShaderPermutation prepared = prepared(1L, 3L, 17L);

        LwjglShaderProgramInstaller.InstallResult first = fixture.installer.install(
            prepared, 1L, 2L, 3L);
        assertTrue(first.getDetail(), first.isInstalled());
        assertEquals(100, first.getProgramId());
        assertEquals(1, fixture.installer.size());
        assertEquals(2, fixture.driver.deletedShaders.size());
        assertEquals(0, fixture.driver.deletedPrograms.size());

        LwjglShaderProgramInstaller.InstallResult duplicate = fixture.installer.install(
            prepared, 1L, 2L, 3L);
        assertTrue(duplicate.isInstalled());
        assertEquals(100, duplicate.getProgramId());
        assertEquals("duplicate must not compile again", 2,
            fixture.driver.nextShader.get() - 1);
        assertTrue(fixture.installer.isInstalled(prepared.getKey(), 1L, 2L, 3L));
        assertFalse(fixture.installer.isInstalled(prepared.getKey(), 1L, 9L, 3L));

        ShaderCertificationRegistry certifications = new ShaderCertificationRegistry(16);
        certifications.recordCompile(prepared.getKey(), true, "");
        assertEquals(0, fixture.installer.certifiedProgram(prepared.getKey(),
            certifications, 1L, 2L, 3L));
        certifications.recordStateValidation(prepared.getKey(), true);
        certifications.recordImageValidation(prepared.getKey(), true);
        assertEquals(100, fixture.installer.certifiedProgram(prepared.getKey(),
            certifications, 1L, 2L, 3L));
        assertEquals(0, fixture.installer.certifiedProgram(prepared.getKey(),
            certifications, 1L, 2L, 4L));

        fixture.installer.reset(true);
        assertEquals(0, fixture.installer.size());
        assertEquals(1, fixture.ledger.collect(2L, 8));
        assertTrue(fixture.driver.deletedPrograms.contains(Integer.valueOf(100)));
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
    }

    @Test
    public void compileLinkAndBudgetFailuresCleanEveryPartialObject() {
        Fixture vertexFailure = new Fixture(2, 512 * 1024L);
        vertexFailure.driver.failedShaders.add(Integer.valueOf(1));
        assertFalse(vertexFailure.installer.install(prepared(1L, 3L, 1L),
            1L, 2L, 3L).isInstalled());
        assertEquals(Collections.singleton(Integer.valueOf(1)),
            vertexFailure.driver.deletedShaders);
        assertTrue(vertexFailure.driver.deletedPrograms.isEmpty());

        Fixture fragmentFailure = new Fixture(2, 512 * 1024L);
        fragmentFailure.driver.failedShaders.add(Integer.valueOf(2));
        assertFalse(fragmentFailure.installer.install(prepared(1L, 3L, 2L),
            1L, 2L, 3L).isInstalled());
        assertEquals(2, fragmentFailure.driver.deletedShaders.size());
        assertTrue(fragmentFailure.driver.deletedPrograms.isEmpty());

        Fixture linkFailure = new Fixture(2, 512 * 1024L);
        linkFailure.driver.linkPasses = false;
        assertFalse(linkFailure.installer.install(prepared(1L, 3L, 3L),
            1L, 2L, 3L).isInstalled());
        assertEquals(2, linkFailure.driver.deletedShaders.size());
        assertEquals(Collections.singleton(Integer.valueOf(100)),
            linkFailure.driver.deletedPrograms);

        Fixture budgetFailure = new Fixture(2, 1L);
        assertFalse(budgetFailure.installer.install(prepared(1L, 3L, 4L),
            1L, 2L, 3L).isInstalled());
        assertTrue("hard GPU rejection must occur before native compilation",
            budgetFailure.driver.deletedShaders.isEmpty());
        assertTrue(budgetFailure.driver.deletedPrograms.isEmpty());
        assertEquals(0, budgetFailure.ledger.snapshot().getLive());
        assertEquals(0L, budgetFailure.budget.snapshot().getGpuUsed());
    }

    @Test
    public void unknownCreateOutcomesPoisonOnlyTheirNativeObjectBudget() {
        Fixture shaderCreate = new Fixture(2, 512 * 1024L);
        shaderCreate.driver.throwCreateShaderCall = 1;
        assertFalse(shaderCreate.installer.install(prepared(1L, 3L, 40L),
            1L, 2L, 3L).isInstalled());
        assertEquals(8L * 1024L,
            shaderCreate.budget.snapshot().getGpuUsed());
        assertTrue(shaderCreate.driver.deletedShaders.isEmpty());
        assertTrue(shaderCreate.driver.deletedPrograms.isEmpty());

        Fixture programCreate = new Fixture(2, 512 * 1024L);
        programCreate.driver.throwCreateProgram = true;
        assertFalse(programCreate.installer.install(prepared(1L, 3L, 41L),
            1L, 2L, 3L).isInstalled());
        assertEquals(64L * 1024L,
            programCreate.budget.snapshot().getGpuUsed());
        assertEquals(2, programCreate.driver.deletedShaders.size());
        assertTrue(programCreate.driver.deletedPrograms.isEmpty());
    }

    @Test
    public void unknownDeleteOutcomesKeepTheirReservationPoisoned() {
        Fixture shaderDelete = new Fixture(2, 512 * 1024L);
        shaderDelete.driver.failedShaders.add(Integer.valueOf(1));
        shaderDelete.driver.throwDeleteShaders.add(Integer.valueOf(1));
        try {
            shaderDelete.installer.install(prepared(1L, 3L, 42L),
                1L, 2L, 3L);
            throw new AssertionError("expected shader delete failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected shader delete failure", expected.getMessage());
        }
        assertEquals(8L * 1024L,
            shaderDelete.budget.snapshot().getGpuUsed());

        Fixture programDelete = new Fixture(2, 512 * 1024L);
        programDelete.driver.linkPasses = false;
        programDelete.driver.throwDeleteProgram = true;
        try {
            programDelete.installer.install(prepared(1L, 3L, 43L),
                1L, 2L, 3L);
            throw new AssertionError("expected program delete failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected program delete failure", expected.getMessage());
        }
        assertEquals(64L * 1024L,
            programDelete.budget.snapshot().getGpuUsed());
        assertEquals(2, programDelete.driver.deletedShaders.size());
    }

    @Test
    public void cleanupFailureCannotMaskFatalShaderCreationFailure() {
        Fixture fixture = new Fixture(2, 512 * 1024L);
        OutOfMemoryError fatal = new OutOfMemoryError(
            "injected retained shader OOME");
        fixture.driver.throwCreateShaderCall = 2;
        fixture.driver.createShaderFatal = fatal;
        fixture.driver.throwDeleteShaders.add(Integer.valueOf(1));

        try {
            fixture.installer.install(prepared(1L, 3L, 45L),
                1L, 2L, 3L);
            throw new AssertionError("expected retained shader OOME");
        } catch (OutOfMemoryError expected) {
            assertSame(fatal, expected);
        }
        assertEquals(1, fatal.getSuppressed().length);
        assertEquals("injected shader delete failure",
            fatal.getSuppressed()[0].getMessage());
    }

    @Test
    public void temporaryShaderBudgetRejectsBeforeAnyNativeAllocation() {
        Fixture fixture = new Fixture(2, 64L * 1024L);
        assertFalse(fixture.installer.install(prepared(1L, 3L, 44L),
            1L, 2L, 3L).isInstalled());
        assertTrue(fixture.driver.events.isEmpty());
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
    }

    @Test
    public void rejectsStaleContextCapacityAndClosedInstallerFailClosed() {
        Fixture fixture = new Fixture(1, 512 * 1024L);
        PreparedShaderPermutation first = prepared(1L, 3L, 5L);
        assertTrue(fixture.installer.install(first, 1L, 2L, 3L).isInstalled());

        assertFalse("same key in a different context must require graph reset",
            fixture.installer.install(first, 1L, 9L, 3L).isInstalled());
        assertEquals(1, fixture.installer.size());
        assertFalse(fixture.installer.install(prepared(1L, 3L, 6L),
            1L, 2L, 3L).isInstalled());
        assertTrue(fixture.installer.isSaturated());

        fixture.installer.close(true);
        assertFalse(fixture.installer.install(prepared(1L, 3L, 7L),
            1L, 2L, 3L).isInstalled());
        assertEquals(1, fixture.ledger.collect(2L, 8));
    }

    @Test
    public void missingRetirementFenceNeverTriggersImmediateDelete() {
        final AtomicInteger deletes = new AtomicInteger();
        CacheBudget budget = new CacheBudget(1L, 1L, 512 * 1024L);
        ResourceLedger ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(),
            budget, new ResourceLedger.Destroyer() {
                @Override public void destroy(RenderResourceKind kind, int nativeId) {
                    deletes.incrementAndGet();
                }
            }, 8);
        FakeDriver driver = new FakeDriver();
        LwjglShaderProgramInstaller installer = new LwjglShaderProgramInstaller(
            RenderThreadGuard.captureCurrent(), ledger, 2, driver,
            new LwjglShaderProgramInstaller.FenceFactory() {
                @Override public ResourceLedger.RetirementFence create() {
                    throw new IllegalStateException("injected Fence failure");
                }
        });
        assertTrue(installer.install(prepared(1L, 3L, 8L),
            1L, 2L, 3L).isInstalled());
        try {
            installer.reset(true);
            throw new AssertionError("expected retirement Fence failure");
        } catch (IllegalStateException expected) {
            assertEquals("injected Fence failure", expected.getMessage());
        }
        assertEquals(0, ledger.collect(2L, 8));
        assertEquals("busy/unknown use must not be deleted", 0, deletes.get());
        ledger.destroyAll(2L);
        assertEquals(1, deletes.get());
    }

    @Test
    public void geometryShaderIsAttachedAndEveryStageIsCleaned() {
        Fixture fixture = new Fixture(2, 512 * 1024L);
        PreparedShaderPermutation prepared = preparedGeometry(1L, 3L, 9L);
        LwjglShaderProgramInstaller.InstallResult result = fixture.installer.install(
            prepared, 1L, 2L, 3L);
        assertTrue(result.getDetail(), result.isInstalled());
        assertEquals(java.util.Arrays.asList(Integer.valueOf(35633),
            Integer.valueOf(0x8DD9), Integer.valueOf(35632)),
            fixture.driver.createdTypes);
        assertEquals(3, fixture.driver.deletedShaders.size());
        assertEquals(3, fixture.driver.attachedShaders.size());

        Fixture geometryFailure = new Fixture(2, 512 * 1024L);
        geometryFailure.driver.failedShaders.add(Integer.valueOf(2));
        assertFalse(geometryFailure.installer.install(
            preparedGeometry(1L, 3L, 10L), 1L, 2L, 3L).isInstalled());
        assertEquals(2, geometryFailure.driver.deletedShaders.size());
        assertTrue(geometryFailure.driver.deletedPrograms.isEmpty());
    }

    @Test
    public void temporaryCompileGateAlwaysPrecedesRetainedObjectCreation() {
        final Fixture fixture = new Fixture(2, 512 * 1024L);
        final List<String> order = fixture.driver.events;
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(registry);
        ShaderCompilationDriver compiler = new ShaderCompilationDriver() {
            @Override public ShaderCompilationResult compile(String vertex,
                                                              String fragment) {
                throw new AssertionError("geometry-aware compile overload required");
            }
            @Override public ShaderCompilationResult compile(String vertex,
                                                              String geometry,
                                                              String fragment,
                                                              int legacyProgram) {
                order.add("temporary-compile:" + legacyProgram);
                return new ShaderCompilationResult(true, "linked");
            }
        };
        ShaderCompileInstallGate.Outcome outcome = ShaderCompileInstallGate.execute(
            prepared(1L, 3L, 11L), pipeline, compiler, fixture.installer,
            77, 1L, 2L, 3L);
        assertTrue(outcome.getDetail(), outcome.isInstalled());
        assertEquals("temporary-compile:77", order.get(0));
        assertEquals("retained-create-shader", order.get(1));

        Fixture rejected = new Fixture(2, 512 * 1024L);
        ShaderCompileInstallGate.Outcome failed = ShaderCompileInstallGate.execute(
            prepared(1L, 3L, 12L),
            new ShaderCertificationPipeline(new ShaderCertificationRegistry(16)),
            new ShaderCompilationDriver() {
                @Override public ShaderCompilationResult compile(String vertex,
                                                                  String fragment) {
                    return new ShaderCompilationResult(false, "rejected");
                }
            }, rejected.installer, 0, 1L, 2L, 3L);
        assertFalse(failed.isInstalled());
        assertTrue(rejected.driver.events.isEmpty());
    }

    @Test
    public void retainedLinkMirrorsAndVerifiesLegacyInterfaceAndIdGeneration() {
        Fixture fixture = new Fixture(2, 512 * 1024L);
        PreparedShaderPermutation geometry = preparedGeometry(1L, 3L, 13L);
        assertTrue(fixture.installer.install(geometry, 91, 1L, 2L, 3L)
            .isInstalled());
        int mirror = fixture.driver.events.indexOf("mirror:91:true");
        int link = fixture.driver.events.indexOf("retained-link");
        int verify = fixture.driver.events.indexOf("verify:91:true");
        assertTrue(mirror >= 0 && mirror < link && link < verify);
        int compiledShaders = fixture.driver.nextShader.get();
        assertFalse(fixture.installer.install(geometry, 92, 1L, 2L, 3L)
            .isInstalled());
        assertEquals("identity mismatch must not relink", compiledShaders,
            fixture.driver.nextShader.get());
    }

    @Test
    public void failureAfterMapPutRollsBackThePublishedIdentityBeforeRetirement() {
        final Fixture fixture = new Fixture(2, 512 * 1024L,
            new LwjglShaderProgramInstaller.PublicationHook() {
                @Override public void afterPut() {
                    throw new IllegalStateException(
                        "injected post-publication failure");
                }
            });

        LwjglShaderProgramInstaller.InstallResult result = fixture.installer.install(
            prepared(1L, 3L, 14L), 1L, 2L, 3L);
        assertFalse(result.isInstalled());
        assertEquals(0, fixture.installer.size());
        assertEquals(0, fixture.ledger.snapshot().getLive());
        assertEquals(1, fixture.ledger.collect(2L, 8));
        assertTrue(fixture.driver.deletedPrograms.contains(Integer.valueOf(100)));
        assertEquals(0L, fixture.budget.snapshot().getGpuUsed());
    }

    private static PreparedShaderPermutation prepared(long resources, long shaders,
                                                       long sourceHash) {
        ShaderPermutationKey key = new ShaderPermutationKey("pack", "gbuffers_terrain",
            "base", resources, shaders, sourceHash);
        PreprocessedShader vertex = new PreprocessedShader(
            "void main(){gl_Position=gl_Vertex;}", Collections.<String>emptyList(), 0);
        PreprocessedShader fragment = new PreprocessedShader(
            "void main(){gl_FragColor=vec4(1.0);}",
            Collections.<String>emptyList(), 0);
        return new PreparedShaderPermutation(key, vertex, fragment,
            new ShaderPackProperties(Collections.<String, String>emptyMap(), 0));
    }

    private static PreparedShaderPermutation preparedGeometry(long resources,
                                                               long shaders,
                                                               long sourceHash) {
        ShaderPermutationKey key = new ShaderPermutationKey("pack",
            "gbuffers_terrain", "geometry", resources, shaders, sourceHash);
        PreprocessedShader vertex = new PreprocessedShader(
            "void main(){gl_Position=gl_Vertex;}",
            Collections.<String>emptyList(), 0);
        PreprocessedShader geometry = new PreprocessedShader(
            "void main(){}", Collections.<String>emptyList(), 0);
        PreprocessedShader fragment = new PreprocessedShader(
            "void main(){gl_FragColor=vec4(1.0);}",
            Collections.<String>emptyList(), 0);
        return new PreparedShaderPermutation(key, vertex, geometry, fragment,
            new ShaderPackProperties(Collections.<String, String>emptyMap(), 0));
    }

    private static final class Fixture {
        private final CacheBudget budget;
        private final ResourceLedger ledger;
        private final FakeDriver driver = new FakeDriver();
        private final LwjglShaderProgramInstaller installer;

        private Fixture(int maximumPrograms, long gpuBudget) {
            this(maximumPrograms, gpuBudget,
                LwjglShaderProgramInstaller.PublicationHook.NONE);
        }

        private Fixture(int maximumPrograms, long gpuBudget,
                        LwjglShaderProgramInstaller.PublicationHook publicationHook) {
            budget = new CacheBudget(1L, 1L, gpuBudget);
            ledger = new ResourceLedger(RenderThreadGuard.captureCurrent(), budget,
                new ResourceLedger.Destroyer() {
                    @Override public void destroy(RenderResourceKind kind, int nativeId) {
                        driver.deleteProgram(nativeId);
                    }
                }, 16);
            installer = new LwjglShaderProgramInstaller(
                RenderThreadGuard.captureCurrent(), ledger, maximumPrograms, driver,
                new LwjglShaderProgramInstaller.FenceFactory() {
                    @Override public ResourceLedger.RetirementFence create() {
                        return new ResourceLedger.RetirementFence() {
                            @Override public boolean isSignaled() { return true; }
                            @Override public void destroy() { }
                        };
                    }
                }, publicationHook);
        }
    }

    private static final class FakeDriver
        implements LwjglShaderProgramInstaller.ProgramDriver {
        private final AtomicInteger nextShader = new AtomicInteger(1);
        private final AtomicInteger nextProgram = new AtomicInteger(100);
        private final Set<Integer> failedShaders = new HashSet<Integer>();
        private final Set<Integer> deletedShaders = new HashSet<Integer>();
        private final Set<Integer> deletedPrograms = new HashSet<Integer>();
        private final List<Integer> createdTypes = new ArrayList<Integer>();
        private final List<Integer> attachedShaders = new ArrayList<Integer>();
        private final List<String> events = new ArrayList<String>();
        private final Set<Integer> throwDeleteShaders = new HashSet<Integer>();
        private boolean linkPasses = true;
        private int createShaderCalls;
        private int throwCreateShaderCall;
        private Error createShaderFatal;
        private boolean throwCreateProgram;
        private boolean throwDeleteProgram;

        @Override public int createShader(int type) {
            events.add("retained-create-shader");
            createdTypes.add(Integer.valueOf(type));
            createShaderCalls++;
            if (createShaderCalls == throwCreateShaderCall) {
                if (createShaderFatal != null) throw createShaderFatal;
                throw new IllegalStateException(
                    "injected shader create failure");
            }
            return nextShader.getAndIncrement();
        }
        @Override public void shaderSource(int shader, String source) { }
        @Override public void compileShader(int shader) { }
        @Override public int shaderStatus(int shader) {
            return failedShaders.contains(Integer.valueOf(shader)) ? 0 : 1;
        }
        @Override public String shaderLog(int shader, int maximumChars) {
            return "shader-log";
        }
        @Override public void deleteShader(int shader) {
            if (throwDeleteShaders.contains(Integer.valueOf(shader))) {
                throw new IllegalStateException(
                    "injected shader delete failure");
            }
            deletedShaders.add(Integer.valueOf(shader));
        }
        @Override public int createProgram() {
            if (throwCreateProgram) throw new IllegalStateException(
                "injected program create failure");
            return nextProgram.getAndIncrement();
        }
        @Override public void attachShader(int program, int shader) {
            attachedShaders.add(Integer.valueOf(shader));
        }
        @Override public void mirrorLinkInterface(int program, int legacyProgram,
                                                  boolean geometry) {
            events.add("mirror:" + legacyProgram + ":" + geometry);
        }
        @Override public void linkProgram(int program) {
            events.add("retained-link");
        }
        @Override public void verifyLinkInterface(int program, int legacyProgram,
                                                  boolean geometry) {
            events.add("verify:" + legacyProgram + ":" + geometry);
        }
        @Override public int programStatus(int program) { return linkPasses ? 1 : 0; }
        @Override public String programLog(int program, int maximumChars) {
            return "program-log";
        }
        @Override public void deleteProgram(int program) {
            if (throwDeleteProgram) throw new IllegalStateException(
                "injected program delete failure");
            deletedPrograms.add(Integer.valueOf(program));
        }
    }
}

package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
/** Enforces temporary compile/link certification before any retained GL name. */
public final class ShaderCompileInstallGate {
    private ShaderCompileInstallGate() {
    }

    public static Outcome execute(PreparedShaderPermutation prepared,
                                  ShaderCertificationPipeline pipeline,
                                  ShaderCompilationDriver compiler,
                                  LwjglShaderProgramInstaller installer,
                                  int legacyProgramId,
                                  long resourceGeneration,
                                  long contextGeneration,
                                  long shaderGeneration) {
        if (prepared == null || pipeline == null || compiler == null
            || installer == null || legacyProgramId < 0
            || resourceGeneration <= 0L || contextGeneration <= 0L
            || shaderGeneration <= 0L) {
            throw new IllegalArgumentException("shader compile/install gate");
        }
        ShaderValidationPolicy.Result policy = ShaderValidationPolicy.inspect(prepared);
        if (!policy.isSafe()) {
            return new Outcome(false, false, false, false, 0,
                policy.getDetail());
        }
        ShaderCertificationPipeline.CompileOutcome compile =
            pipeline.compileDetailed(prepared, compiler, legacyProgramId);
        if (!compile.isPassed()) {
            return new Outcome(true, false, false,
                compile.isInfrastructureFailure(), 0, compile.getDetail());
        }
        try {
            LwjglShaderProgramInstaller.InstallResult install = installer.install(
                prepared, legacyProgramId, resourceGeneration, contextGeneration,
                shaderGeneration);
            return new Outcome(true, true, install.isInstalled(), false,
                install.getProgramId(), install.getDetail());
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            return new Outcome(true, true, false, true, 0, compact(error));
        }
    }

    private static String compact(Throwable error) {
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    public static final class Outcome {
        private final boolean compileAttempted;
        private final boolean compiled;
        private final boolean installed;
        private final boolean infrastructureFailure;
        private final int programId;
        private final String detail;

        private Outcome(boolean compileAttempted, boolean compiled,
                        boolean installed, boolean infrastructureFailure,
                        int programId, String detail) {
            this.compileAttempted = compileAttempted;
            this.compiled = compiled;
            this.installed = installed;
            this.infrastructureFailure = infrastructureFailure;
            this.programId = programId;
            this.detail = detail == null ? "" : detail;
        }

        public boolean wasCompileAttempted() { return compileAttempted; }
        public boolean isCompiled() { return compiled; }
        public boolean isInstalled() { return installed; }
        public boolean isInfrastructureFailure() { return infrastructureFailure; }
        public int getProgramId() { return programId; }
        public String getDetail() { return detail; }
    }
}

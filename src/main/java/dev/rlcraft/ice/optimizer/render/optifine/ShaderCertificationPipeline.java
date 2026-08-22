package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.render.validation.ImageValidationResult;
import dev.rlcraft.ice.optimizer.render.validation.ShaderImageValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/** Bounded compile/state/attachment certification; all three gates are mandatory. */
public final class ShaderCertificationPipeline {
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ATTACHMENTS = 16;
    private static final long MAX_IMAGE_BYTES = 128L * 1024L * 1024L;
    private final ShaderCertificationRegistry registry;
    private final ShaderPreprocessor preprocessor;
    private final ShaderPackPropertiesParser propertiesParser;
    private final ShaderProgramStateValidator stateValidator =
        new ShaderProgramStateValidator();
    private final ShaderImageValidator imageValidator = new ShaderImageValidator();

    public ShaderCertificationPipeline(ShaderCertificationRegistry registry) {
        this(registry, new ShaderPreprocessor(),
            new ShaderPackPropertiesParser());
    }

    ShaderCertificationPipeline(ShaderCertificationRegistry registry,
                                ShaderPreprocessor preprocessor,
                                ShaderPackPropertiesParser propertiesParser) {
        if (registry == null || preprocessor == null || propertiesParser == null) {
            throw new IllegalArgumentException("shader certification dependencies");
        }
        this.registry = registry;
        this.preprocessor = preprocessor;
        this.propertiesParser = propertiesParser;
    }

    public PreparedShaderPermutation prepare(String packId, String program,
                                             String permutation,
                                             long resourceGeneration,
                                             long shaderGeneration,
                                             String vertexPath,
                                             String fragmentPath,
                                             String propertiesSource,
                                             ShaderSourceRepository repository) {
        validateIdentifier(packId, "pack", 256);
        validateIdentifier(program, "program", 256);
        if (permutation == null || permutation.isEmpty()
            || permutation.length() > 1024 || permutation.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shader permutation");
        }
        PreprocessedShader vertex = preprocessor.preprocess("shaders",
            vertexPath, repository);
        PreprocessedShader fragment = preprocessor.preprocess("shaders",
            fragmentPath, repository);
        int bytes = checkedSourceBytes(vertex.getSource(), null,
            fragment.getSource());
        if (bytes <= 0) throw new IllegalArgumentException("empty shader sources");
        ShaderPackProperties properties = propertiesParser.parse(
            propertiesSource == null ? "" : propertiesSource);
        long hash = sourceHash(vertex.getSource(), null, fragment.getSource(),
            properties.asMap());
        ShaderPermutationKey key = new ShaderPermutationKey(packId, program,
            permutation, resourceGeneration, shaderGeneration, hash);
        return new PreparedShaderPermutation(key, vertex, fragment, properties);
    }

    /**
     * Accepts the exact include/option-resolved text already submitted by
     * OptiFine.  Paths are retained as provenance only; no host file access is
     * performed and all normal source/property limits still apply.
     */
    public PreparedShaderPermutation prepareResolved(String packId,
                                                      String program,
                                                      String permutation,
                                                      long resourceGeneration,
                                                      long shaderGeneration,
                                                      String vertexPath,
                                                      String vertexSource,
                                                      String geometryPath,
                                                      String geometrySource,
                                                      String fragmentPath,
                                                      String fragmentSource,
                                                      String propertiesSource) {
        validateIdentifier(packId, "pack", 256);
        validateIdentifier(program, "program", 256);
        if (permutation == null || permutation.isEmpty()
            || permutation.length() > 1024 || permutation.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shader permutation");
        }
        validateResolvedPath(vertexPath, "vertex");
        validateResolvedPath(fragmentPath, "fragment");
        if (geometrySource != null) validateResolvedPath(geometryPath, "geometry");
        int bytes = checkedSourceBytes(vertexSource, geometrySource,
            fragmentSource);
        if (bytes <= 0) throw new IllegalArgumentException("empty shader sources");
        ShaderPackProperties properties = propertiesParser.parse(
            propertiesSource == null ? "" : propertiesSource);
        PreprocessedShader vertex = new PreprocessedShader(vertexSource,
            Collections.singletonList(vertexPath), 0);
        PreprocessedShader geometry = geometrySource == null ? null
            : new PreprocessedShader(geometrySource,
                Collections.singletonList(geometryPath), 0);
        PreprocessedShader fragment = new PreprocessedShader(fragmentSource,
            Collections.singletonList(fragmentPath), 0);
        long hash = sourceHash(vertexSource, geometrySource, fragmentSource,
            properties.asMap());
        ShaderPermutationKey key = new ShaderPermutationKey(packId, program,
            permutation, resourceGeneration, shaderGeneration, hash);
        return new PreparedShaderPermutation(key, vertex, geometry, fragment,
            properties);
    }

    public boolean compile(PreparedShaderPermutation prepared,
                           ShaderCompilationDriver driver) {
        return compile(prepared, driver, 0);
    }

    public boolean compile(PreparedShaderPermutation prepared,
                           ShaderCompilationDriver driver,
                           int legacyProgramId) {
        return compileDetailed(prepared, driver, legacyProgramId).isPassed();
    }

    public CompileOutcome compileDetailed(PreparedShaderPermutation prepared,
                                          ShaderCompilationDriver driver,
                                          int legacyProgramId) {
        if (prepared == null || driver == null) throw new IllegalArgumentException(
            "shader compile");
        String geometry = prepared.getGeometry() == null ? null
            : prepared.getGeometry().getSource();
        checkedSourceBytes(prepared.getVertex().getSource(), geometry,
            prepared.getFragment().getSource());
        ShaderCompilationResult result;
        try {
            result = driver.compile(prepared.getVertex().getSource(), geometry,
                prepared.getFragment().getSource(), legacyProgramId);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            String detail = error.getClass().getSimpleName() + ": "
                + safeMessage(error);
            registry.recordCompile(prepared.getKey(), false,
                detail);
            return new CompileOutcome(false, true, detail);
        }
        boolean passed = result != null && result.isLinked();
        String detail = result == null ? "compiler returned null" : result.getLog();
        registry.recordCompile(prepared.getKey(), passed,
            detail);
        return new CompileOutcome(passed, result == null, detail);
    }

    public ShaderStateValidationResult validateState(ShaderPermutationKey key,
                                                       OptifineProgramState legacy,
                                                       OptifineProgramState modern) {
        if (key == null) throw new IllegalArgumentException("shader key");
        ShaderStateValidationResult result = stateValidator.compare(legacy, modern);
        registry.recordStateValidation(key, result.isEquivalent());
        return result;
    }

    public boolean validateImages(ShaderPermutationKey key,
                                  Map<String, byte[]> legacy,
                                  Map<String, byte[]> modern,
                                  int allowedComponentDelta) {
        if (key == null) throw new IllegalArgumentException("shader key");
        boolean passed = validAttachments(legacy) && validAttachments(modern)
            && legacy.keySet().equals(modern.keySet());
        if (passed) {
            for (Map.Entry<String, byte[]> entry : legacy.entrySet()) {
                ImageValidationResult result = imageValidator.compare(entry.getValue(),
                    modern.get(entry.getKey()), allowedComponentDelta);
                if (!result.isEquivalent()) {
                    passed = false;
                    break;
                }
            }
        }
        registry.recordImageValidation(key, passed);
        return passed;
    }

    private static boolean validAttachments(Map<String, byte[]> attachments) {
        if (attachments == null || attachments.isEmpty()
            || attachments.size() > MAX_ATTACHMENTS) return false;
        long bytes = 0L;
        for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
            String name = entry.getKey();
            byte[] value = entry.getValue();
            if (name == null || name.isEmpty() || name.length() > 128
                || value == null) return false;
            bytes += value.length;
            if (bytes > MAX_IMAGE_BYTES) return false;
        }
        return true;
    }

    private static int checkedSourceBytes(String vertex, String geometry,
                                          String fragment) {
        if (vertex == null || fragment == null) {
            throw new IllegalArgumentException("shader source");
        }
        if (vertex.indexOf('\0') >= 0 || fragment.indexOf('\0') >= 0
            || geometry != null && geometry.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL in shader source");
        }
        long bytes = (long) vertex.getBytes(StandardCharsets.UTF_8).length
            + fragment.getBytes(StandardCharsets.UTF_8).length
            + (geometry == null ? 0L
                : geometry.getBytes(StandardCharsets.UTF_8).length);
        if (bytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("shader source byte limit exceeded");
        }
        return (int) bytes;
    }

    private static long sourceHash(String vertex, String geometry,
                                   String fragment,
                                   Map<String, String> properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, vertex);
            if (geometry != null) {
                update(digest, "\u0000geometry\u0000");
                update(digest, geometry);
            }
            update(digest, "\u0000fragment\u0000");
            update(digest, fragment);
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                update(digest, "\u0000" + entry.getKey() + "=" + entry.getValue());
            }
            byte[] value = digest.digest();
            long result = 0L;
            for (int index = 0; index < 8; index++) {
                result = (result << 8) | (value[index] & 255L);
            }
            return result;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateIdentifier(String value, String detail,
                                           int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
            || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shader " + detail);
        }
    }

    private static void validateResolvedPath(String value, String stage) {
        if (value == null || value.isEmpty() || value.length() > 1024
            || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("shader " + stage + " path");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "";
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    public static final class CompileOutcome {
        private final boolean passed;
        private final boolean infrastructureFailure;
        private final String detail;

        private CompileOutcome(boolean passed, boolean infrastructureFailure,
                               String detail) {
            this.passed = passed;
            this.infrastructureFailure = infrastructureFailure;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isPassed() { return passed; }
        public boolean isInfrastructureFailure() {
            return infrastructureFailure;
        }
        public String getDetail() { return detail; }
    }
}

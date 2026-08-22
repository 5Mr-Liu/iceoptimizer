package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.validation.ImageValidationResult;
import dev.rlcraft.ice.optimizer.render.validation.ShaderImageValidator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class ShaderBridgeTest {
    @Test
    public void expandsConfinedIncludesAndCountsBoundedMacros() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("shaders/main.vsh", "#define ICE_TEST 1\n#include \"lib/common.glsl\"\nvoid main(){}\n");
        sources.put("shaders/lib/common.glsl", "vec3 common(){return vec3(1.0);}\n");
        PreprocessedShader result = new ShaderPreprocessor().preprocess(
            "shaders/main.vsh", new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return sources.get(normalizedPath);
                }
            });
        assertTrue(result.getSource().contains("vec3 common"));
        assertEquals(2, result.getDependencies().size());
        assertEquals(1, result.getMacroCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncludeTraversalOutsideShaderPack() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("main.vsh", "#include \"../secret.txt\"\n");
        new ShaderPreprocessor().preprocess("main.vsh", new ShaderSourceRepository() {
            @Override public String load(String normalizedPath) { return sources.get(normalizedPath); }
        });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncludeCycles() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("a", "#include \"b\"\n");
        sources.put("b", "#include \"a\"\n");
        new ShaderPreprocessor().preprocess("a", new ShaderSourceRepository() {
            @Override public String load(String normalizedPath) { return sources.get(normalizedPath); }
        });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNulBeforePassingSourceToTheCompiler() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("main.vsh", "void main(){}\0ignored");
        new ShaderPreprocessor().preprocess("main.vsh",
            new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return sources.get(normalizedPath);
                }
            });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedUnicodeBeforeShaderExpansion() {
        new ShaderPreprocessor().preprocess("main.vsh",
            new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return "void main(){}\uD800";
                }
            });
    }

    @Test
    public void optifineRootIncludesStayInsideTheShadersDirectory() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("shaders/world/main.vsh", "#include \"/lib/common.glsl\"\n");
        sources.put("shaders/lib/common.glsl", "void common(){}\n");
        PreprocessedShader result = new ShaderPreprocessor().preprocess(
            "shaders", "shaders/world/main.vsh", new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return sources.get(normalizedPath);
                }
            });
        assertTrue(result.getSource().contains("common"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRelativeEscapeFromTheConfiguredShaderRoot() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("shaders/world/main.vsh", "#include \"../../outside\"\n");
        new ShaderPreprocessor().preprocess("shaders", "shaders/world/main.vsh",
            new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return sources.get(normalizedPath);
                }
            });
    }

    @Test
    public void parsesBoundedShaderPropertiesWithJavaCompatibleEscapes() {
        ShaderPackProperties properties = new ShaderPackPropertiesParser().parse(
            "# comment\nprogram.gbuffers_terrain.enabled=true\n"
                + "key\\ with = first\nkey\\ with=last\n"
                + "continued=one\\\n  two\nunicode=\\u0041\n");
        assertEquals("true", properties.get(
            "program.gbuffers_terrain.enabled"));
        assertEquals("last", properties.get("key with"));
        assertEquals("onetwo", properties.get("continued"));
        assertEquals("A", properties.get("unicode"));
        assertEquals(1, properties.getPermutationDirectives());
    }

    @Test(expected = IllegalArgumentException.class)
    public void boundsShaderPermutationDirectives() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 17; index++) {
            source.append("program.p").append(index).append("=true\n");
        }
        new ShaderPackPropertiesParser(65536, 64, 16, 128, 128, 4)
            .parse(source.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void boundsPropertyContinuationChains() {
        new ShaderPackPropertiesParser(65536, 64, 16, 128, 128, 2)
            .parse("value=a\\\n b\\\n c\\\n d\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void boundsShaderPropertyUtf8WithoutMaterializingAByteArray() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 600; index++) source.append('\u20ac');
        new ShaderPackPropertiesParser(1024, 64, 16, 128, 2048, 4)
            .parse(source.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnpairedSurrogateCreatedByPropertyEscape() {
        new ShaderPackPropertiesParser().parse("value=\\uD800\n");
    }

    @Test
    public void acceptsScalarPairCreatedByPropertyEscapes() {
        ShaderPackProperties properties = new ShaderPackPropertiesParser().parse(
            "value=\\uD83D\\uDE00\n");
        assertEquals("\uD83D\uDE00", properties.get("value"));
    }

    @Test
    public void blankPhysicalLineEndsAJavaPropertiesContinuation() {
        ShaderPackProperties properties = new ShaderPackPropertiesParser().parse(
            "continued=one\\\n   \nnext=value\n");
        assertEquals("one", properties.get("continued"));
        assertEquals("value", properties.get("next"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedDirectPermutationKeys() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 1025; index++) value.append('x');
        new ShaderPermutationKey("pack", "program", value.toString(),
            1L, 1L, 1L);
    }

    @Test
    public void compilerLogsAreBoundedAtTheValueBoundary() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 20000; index++) value.append('x');
        assertEquals(16384,
            new ShaderCompilationResult(false, value.toString()).getLog().length());
    }

    @Test
    public void nativeShaderRequiresCompileStateAndImageCertification() {
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderPermutationKey key = new ShaderPermutationKey("pack", "gbuffers_terrain",
            "main", 1L, 1L, 7L);
        OptifineShaderBackendSelector selector = new OptifineShaderBackendSelector(registry);
        assertEquals(RenderBackendId.OF_COMPAT_REGION,
            selector.select(true, true, key, false, false, true));
        registry.recordCompile(key, true, "ok");
        registry.recordStateValidation(key, true);
        assertFalse(registry.isCertified(key));
        registry.recordImageValidation(key, true);
        assertTrue(registry.isCertified(key));
        assertEquals(RenderBackendId.OF_COMPAT_REGION,
            selector.select(true, true, key, false, true, true));
        assertEquals(RenderBackendId.OF_COMPAT_REGION,
            selector.select(true, true, key, true, false, true));
        assertEquals(RenderBackendId.ICE_NATIVE,
            selector.select(true, true, key, true, true, true));

        ShaderImageValidator validator = new ShaderImageValidator();
        ImageValidationResult oneLsb = validator.compare(new byte[] { 1, 2 },
            new byte[] { 2, 3 }, 1);
        assertTrue(oneLsb.isEquivalent());
        assertFalse(validator.compare(new byte[] { 1 }, new byte[] { 3 }, 1).isEquivalent());
    }

    @Test
    public void failedPermutationIsStickyAndCapacitySaturatesFailClosed() {
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderPermutationKey first = null;
        for (int index = 0; index < 16; index++) {
            ShaderPermutationKey key = new ShaderPermutationKey("pack", "p" + index,
                "main", 1L, 1L, index);
            if (first == null) first = key;
            registry.recordCompile(key, true, "ok");
            registry.recordStateValidation(key, true);
            registry.recordImageValidation(key, true);
            assertTrue(registry.isCertified(key));
        }
        ShaderPermutationKey overflow = new ShaderPermutationKey("pack", "overflow",
            "main", 1L, 1L, 99L);
        registry.recordCompile(overflow, true, "ok");
        registry.recordStateValidation(overflow, true);
        registry.recordImageValidation(overflow, true);
        assertFalse(registry.isCertified(overflow));
        assertEquals(16, registry.entryCount());
        assertTrue(registry.isSaturated());
        assertTrue(registry.isCertified(first));

        registry.invalidate();
        ShaderPermutationKey failed = new ShaderPermutationKey("pack", "failed",
            "main", 2L, 2L, 100L);
        registry.recordCompile(failed, false, "failed");
        registry.recordCompile(failed, true, "retry");
        registry.recordStateValidation(failed, true);
        registry.recordImageValidation(failed, true);
        assertFalse(registry.isCertified(failed));
        assertFalse(registry.isSaturated());
    }

    @Test
    public void pipelineRequiresCompileLogicalFboStateAndEveryAttachment() {
        final Map<String, String> sources = new HashMap<String, String>();
        sources.put("shaders/gbuffers_terrain.vsh", "void main(){}\n");
        sources.put("shaders/gbuffers_terrain.fsh", "void main(){}\n");
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(registry);
        PreparedShaderPermutation prepared = pipeline.prepare("pack",
            "gbuffers_terrain", "main", 1L, 2L,
            "shaders/gbuffers_terrain.vsh", "shaders/gbuffers_terrain.fsh",
            "program.gbuffers_terrain.enabled=true\n",
            new ShaderSourceRepository() {
                @Override public String load(String normalizedPath) {
                    return sources.get(normalizedPath);
                }
            });
        assertTrue(pipeline.compile(prepared, new ShaderCompilationDriver() {
            @Override public ShaderCompilationResult compile(String vertex,
                                                              String fragment) {
                return new ShaderCompilationResult(true, "linked");
            }
        }));

        ShaderFramebufferState framebuffer = new ShaderFramebufferState(
            1920, 1080, 0, 33190, new int[] { 32856, 34842 });
        OptifineProgramState legacy = state(11, 7, framebuffer);
        OptifineProgramState modern = state(99, 13, framebuffer);
        assertTrue(pipeline.validateState(prepared.getKey(), legacy, modern)
            .isEquivalent());
        Map<String, byte[]> legacyImages = new LinkedHashMap<String, byte[]>();
        Map<String, byte[]> modernImages = new LinkedHashMap<String, byte[]>();
        legacyImages.put("color0", new byte[] { 1, 2, 3, 4 });
        legacyImages.put("depth", new byte[] { 5, 6 });
        modernImages.put("color0", new byte[] { 1, 2, 3, 4 });
        modernImages.put("depth", new byte[] { 5, 6 });
        assertTrue(pipeline.validateImages(prepared.getKey(), legacyImages,
            modernImages, 0));
        assertTrue(registry.isCertified(prepared.getKey()));
    }

    @Test
    public void resolvedOptifineTextRetainsGeometryAndNeverUsesARepository() {
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(
            new ShaderCertificationRegistry(16));
        PreparedShaderPermutation prepared = pipeline.prepareResolved("pack",
            "gbuffers_terrain", "resolved", 4L, 5L,
            "world/terrain.vsh", "vertex-source",
            "world/terrain.gsh", "geometry-source",
            "world/terrain.fsh", "fragment-source",
            "program.gbuffers_terrain.enabled=true\n");
        assertEquals("vertex-source", prepared.getVertex().getSource());
        assertEquals("geometry-source", prepared.getGeometry().getSource());
        assertEquals("fragment-source", prepared.getFragment().getSource());
        assertEquals(java.util.Collections.singletonList("world/terrain.gsh"),
            prepared.getGeometry().getDependencies());
        assertEquals("true", prepared.getProperties().get(
            "program.gbuffers_terrain.enabled"));

        PreparedShaderPermutation withoutGeometry = pipeline.prepareResolved(
            "pack", "gbuffers_terrain", "resolved", 4L, 5L,
            "world/terrain.vsh", "vertex-source", null, null,
            "world/terrain.fsh", "fragment-source", "");
        assertTrue(withoutGeometry.getGeometry() == null);
        assertFalse(prepared.getKey().equals(withoutGeometry.getKey()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void resolvedOptifineTextRejectsNulBeforeNativeCompilation() {
        new ShaderCertificationPipeline(new ShaderCertificationRegistry(16))
            .prepareResolved("pack", "program", "resolved", 1L, 1L,
                "program.vsh", "void main(){}\0ignored", null, null,
                "program.fsh", "void main(){}", "");
    }

    @Test
    public void derivesExactG5DeferredAndShadowAttachmentLayouts() {
        ShaderFramebufferState deferred =
            OptifineProgramIntrospector.framebufferState(true, 1920, 1080,
                2, 3, new int[] { 32856, 34842, 1 });
        assertEquals(new ShaderFramebufferState(1920, 1080, 0, 6402,
            new int[] { 32856, 34842 }), deferred);
        ShaderFramebufferState shadow =
            OptifineProgramIntrospector.framebufferState(false, 2048, 2048,
                2, 2, null);
        assertEquals(new ShaderFramebufferState(2048, 2048, 0, 6402,
            new int[] { 6408, 6408 }), shadow);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsTruncatedGbufferFormatMetadata() {
        OptifineProgramIntrospector.framebufferState(true, 1920, 1080,
            2, 1, new int[] { 32856 });
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShaderFramebufferAttachmentByteBombs() {
        new ShaderFramebufferState(16384, 16384, 4, 6402,
            new int[] { 32856, 34842, 32856, 34842 });
    }

    @Test
    public void missingFboAttachmentMetadataCanNeverCertifyNative() {
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(registry);
        ShaderPermutationKey key = new ShaderPermutationKey("pack", "final",
            "main", 1L, 1L, 4L);
        OptifineProgramState legacy = state(1, 2, null);
        OptifineProgramState modern = state(3, 4, null);
        assertFalse(pipeline.validateState(key, legacy, modern).isEquivalent());
        registry.recordCompile(key, true, "ok");
        registry.recordImageValidation(key, true);
        assertFalse(registry.isCertified(key));
    }

    @Test
    public void missingOrDifferentAttachmentImagesFailTheWholePermutation() {
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(registry);
        ShaderPermutationKey key = new ShaderPermutationKey("pack", "composite",
            "main", 1L, 1L, 10L);
        Map<String, byte[]> legacy = new LinkedHashMap<String, byte[]>();
        Map<String, byte[]> modern = new LinkedHashMap<String, byte[]>();
        legacy.put("color0", new byte[] { 1, 2 });
        legacy.put("color1", new byte[] { 3, 4 });
        modern.put("color0", new byte[] { 1, 2 });
        assertFalse(pipeline.validateImages(key, legacy, modern, 0));
        modern.put("color1", new byte[] { 3, 5 });
        assertFalse(pipeline.validateImages(key, legacy, modern, 0));
    }

    @Test
    public void validationPolicyRejectsEveryExternalStorageFamilyAndFragmentOut() {
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(
            new ShaderCertificationRegistry(16));
        PreparedShaderPermutation image = pipeline.prepareResolved("pack", "p",
            "image", 1L, 1L, "p.vsh", "void main(){}", null, null,
            "p.fsh", "layout(binding=0) uniform uimage2D sink;\n"
                + "void main(){imageStore(sink, ivec2(0), uvec4(1));}", "");
        assertFalse(ShaderValidationPolicy.inspect(image).isSafe());

        PreparedShaderPermutation atomic = pipeline.prepareResolved("pack", "p",
            "atomic", 1L, 1L, "p.vsh", "void main(){}", null, null,
            "p.fsh", "void main(){atomicCounterIncrement(counter);}", "");
        assertFalse(ShaderValidationPolicy.inspect(atomic).isSafe());

        PreparedShaderPermutation output = pipeline.prepareResolved("pack", "p",
            "output", 1L, 1L, "p.vsh", "void main(){}", null, null,
            "p.fsh", "out vec4 color; void main(){color=vec4(1);}", "");
        assertFalse(ShaderValidationPolicy.inspect(output).isSafe());

        PreparedShaderPermutation ordinary = pipeline.prepareResolved("pack", "p",
            "ordinary", 1L, 1L, "p.vsh", "void main(){}", null, null,
            "p.fsh", "uniform vec4 bufferColor; void main(){gl_FragColor=bufferColor;}",
            "");
        assertTrue(ShaderValidationPolicy.inspect(ordinary).isSafe());
    }

    @Test
    public void compileOutcomeSeparatesShaderRejectionFromDriverFailureAndPassesId() {
        ShaderCertificationRegistry registry = new ShaderCertificationRegistry(16);
        ShaderCertificationPipeline pipeline = new ShaderCertificationPipeline(registry);
        PreparedShaderPermutation prepared = pipeline.prepareResolved("pack", "p",
            "base", 1L, 1L, "p.vsh", "void main(){}", null, null,
            "p.fsh", "void main(){}", "");
        final int[] observedId = { 0 };
        ShaderCertificationPipeline.CompileOutcome linked = pipeline.compileDetailed(
            prepared, new ShaderCompilationDriver() {
                @Override public ShaderCompilationResult compile(String vertex,
                                                                  String fragment) {
                    throw new AssertionError("legacy-id overload was not used");
                }
                @Override public ShaderCompilationResult compile(String vertex,
                                                                  String geometry,
                                                                  String fragment,
                                                                  int legacyProgram) {
                    observedId[0] = legacyProgram;
                    return new ShaderCompilationResult(true, "ok");
                }
            }, 73);
        assertTrue(linked.isPassed());
        assertFalse(linked.isInfrastructureFailure());
        assertEquals(73, observedId[0]);

        PreparedShaderPermutation failed = pipeline.prepareResolved("pack", "q",
            "failure", 1L, 1L, "q.vsh", "void main(){}", null, null,
            "q.fsh", "void main(){}", "");
        ShaderCertificationPipeline.CompileOutcome exception = pipeline.compileDetailed(
            failed, new ShaderCompilationDriver() {
                @Override public ShaderCompilationResult compile(String vertex,
                                                                  String fragment) {
                    throw new IllegalStateException("injected driver failure");
                }
            }, 0);
        assertFalse(exception.isPassed());
        assertTrue(exception.isInfrastructureFailure());
        assertTrue(registry.hasFailed(failed.getKey()));
    }

    @Test
    public void uniformMirrorDoesNotConfuseUintVectorsWithSamplerEnums() {
        assertEquals(1, LwjglShaderUniformMirror.typeComponentsForTest(36288));
        assertFalse(LwjglShaderUniformMirror.typeUnsignedForTest(36288));
        assertEquals(2, LwjglShaderUniformMirror.typeComponentsForTest(36294));
        assertTrue(LwjglShaderUniformMirror.typeUnsignedForTest(36294));
        assertEquals(4, LwjglShaderUniformMirror.typeComponentsForTest(36296));
        assertTrue(LwjglShaderUniformMirror.typeUnsignedForTest(36296));
        assertEquals(1, LwjglShaderUniformMirror.typeComponentsForTest(37128));
    }

    private static OptifineProgramState state(int program, int framebuffer,
                                               ShaderFramebufferState layout) {
        return new OptifineProgramState("gbuffers_terrain", "GBUFFERS",
            program, framebuffer, layout, new int[] { 36064, 36065 }, 0, 1,
            new OptifineProgramState.AlphaState(true, 516, 0.1F),
            new OptifineProgramState.BlendState(true, 770, 771, 1, 771),
            new OptifineProgramState.RenderScaleState(1.0F, 0.0F, 0.0F));
    }
}

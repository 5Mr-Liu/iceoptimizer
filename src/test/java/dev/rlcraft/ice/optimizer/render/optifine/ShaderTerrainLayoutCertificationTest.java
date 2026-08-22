package dev.rlcraft.ice.optimizer.render.optifine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShaderTerrainLayoutCertificationTest {
    @Test
    public void linkedProgramWithoutGenericOrUnsupportedBuiltinsIsCertified() {
        ShaderTerrainLayoutCertification.Result result =
            ShaderTerrainLayoutCertification.certify(7,
                "void main(){gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex;"
                    + "vec4 c=gl_Color+gl_MultiTexCoord0+gl_MultiTexCoord1;}",
                new Query(true, new String[0]));
        assertTrue(result.getDetail(), result.isCertified());
    }

    @Test
    public void activeOptifineAttributesRequireExtendedBlockLayout() {
        ShaderTerrainLayoutCertification.Result result =
            ShaderTerrainLayoutCertification.certify(9,
                "void main(){gl_Position=gl_Vertex;}",
                new Query(true, new String[] { "mc_Entity", "at_tangent" }));
        assertFalse(result.isCertified());
        assertTrue(result.getDetail().contains("mc_Entity"));
        assertTrue(result.getDetail().contains("at_tangent"));
    }

    @Test
    public void unsupportedBuiltinsAreRejectedAsWholeIdentifiersOnly() {
        assertFalse(ShaderTerrainLayoutCertification.certify(11,
            "void main(){vec3 n=gl_Normal;}",
            new Query(true, new String[0])).isCertified());
        assertFalse(ShaderTerrainLayoutCertification.certify(11,
            "void main(){vec4 t=gl_MultiTexCoord2;}",
            new Query(true, new String[0])).isCertified());
        assertTrue(ShaderTerrainLayoutCertification.certify(11,
            "void main(){mat3 n=gl_NormalMatrix;gl_Position=gl_Vertex;}",
            new Query(true, new String[0])).isCertified());
        assertFalse(ShaderTerrainLayoutCertification.certify(11,
            "#define ATTR(n) gl_MultiTexCoord ## n\n"
                + "void main(){gl_Position=ATTR(2);}",
            new Query(true, new String[0])).isCertified());
    }

    @Test
    public void unlinkedOrQueryFailureIsRejectedWithoutOptimism() {
        assertFalse(ShaderTerrainLayoutCertification.certify(13,
            "void main(){gl_Position=gl_Vertex;}",
            new Query(false, new String[0])).isCertified());
        assertFalse(ShaderTerrainLayoutCertification.certify(13,
            "void main(){gl_Position=gl_Vertex;}",
            new ShaderTerrainLayoutCertification.AttributeQuery() {
                @Override public boolean linked(int program) {
                    throw new IllegalStateException("query failed");
                }
                @Override public int activeAttributeCount(int program) { return 0; }
                @Override public int maximumAttributeName(int program) { return 0; }
                @Override public String activeAttribute(int program, int index,
                                                        int maximumChars) {
                    return null;
                }
            }).isCertified());
    }

    private static final class Query
        implements ShaderTerrainLayoutCertification.AttributeQuery {
        private final boolean linked;
        private final String[] attributes;
        private Query(boolean linked, String[] attributes) {
            this.linked = linked;
            this.attributes = attributes;
        }
        @Override public boolean linked(int program) { return linked; }
        @Override public int activeAttributeCount(int program) {
            return attributes.length;
        }
        @Override public int maximumAttributeName(int program) { return 64; }
        @Override public String activeAttribute(int program, int index,
                                                int maximumChars) {
            return attributes[index];
        }
    }
}

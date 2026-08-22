package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.util.LinkedHashSet;
import java.util.Set;
import org.lwjgl.opengl.GL20;

/** Proves that a linked ShaderPack vertex program consumes only BLOCK's 28-byte prefix. */
public final class ShaderTerrainLayoutCertification {
    private static final int MAX_ATTRIBUTES = 64;
    private static final int MAX_ATTRIBUTE_NAME = 4096;

    private ShaderTerrainLayoutCertification() {
    }

    public static Result certify(int program, String resolvedVertexSource) {
        return certify(program, resolvedVertexSource, LwjglQuery.INSTANCE);
    }

    static Result certify(int program, String resolvedVertexSource,
                          AttributeQuery query) {
        if (program <= 0 || resolvedVertexSource == null || query == null) {
            return Result.rejected("invalid terrain shader layout input");
        }
        String unsupportedBuiltin = unsupportedBuiltin(resolvedVertexSource);
        if (unsupportedBuiltin != null) {
            return Result.rejected("vertex shader consumes unsupported built-in "
                + unsupportedBuiltin);
        }
        try {
            if (!query.linked(program)) {
                return Result.rejected("candidate shader program is not linked");
            }
            int count = query.activeAttributeCount(program);
            if (count < 0 || count > MAX_ATTRIBUTES) {
                return Result.rejected("active generic attribute count exceeds limits");
            }
            if (count == 0) {
                return Result.certified(
                    "linked program uses only position/color/uv0/lightmap prefix");
            }
            int nameCapacity = query.maximumAttributeName(program);
            if (nameCapacity < 0 || nameCapacity > MAX_ATTRIBUTE_NAME) {
                return Result.rejected("active generic attribute names exceed limits");
            }
            Set<String> names = new LinkedHashSet<String>();
            for (int index = 0; index < count; index++) {
                String name = query.activeAttribute(program, index,
                    Math.max(1, nameCapacity));
                if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
                    || name.length() > MAX_ATTRIBUTE_NAME) {
                    return Result.rejected("invalid active generic attribute");
                }
                names.add(name);
            }
            return Result.rejected("active generic attributes require the extended BLOCK layout: "
                + names);
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            return Result.rejected("terrain layout query failed: "
                + error.getClass().getSimpleName());
        }
    }

    static String unsupportedBuiltin(String source) {
        // Token pasting can synthesize an unsupported compatibility attribute
        // without leaving its final identifier in the source text inspected
        // below (for example gl_MultiTexCoord ## 2).  Full GLSL preprocessing
        // is driver/version dependent, so this layout-only gate rejects that
        // ambiguity instead of certifying it optimistically.
        if (source != null && source.indexOf("##") >= 0) {
            return "preprocessor token pasting";
        }
        int length = source == null ? 0 : source.length();
        for (int offset = 0; offset < length;) {
            char current = source.charAt(offset);
            if (!identifierStart(current)) {
                offset++;
                continue;
            }
            int end = offset + 1;
            while (end < length && identifierPart(source.charAt(end))) end++;
            String token = source.substring(offset, end);
            if ("gl_Normal".equals(token) || "gl_FogCoord".equals(token)
                || "gl_SecondaryColor".equals(token)) return token;
            if (token.startsWith("gl_MultiTexCoord")) {
                String suffix = token.substring("gl_MultiTexCoord".length());
                try {
                    if (!suffix.isEmpty() && Integer.parseInt(suffix) >= 2) {
                        return token;
                    }
                } catch (NumberFormatException ignored) {
                    return token;
                }
            }
            offset = end;
        }
        return null;
    }

    private static boolean identifierStart(char value) {
        return value == '_' || value >= 'A' && value <= 'Z'
            || value >= 'a' && value <= 'z';
    }

    private static boolean identifierPart(char value) {
        return identifierStart(value) || value >= '0' && value <= '9';
    }

    interface AttributeQuery {
        boolean linked(int program);
        int activeAttributeCount(int program);
        int maximumAttributeName(int program);
        String activeAttribute(int program, int index, int maximumChars);
    }

    private enum LwjglQuery implements AttributeQuery {
        INSTANCE;
        @Override public boolean linked(int program) {
            return GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0;
        }
        @Override public int activeAttributeCount(int program) {
            return GL20.glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTES);
        }
        @Override public int maximumAttributeName(int program) {
            return GL20.glGetProgrami(program,
                GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        }
        @Override public String activeAttribute(int program, int index,
                                                int maximumChars) {
            return GL20.glGetActiveAttrib(program, index, maximumChars);
        }
    }

    public static final class Result {
        private final boolean certified;
        private final String detail;

        private Result(boolean certified, String detail) {
            this.certified = certified;
            this.detail = detail == null ? "" : detail.length() <= 1024
                ? detail : detail.substring(0, 1024);
        }

        private static Result certified(String detail) {
            return new Result(true, detail);
        }
        private static Result rejected(String detail) {
            return new Result(false, detail);
        }
        public boolean isCertified() { return certified; }
        public String getDetail() { return detail; }
    }
}

package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.HashSet;
import java.util.Set;
import org.lwjgl.opengl.ARBGeometryShader4;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Mirrors only link-time state that is observable in an already-linked program. */
final class LwjglShaderLinkInterface {
    private static final int MAX_ATTRIBUTES = 64;
    private static final int MAX_ATTRIBUTE_LOCATION = 255;
    private static final int MAX_NAME_CHARS = 4096;

    private LwjglShaderLinkInterface() {
    }

    static void mirror(int program, int legacyProgram, boolean geometry) {
        if (legacyProgram <= 0) {
            if (geometry) {
                throw new IllegalStateException(
                    "geometry certification requires the linked legacy program");
            }
            return;
        }
        if (GL20.glGetProgrami(legacyProgram, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("legacy OptiFine program is not linked");
        }
        rejectTransformFeedback(legacyProgram);
        int attributes = GL20.glGetProgrami(legacyProgram,
            GL20.GL_ACTIVE_ATTRIBUTES);
        int maximumName = GL20.glGetProgrami(legacyProgram,
            GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        if (attributes < 0 || attributes > MAX_ATTRIBUTES || maximumName < 0
            || maximumName > MAX_NAME_CHARS) {
            throw new IllegalStateException(
                "legacy OptiFine attribute interface exceeds limits");
        }
        int nameCapacity = Math.max(1, maximumName);
        int maximumLocations = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        if (maximumLocations <= 0
            || maximumLocations > MAX_ATTRIBUTE_LOCATION + 1) {
            maximumLocations = MAX_ATTRIBUTE_LOCATION + 1;
        }
        Set<String> names = new HashSet<String>();
        Set<Integer> locations = new HashSet<Integer>();
        for (int index = 0; index < attributes; index++) {
            String name = GL20.glGetActiveAttrib(legacyProgram, index,
                nameCapacity);
            if (name == null || name.isEmpty() || name.length() > MAX_NAME_CHARS
                || name.indexOf('\0') >= 0 || name.startsWith("gl_")
                || !names.add(name)) {
                throw new IllegalStateException(
                    "invalid legacy OptiFine attribute name");
            }
            int location = GL20.glGetAttribLocation(legacyProgram, name);
            if (location < 0 || location >= maximumLocations
                || !locations.add(Integer.valueOf(location))) {
                throw new IllegalStateException(
                    "legacy OptiFine attribute location exceeds limits");
            }
            GL20.glBindAttribLocation(program, location, name);
        }
        if (geometry) {
            mirrorGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_INPUT_TYPE_ARB);
            mirrorGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_OUTPUT_TYPE_ARB);
            mirrorGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_VERTICES_OUT_ARB);
        }
    }

    /** Verifies the post-link input contract before a clone can be retained. */
    static void verify(int program, int legacyProgram, boolean geometry) {
        if (program <= 0 || legacyProgram <= 0
            || GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0
            || GL20.glGetProgrami(legacyProgram, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("shader link interface is not linked");
        }
        rejectTransformFeedback(program);
        rejectTransformFeedback(legacyProgram);
        int legacyCount = GL20.glGetProgrami(legacyProgram,
            GL20.GL_ACTIVE_ATTRIBUTES);
        int candidateCount = GL20.glGetProgrami(program,
            GL20.GL_ACTIVE_ATTRIBUTES);
        if (legacyCount < 0 || legacyCount > MAX_ATTRIBUTES
            || candidateCount != legacyCount) {
            throw new IllegalStateException("active shader attribute count differs");
        }
        int maximumName = GL20.glGetProgrami(legacyProgram,
            GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        if (maximumName < 0 || maximumName > MAX_NAME_CHARS) {
            throw new IllegalStateException("active shader attribute names exceed limits");
        }
        int capacity = Math.max(1, maximumName);
        Set<String> names = new HashSet<String>();
        for (int index = 0; index < legacyCount; index++) {
            String name = GL20.glGetActiveAttrib(legacyProgram, index, capacity);
            if (name == null || name.isEmpty() || name.length() > MAX_NAME_CHARS
                || name.indexOf('\0') >= 0 || !names.add(name)) {
                throw new IllegalStateException("invalid linked shader attribute");
            }
            int legacyLocation = GL20.glGetAttribLocation(legacyProgram, name);
            int candidateLocation = GL20.glGetAttribLocation(program, name);
            int legacySize = GL20.glGetActiveAttribSize(legacyProgram, index);
            int legacyType = GL20.glGetActiveAttribType(legacyProgram, index);
            Attribute candidate = find(program, name, candidateCount);
            if (legacyLocation < 0 || candidateLocation != legacyLocation
                || legacySize <= 0 || candidate == null
                || candidate.size != legacySize || candidate.type != legacyType) {
                throw new IllegalStateException("linked shader attribute differs: "
                    + name);
            }
        }
        int expectedStages = geometry ? 3 : 2;
        int legacyStages = GL20.glGetProgrami(legacyProgram,
            GL20.GL_ATTACHED_SHADERS);
        int candidateStages = GL20.glGetProgrami(program,
            GL20.GL_ATTACHED_SHADERS);
        if (legacyStages != expectedStages || candidateStages != expectedStages) {
            throw new IllegalStateException("attached shader stage set differs");
        }
        if (geometry) {
            verifyGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_INPUT_TYPE_ARB);
            verifyGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_OUTPUT_TYPE_ARB);
            verifyGeometryParameter(program, legacyProgram,
                ARBGeometryShader4.GL_GEOMETRY_VERTICES_OUT_ARB);
        }
    }

    private static Attribute find(int program, String expected, int count) {
        int maximumName = GL20.glGetProgrami(program,
            GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
        if (maximumName < 0 || maximumName > MAX_NAME_CHARS) return null;
        int capacity = Math.max(1, maximumName);
        for (int index = 0; index < count; index++) {
            String name = GL20.glGetActiveAttrib(program, index, capacity);
            if (!expected.equals(name)) continue;
            return new Attribute(GL20.glGetActiveAttribSize(program, index),
                GL20.glGetActiveAttribType(program, index));
        }
        return null;
    }

    private static void rejectTransformFeedback(int program) {
        int varyings = GL20.glGetProgrami(program,
            GL30.GL_TRANSFORM_FEEDBACK_VARYINGS);
        if (varyings != 0) {
            throw new IllegalStateException(
                "transform-feedback shader programs are not certifiable");
        }
    }

    private static void verifyGeometryParameter(int program, int legacyProgram,
                                                int parameter) {
        if (GL20.glGetProgrami(program, parameter)
            != GL20.glGetProgrami(legacyProgram, parameter)) {
            throw new IllegalStateException(
                "linked geometry interface differs");
        }
    }

    private static void mirrorGeometryParameter(int program, int legacyProgram,
                                                int parameter) {
        int value = GL20.glGetProgrami(legacyProgram, parameter);
        if (value <= 0) {
            throw new IllegalStateException(
                "invalid legacy OptiFine geometry link parameter");
        }
        ARBGeometryShader4.glProgramParameteriARB(program, parameter, value);
    }

    private static final class Attribute {
        private final int size;
        private final int type;
        private Attribute(int size, int type) {
            this.size = size;
            this.type = type;
        }
    }
}

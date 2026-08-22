package dev.rlcraft.ice.optimizer.render.optifine;

import dev.rlcraft.ice.optimizer.FatalErrors;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/** Bounded snapshot/copy of ordinary uniforms; storage blocks fail closed. */
final class LwjglShaderUniformMirror {
    private static final int MAX_UNIFORMS = 512;
    private static final int MAX_UNIFORM_ELEMENTS = 8192;
    private static final int MAX_NAME_CHARS = 4096;

    private LwjglShaderUniformMirror() {
    }

    static int typeComponentsForTest(int type) {
        TypeInfo info = TypeInfo.of(type);
        return info == null ? 0 : info.components;
    }

    static boolean typeUnsignedForTest(int type) {
        TypeInfo info = TypeInfo.of(type);
        return info != null && info.unsigned;
    }

    static Snapshot snapshot(int sourceProgram, int targetProgram,
                             FloatBuffer floats, IntBuffer integers) {
        if (sourceProgram <= 0 || targetProgram <= 0
            || sourceProgram == targetProgram) {
            return Snapshot.failure("invalid uniform mirror programs");
        }
        if (floats == null || !floats.isDirect() || floats.capacity() < 16
            || integers == null || !integers.isDirect()
            || integers.capacity() < 4) {
            return Snapshot.failure("uniform mirror workspace unavailable");
        }
        try {
            if (GL20.glGetProgrami(sourceProgram, GL20.GL_LINK_STATUS) == 0
                || GL20.glGetProgrami(targetProgram, GL20.GL_LINK_STATUS) == 0) {
                return Snapshot.failure("uniform mirror program is not linked");
            }
            int sourceBlocks = GL20.glGetProgrami(sourceProgram,
                GL31.GL_ACTIVE_UNIFORM_BLOCKS);
            int targetBlocks = GL20.glGetProgrami(targetProgram,
                GL31.GL_ACTIVE_UNIFORM_BLOCKS);
            if (sourceBlocks != 0 || targetBlocks != 0) {
                return Snapshot.failure(
                    "uniform/storage blocks are not eligible for bounded mirroring");
            }
            Interface source = readInterface(sourceProgram);
            Interface target = readInterface(targetProgram);
            if (!source.valid) return Snapshot.failure(source.detail);
            if (!target.valid) return Snapshot.failure(target.detail);
            if (source.uniforms.size() != target.uniforms.size()) {
                return Snapshot.failure("active uniform count differs");
            }
            List<Value> values = new ArrayList<Value>();
            int elements = 0;
            for (Uniform sourceUniform : source.uniforms.values()) {
                Uniform targetUniform = target.uniforms.get(sourceUniform.name);
                if (targetUniform == null || sourceUniform.type != targetUniform.type
                    || sourceUniform.size != targetUniform.size) {
                    return Snapshot.failure("uniform interface differs: "
                        + sourceUniform.name);
                }
                TypeInfo type = TypeInfo.of(sourceUniform.type);
                if (type == null) {
                    return Snapshot.failure("unsupported uniform type "
                        + sourceUniform.type + " for " + sourceUniform.name);
                }
                if (sourceUniform.size > MAX_UNIFORM_ELEMENTS - elements) {
                    return Snapshot.failure("uniform element limit exceeded");
                }
                elements += sourceUniform.size;
                for (int element = 0; element < sourceUniform.size; element++) {
                    String elementName = elementName(sourceUniform.name, element,
                        sourceUniform.size);
                    int sourceLocation = GL20.glGetUniformLocation(sourceProgram,
                        elementName);
                    int targetLocation = GL20.glGetUniformLocation(targetProgram,
                        elementName);
                    if (sourceLocation < 0 || targetLocation < 0) {
                        return Snapshot.failure("uniform location differs: "
                            + elementName);
                    }
                    if (type.floating) {
                        floats.clear();
                        floats.limit(type.components);
                        GL20.glGetUniform(sourceProgram, sourceLocation, floats);
                        float[] data = new float[type.components];
                        for (int i = 0; i < data.length; i++) data[i] = floats.get(i);
                        values.add(Value.floating(targetLocation, type, data));
                    } else {
                        integers.clear();
                        integers.limit(type.components);
                        if (type.unsigned) {
                            GL30.glGetUniformu(sourceProgram, sourceLocation,
                                integers);
                        } else {
                            GL20.glGetUniform(sourceProgram, sourceLocation,
                                integers);
                        }
                        int[] data = new int[type.components];
                        for (int i = 0; i < data.length; i++) data[i] = integers.get(i);
                        values.add(Value.integer(targetLocation, type, data));
                    }
                }
            }
            return Snapshot.success(values);
        } catch (Throwable error) {
            return Snapshot.failure(compact(error));
        }
    }

    private static Interface readInterface(int program) {
        int count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
        int maximumName = GL20.glGetProgrami(program,
            GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH);
        if (count < 0 || count > MAX_UNIFORMS || maximumName < 0
            || maximumName > MAX_NAME_CHARS) {
            return Interface.failure("active uniform interface exceeds limits");
        }
        LinkedHashMap<String, Uniform> uniforms =
            new LinkedHashMap<String, Uniform>();
        int nameCapacity = Math.max(1, maximumName);
        for (int index = 0; index < count; index++) {
            String name = GL20.glGetActiveUniform(program, index, nameCapacity);
            int size = GL20.glGetActiveUniformSize(program, index);
            int type = GL20.glGetActiveUniformType(program, index);
            if (name == null || name.isEmpty() || name.length() > MAX_NAME_CHARS
                || name.indexOf('\0') >= 0 || size <= 0
                || size > MAX_UNIFORM_ELEMENTS || uniforms.containsKey(name)) {
                return Interface.failure("invalid active uniform interface");
            }
            uniforms.put(name, new Uniform(name, size, type));
        }
        return Interface.success(uniforms);
    }

    private static String elementName(String activeName, int element, int size) {
        if (size <= 1) return activeName;
        int array = activeName.indexOf("[0]");
        if (array < 0) {
            throw new IllegalStateException(
                "uniform array does not expose a [0] base: " + activeName);
        }
        return activeName.substring(0, array + 1) + element
            + activeName.substring(array + 2);
    }

    private static String compact(Throwable error) {
        FatalErrors.rethrowIfFatal(error);
        String message = error.getMessage();
        String detail = error.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
        return detail.length() <= 256 ? detail : detail.substring(0, 256);
    }

    static final class Snapshot {
        private final boolean valid;
        private final String detail;
        private final List<Value> values;

        private Snapshot(boolean valid, String detail, List<Value> values) {
            this.valid = valid;
            this.detail = detail == null ? "" : detail;
            this.values = values;
        }

        private static Snapshot success(List<Value> values) {
            return new Snapshot(true, "ordinary uniform interface mirrored",
                values);
        }

        private static Snapshot failure(String detail) {
            return new Snapshot(false, detail, null);
        }

        boolean isValid() { return valid; }
        String getDetail() { return detail; }

        void apply(FloatBuffer floats, IntBuffer integers) {
            if (!valid || values == null) {
                throw new IllegalStateException("invalid uniform snapshot: "
                    + detail);
            }
            if (floats == null || !floats.isDirect() || floats.capacity() < 16
                || integers == null || !integers.isDirect()
                || integers.capacity() < 4) {
                throw new IllegalStateException(
                    "uniform mirror workspace unavailable");
            }
            for (Value value : values) value.apply(floats, integers);
        }
    }

    private static final class Interface {
        private final boolean valid;
        private final String detail;
        private final Map<String, Uniform> uniforms;

        private Interface(boolean valid, String detail,
                          Map<String, Uniform> uniforms) {
            this.valid = valid;
            this.detail = detail;
            this.uniforms = uniforms;
        }

        private static Interface success(Map<String, Uniform> uniforms) {
            return new Interface(true, "", uniforms);
        }

        private static Interface failure(String detail) {
            return new Interface(false, detail,
                java.util.Collections.<String, Uniform>emptyMap());
        }
    }

    private static final class Uniform {
        private final String name;
        private final int size;
        private final int type;

        private Uniform(String name, int size, int type) {
            this.name = name;
            this.size = size;
            this.type = type;
        }
    }

    private static final class Value {
        private final int location;
        private final TypeInfo type;
        private final float[] floats;
        private final int[] integers;

        private Value(int location, TypeInfo type, float[] floats,
                      int[] integers) {
            this.location = location;
            this.type = type;
            this.floats = floats;
            this.integers = integers;
        }

        private static Value floating(int location, TypeInfo type,
                                      float[] values) {
            return new Value(location, type, values, null);
        }

        private static Value integer(int location, TypeInfo type, int[] values) {
            return new Value(location, type, null, values);
        }

        private void apply(FloatBuffer floatBuffer, IntBuffer intBuffer) {
            if (type.floating) {
                floatBuffer.clear();
                floatBuffer.put(floats).flip();
                if (type.matrixColumns != 0) {
                    matrix(floatBuffer);
                } else if (type.components == 1) GL20.glUniform1(location,
                    floatBuffer);
                else if (type.components == 2) GL20.glUniform2(location,
                    floatBuffer);
                else if (type.components == 3) GL20.glUniform3(location,
                    floatBuffer);
                else GL20.glUniform4(location, floatBuffer);
            } else {
                intBuffer.clear();
                intBuffer.put(integers).flip();
                if (type.unsigned) {
                    if (type.components == 1) GL30.glUniform1u(location, intBuffer);
                    else if (type.components == 2) GL30.glUniform2u(location,
                        intBuffer);
                    else if (type.components == 3) GL30.glUniform3u(location,
                        intBuffer);
                    else GL30.glUniform4u(location, intBuffer);
                } else if (type.components == 1) GL20.glUniform1(location,
                    intBuffer);
                else if (type.components == 2) GL20.glUniform2(location,
                    intBuffer);
                else if (type.components == 3) GL20.glUniform3(location,
                    intBuffer);
                else GL20.glUniform4(location, intBuffer);
            }
        }

        private void matrix(FloatBuffer values) {
            int columns = type.matrixColumns;
            int rows = type.matrixRows;
            if (columns == 2 && rows == 2) GL20.glUniformMatrix2(location,
                false, values);
            else if (columns == 3 && rows == 3) GL20.glUniformMatrix3(location,
                false, values);
            else if (columns == 4 && rows == 4) GL20.glUniformMatrix4(location,
                false, values);
            else if (columns == 2 && rows == 3) GL21.glUniformMatrix2x3(location,
                false, values);
            else if (columns == 2 && rows == 4) GL21.glUniformMatrix2x4(location,
                false, values);
            else if (columns == 3 && rows == 2) GL21.glUniformMatrix3x2(location,
                false, values);
            else if (columns == 3 && rows == 4) GL21.glUniformMatrix3x4(location,
                false, values);
            else if (columns == 4 && rows == 2) GL21.glUniformMatrix4x2(location,
                false, values);
            else if (columns == 4 && rows == 3) GL21.glUniformMatrix4x3(location,
                false, values);
            else throw new IllegalStateException("unsupported matrix shape");
        }
    }

    private static final class TypeInfo {
        private final int components;
        private final boolean floating;
        private final boolean unsigned;
        private final int matrixColumns;
        private final int matrixRows;

        private TypeInfo(int components, boolean floating, boolean unsigned,
                         int matrixColumns, int matrixRows) {
            this.components = components;
            this.floating = floating;
            this.unsigned = unsigned;
            this.matrixColumns = matrixColumns;
            this.matrixRows = matrixRows;
        }

        private static TypeInfo of(int type) {
            if (type == GL11.GL_FLOAT) return floating(1);
            if (type == GL20.GL_FLOAT_VEC2) return floating(2);
            if (type == GL20.GL_FLOAT_VEC3) return floating(3);
            if (type == GL20.GL_FLOAT_VEC4) return floating(4);
            if (type == GL20.GL_FLOAT_MAT2) return matrix(2, 2);
            if (type == GL20.GL_FLOAT_MAT3) return matrix(3, 3);
            if (type == GL20.GL_FLOAT_MAT4) return matrix(4, 4);
            if (type == GL21.GL_FLOAT_MAT2x3) return matrix(2, 3);
            if (type == GL21.GL_FLOAT_MAT2x4) return matrix(2, 4);
            if (type == GL21.GL_FLOAT_MAT3x2) return matrix(3, 2);
            if (type == GL21.GL_FLOAT_MAT3x4) return matrix(3, 4);
            if (type == GL21.GL_FLOAT_MAT4x2) return matrix(4, 2);
            if (type == GL21.GL_FLOAT_MAT4x3) return matrix(4, 3);
            if (type == GL11.GL_INT || type == GL20.GL_BOOL
                || sampler(type)) return integer(1, false);
            if (type == GL20.GL_INT_VEC2 || type == GL20.GL_BOOL_VEC2) {
                return integer(2, false);
            }
            if (type == GL20.GL_INT_VEC3 || type == GL20.GL_BOOL_VEC3) {
                return integer(3, false);
            }
            if (type == GL20.GL_INT_VEC4 || type == GL20.GL_BOOL_VEC4) {
                return integer(4, false);
            }
            if (type == GL11.GL_UNSIGNED_INT) return integer(1, true);
            if (type == GL30.GL_UNSIGNED_INT_VEC2) return integer(2, true);
            if (type == GL30.GL_UNSIGNED_INT_VEC3) return integer(3, true);
            if (type == GL30.GL_UNSIGNED_INT_VEC4) return integer(4, true);
            return null;
        }

        private static TypeInfo floating(int components) {
            return new TypeInfo(components, true, false, 0, 0);
        }

        private static TypeInfo integer(int components, boolean unsigned) {
            return new TypeInfo(components, false, unsigned, 0, 0);
        }

        private static TypeInfo matrix(int columns, int rows) {
            return new TypeInfo(columns * rows, true, false, columns, rows);
        }

        private static boolean sampler(int type) {
            return type >= 35677 && type <= 35684
                || type >= 36288 && type <= 36293
                || type >= 36297 && type <= 36312
                || type >= 36876 && type <= 36879
                || type >= 37128 && type <= 37133;
        }
    }
}

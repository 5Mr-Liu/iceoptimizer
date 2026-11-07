package dev.rlcraft.ice.optimizer.render.terrain;

import org.lwjgl.opengl.GL20;

final class NativeTerrainProgram {
    private NativeTerrainProgram() { }
    static int link(boolean compatibility) {
        int vertex = compile(GL20.GL_VERTEX_SHADER, compatibility ? NativeTerrainShader.vertex21() : NativeTerrainShader.vertex());
        int fragment = 0, result = 0;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, NativeTerrainShader.fragment());
            result = GL20.glCreateProgram();
            GL20.glAttachShader(result, vertex); GL20.glAttachShader(result, fragment);
            GL20.glBindAttribLocation(result, 8, "icePosition");
            GL20.glBindAttribLocation(result, 9, "iceColor");
            GL20.glBindAttribLocation(result, 10, "iceUv");
            GL20.glBindAttribLocation(result, 11, "iceLight");
            GL20.glBindAttribLocation(result, 12, "iceOffset");
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(result, 4096));
            return result;
        } catch (RuntimeException failure) {
            if (result != 0) GL20.glDeleteProgram(result);
            throw failure;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

}

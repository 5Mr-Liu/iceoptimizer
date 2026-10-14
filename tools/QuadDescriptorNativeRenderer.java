package dev.rlcraft.ice.benchmark;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Experimental captured-geometry renderer, never packaged with ICE. */
public final class QuadDescriptorNativeRenderer {
    public static final class Range {
        public final boolean compact;
        public final int first;
        public int count;
        Range(boolean compact, int first) { this.compact = compact; this.first = first; this.count = 1; }
    }
    private final Map<TerrainSceneData.Section, List<Range>> ranges = new IdentityHashMap<TerrainSceneData.Section, List<Range>>();
    private int buffer, vao, program, vertexShader, fragmentShader, descriptorTexture;
    private final boolean pulling = Boolean.getBoolean("ice.quadPulling");
    public final int descriptors;
    public final long encodedBytes;

    public QuadDescriptorNativeRenderer(TerrainSceneData scene) {
        long maximum = 0;
        for (TerrainSceneData.Section section : scene.sections) if (section.owned) maximum += (section.vertices / 4L) * 72;
        if (maximum > Integer.MAX_VALUE) throw new IllegalArgumentException("Captured data too large");
        ByteBuffer encoded = BufferUtils.createByteBuffer((int) maximum).order(ByteOrder.nativeOrder());
        int[] input = new int[28], output = new int[18];
        int count = 0;
        for (TerrainSceneData.Section section : scene.sections) {
            if (!section.owned) continue;
            ByteBuffer source = ByteBuffer.wrap(section.data).order(ByteOrder.LITTLE_ENDIAN);
            List<Range> list = new ArrayList<Range>();
            Range previous = null;
            for (int vertex = 0; vertex + 4 <= section.vertices; vertex += 4) {
                for (int i = 0; i < 28; i++) input[i] = source.getInt(vertex * 28 + i * 4);
                boolean compact = TerrainQuadDescriptorProbe.encode(input, output);
                int first = compact ? count : vertex / 4;
                if (previous != null && previous.compact == compact && previous.first + previous.count == first) previous.count++;
                else { previous = new Range(compact, first); list.add(previous); }
                if (compact) { for (int value : output) encoded.putInt(value); count++; }
            }
            ranges.put(section, list);
        }
        descriptors = count; encodedBytes = encoded.position(); encoded.flip();
        buffer = GL15.glGenBuffers(); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, encoded, GL15.GL_STATIC_DRAW);
        vao = GL30.glGenVertexArrays(); GL30.glBindVertexArray(vao);
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 72, 0L);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 72, 16L);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, 72, 32L);
        GL30.glVertexAttribIPointer(3, 1, GL11.GL_UNSIGNED_INT, 72, 36L);
        GL30.glVertexAttribIPointer(4, 4, GL11.GL_UNSIGNED_INT, 72, 40L);
        GL30.glVertexAttribIPointer(5, 4, GL11.GL_UNSIGNED_INT, 72, 56L);
        if (!pulling) for (int i = 0; i < 6; i++) { GL20.glEnableVertexAttribArray(i); GL33.glVertexAttribDivisor(i, 1); }
        GL30.glBindVertexArray(0);
        if (pulling) {
            descriptorTexture = GL11.glGenTextures(); GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, descriptorTexture); GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_R32UI, buffer);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        vertexShader = compile(GL20.GL_VERTEX_SHADER,
            "#version 330 compatibility\n"
            + (Boolean.getBoolean("ice.quadInvariant") ? "invariant gl_Position;\n" : "")
            + (pulling ? "uniform usamplerBuffer descriptors; uint word(uint i){return texelFetch(descriptors,int(i)).r;} float number(uint i){return uintBitsToFloat(word(i));}\n"
                : "layout(location=0) in vec4 b0; layout(location=1) in vec4 b1; layout(location=2) in float v1;\n"
                    + "layout(location=3) in uint descriptor; layout(location=4) in uvec4 colors; layout(location=5) in uvec4 lights;\n")
            + "out vec4 tint; out vec2 uv0; out vec2 uv1;\n"
            + "void main(){ uint corner=uint(gl_VertexID)&3u; "
            + (pulling ? "uint base=(uint(gl_VertexID)>>2u)*18u; uint descriptor=word(base+9u); " : "")
            + "uint code=(descriptor>>(2u+corner*4u))&15u; int axis=int(descriptor&3u); vec3 p;\n"
            + (pulling ? "p[axis]=number(base); p[(axis+1)%3]=number(base+1u+(code&1u)); p[(axis+2)%3]=number(base+3u+((code>>1u)&1u));\n"
                    + "float u=number(base+5u+((code>>2u)&1u)); float v=number(base+7u+((code>>3u)&1u)); uint c=word(base+10u+corner); uint l=word(base+14u+corner);\n"
                : "p[axis]=b0.x; p[(axis+1)%3]=(code&1u)==0u?b0.y:b0.z; p[(axis+2)%3]=(code&2u)==0u?b0.w:b1.x;\n"
                    + "float u=(code&4u)==0u?b1.y:b1.z; float v=(code&8u)==0u?b1.w:v1; uint c=colors[int(corner)]; uint l=lights[int(corner)];\n")
            + "tint=vec4(float(c&255u),float((c>>8u)&255u),float((c>>16u)&255u),float(c>>24u))/255.0;\n"
            + "uv0=(gl_TextureMatrix[0]*vec4(u,v,0.0,1.0)).xy; uv1=(gl_TextureMatrix[1]*vec4(float(int(l<<16u)>>16),float(int(l)>>16),0.0,1.0)).xy;\n"
            + "gl_Position=" + (Boolean.getBoolean("ice.quadSeparateMatrices")
                ? "gl_ProjectionMatrix*(gl_ModelViewMatrix*vec4(p,1.0))"
                : "gl_ModelViewProjectionMatrix*vec4(p,1.0)") + "; }\n");
        fragmentShader = compile(GL20.GL_FRAGMENT_SHADER,
            "#version 330 compatibility\n"
            + "uniform sampler2D atlas; uniform sampler2D lightmap; in vec4 tint; in vec2 uv0; in vec2 uv1; out vec4 color;\n"
            + "void main(){color=tint*texture(atlas,uv0)*texture(lightmap,uv1);}\n");
        program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertexShader); GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 4096));
        GL20.glUseProgram(program); GL20.glUniform1i(GL20.glGetUniformLocation(program, "atlas"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "lightmap"), 1);
        if (pulling) GL20.glUniform1i(GL20.glGetUniformLocation(program, "descriptors"), 2);
        GL20.glUseProgram(0);
    }
    public List<Range> ranges(TerrainSceneData.Section section) { return ranges.get(section); }
    public void draw(int first, int count) {
        GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
        if (pulling) GL11.glDrawArrays(GL11.GL_QUADS, first * 4, count * 4);
        else GL42.glDrawArraysInstancedBaseInstance(GL11.GL_QUADS, 0, 4, count, first);
    }
    public void legacy() { GL20.glUseProgram(0); GL30.glBindVertexArray(0); }
    public void close() {
        legacy(); GL20.glDeleteProgram(program); GL20.glDeleteShader(vertexShader); GL20.glDeleteShader(fragmentShader);
        GL30.glDeleteVertexArrays(vao); GL15.glDeleteBuffers(buffer);
        if (descriptorTexture != 0) GL11.glDeleteTextures(descriptorTexture);
    }
    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader, 4096));
        return shader;
    }
}

package dev.rlcraft.ice.optimizer.render.terrain;

/** ICE-authored terrain stages, using the compatibility profile's light/fog state. */
public final class NativeTerrainShader {
    private NativeTerrainShader() { }

    public static String vertex() {
        return "#version 120\n"
            + "attribute vec3 icePosition;\nattribute vec4 iceColor;\n"
            + "attribute vec2 iceUv;\nattribute vec2 iceLight;\nattribute vec4 iceOffset;\n"
            + "uniform int iceFogDistance;\n"
            + "void main() {\n"
            + " vec3 local = icePosition * iceOffset.w + vec3(8.0 * iceOffset.w - 8.0);\n"
            + " vec4 eye = gl_ModelViewMatrix * vec4(local + iceOffset.xyz, 1.0);\n"
            + " gl_Position = gl_ProjectionMatrix * eye;\n gl_ClipVertex = eye;\n"
            + " gl_TexCoord[0] = gl_TextureMatrix[0] * vec4(iceUv, 0.0, 1.0);\n"
            + " gl_TexCoord[1] = gl_TextureMatrix[1] * vec4(iceLight, 0.0, 1.0);\n"
            + " gl_FrontColor = iceColor;\n gl_BackColor = iceColor;\n"
            + " gl_FogFragCoord = iceFogDistance == 1 ? length(eye.xyz) : -eye.z;\n"
            + "}\n";
    }

    public static String fragment() {
        return "#version 120\n"
            + "uniform sampler2D iceAtlas;\nuniform sampler2D iceLightmap;\n"
            + "uniform int iceFogMode;\n"
            + "void main() {\n"
            + " vec4 color = gl_Color * texture2DProj(iceAtlas, gl_TexCoord[0]);\n"
            + " color *= texture2DProj(iceLightmap, gl_TexCoord[1]);\n"
            + " float d = abs(gl_FogFragCoord);\n float f = 1.0;\n"
            + " if (iceFogMode == 9729) f = (gl_Fog.end - d) * gl_Fog.scale;\n"
            + " else if (iceFogMode == 2048) f = exp(-gl_Fog.density * d);\n"
            + " else if (iceFogMode == 2049) { float v = gl_Fog.density * d; f = exp(-v * v); }\n"
            + " color.rgb = mix(gl_Fog.color.rgb, color.rgb, clamp(f, 0.0, 1.0));\n"
            + " gl_FragColor = color;\n"
            + "}\n";
    }
}

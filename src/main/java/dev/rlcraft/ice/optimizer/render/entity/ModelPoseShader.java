package dev.rlcraft.ice.optimizer.render.entity;

/** GLSL 1.20 compatibility lighting; fragment processing remains native. */
final class ModelPoseShader {
    private ModelPoseShader() { }

    static String source(int paletteCapacity) {
        StringBuilder out = new StringBuilder(8192);
        out.append("#version 120\n")
            .append("attribute float icePart; uniform mat4 icePose[").append(paletteCapacity)
            .append("]; uniform int iceBase;\n")
            .append("uniform bool iceLighting, iceNormalize, iceRescale, iceTwoSide;\n")
            .append("uniform bool iceLocalViewer, iceSeparate; uniform int iceRadialFog; uniform bool iceLights[8];\n")
            .append("uniform bool iceColorMaterial; uniform int iceColorFace, iceColorMode;\n")
            .append("gl_MaterialParameters material(gl_MaterialParameters m, int face) {\n")
            .append("if(iceColorMaterial && (iceColorFace==1032 || iceColorFace==face)) {\n")
            .append("if(iceColorMode==4608 || iceColorMode==5634) m.ambient=gl_Color;\n")
            .append("if(iceColorMode==4609 || iceColorMode==5634) m.diffuse=gl_Color;\n")
            .append("if(iceColorMode==4610) m.specular=gl_Color; if(iceColorMode==5632) m.emission=gl_Color;} return m;}\n")
            .append("void illuminate(vec3 n, vec3 eye, gl_MaterialParameters m, out vec4 color, out vec4 secondary) {\n")
            .append("vec3 diffuse=m.emission.rgb + gl_LightModel.ambient.rgb*m.ambient.rgb; vec3 specular=vec3(0.0);\n")
            .append("vec3 view=iceLocalViewer ? normalize(-eye) : vec3(0.0,0.0,1.0);\n");
        // Constant light indices also compile on pre-GL3 drivers with restricted
        // indexing of compatibility built-ins. Only the user pose array is indexed.
        for (int i = 0; i < 8; i++) {
            out.append("if(iceLights[").append(i).append("]) {\n")
                .append("gl_LightSourceParameters light=gl_LightSource[").append(i).append("];\n")
                .append("vec3 l; float attenuation=1.0;\n")
                .append("if(light.position.w==0.0) {l=normalize(light.position.xyz);} else {\n")
                .append("vec3 delta=light.position.xyz/light.position.w-eye; float distance=length(delta); l=delta/max(distance,1.0e-20);\n")
                .append("attenuation=1.0/(light.constantAttenuation+light.linearAttenuation*distance+light.quadraticAttenuation*distance*distance);}\n")
                .append("if(light.spotCutoff!=180.0) {float spot=dot(-l,normalize(light.spotDirection));\n")
                .append("attenuation *= spot<light.spotCosCutoff ? 0.0 : pow(max(spot,0.0),light.spotExponent);}\n")
                .append("float d=max(dot(n,l),0.0); vec3 h=normalize(l+view);\n")
                .append("float s=d>0.0 ? (m.shininess==0.0 ? 1.0 : pow(max(dot(n,h),0.0),m.shininess)) : 0.0;\n")
                .append("diffuse+=attenuation*(light.ambient.rgb*m.ambient.rgb+d*light.diffuse.rgb*m.diffuse.rgb);\n")
                .append("specular+=attenuation*s*light.specular.rgb*m.specular.rgb; }\n");
        }
        out.append("color=vec4(clamp(diffuse+(iceSeparate?vec3(0.0):specular),0.0,1.0),m.diffuse.a);\n")
            .append("secondary=vec4(iceSeparate?clamp(specular,0.0,1.0):vec3(0.0),0.0); }\n")
            .append("void main() { mat4 pose=icePose[int(icePart)-iceBase]; vec4 eye=pose*gl_Vertex;\n")
            .append("gl_Position=gl_ProjectionMatrix*eye; gl_ClipVertex=eye;\n")
            .append("gl_FogFragCoord=iceRadialFog==1?length(eye.xyz):(iceRadialFog==2?-eye.z:abs(eye.z));\n")
            .append("if(iceLighting) { vec3 a=pose[0].xyz,b=pose[1].xyz,c=pose[2].xyz;\n")
            .append("mat3 normalMatrix=mat3(cross(b,c),cross(c,a),cross(a,b))/dot(a,cross(b,c));\n")
            .append("vec3 n=normalMatrix*gl_Normal; if(iceNormalize) n=normalize(n);\n")
            .append("else if(iceRescale) n*=1.0/length(normalMatrix[2]);\n")
            .append("illuminate(n,eye.xyz,material(gl_FrontMaterial,1028),gl_FrontColor,gl_FrontSecondaryColor);\n")
            .append("if(iceTwoSide) illuminate(-n,eye.xyz,material(gl_BackMaterial,1029),gl_BackColor,gl_BackSecondaryColor);\n")
            .append("else {gl_BackColor=gl_FrontColor; gl_BackSecondaryColor=gl_FrontSecondaryColor;}\n")
            .append("} else {gl_FrontColor=gl_Color; gl_BackColor=gl_Color; gl_FrontSecondaryColor=gl_SecondaryColor; gl_BackSecondaryColor=gl_SecondaryColor;}\n");
        for (int i = 0; i < 8; i++) out.append("gl_TexCoord[").append(i)
            .append("]=gl_TextureMatrix[").append(i).append("]*gl_MultiTexCoord").append(i).append(";\n");
        return out.append("}\n").toString();
    }
}






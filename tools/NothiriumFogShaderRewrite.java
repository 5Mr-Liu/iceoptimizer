package dev.rlcraft.ice.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Test-only, version-pinned shader adaptation. No dependency shader is redistributed. */
public final class NothiriumFogShaderRewrite {
    public static final String VERTEX_SHA256="a540f1b42451f2561b2068710eabd5226dd51fb4c260a90d4652cb2cb77504c1";
    public static final String FRAGMENT_SHA256="78748b24d0b22ab07857bac850afe4d8eb99b5602af82604321922995ece7a1a";
    private NothiriumFogShaderRewrite() { }

    /** Validate the effective pair together, including resource-pack overrides. */
    public static String[] rewrite(String vertex,String fragment) {
        vertex=normalize(vertex); fragment=normalize(fragment);
        if(!VERTEX_SHA256.equals(hash(vertex))||!FRAGMENT_SHA256.equals(hash(fragment)))
            throw new IllegalArgumentException("Unsupported Nothirium shader pair; preserve the original path");
        vertex=replace(vertex,"uniform mat4 u_ModelViewProjectionMatrix;",
            "uniform mat4 u_ModelViewProjectionMatrix;\nuniform mat4 iceFogModelView;\nuniform int iceFogDistance;",1);
        vertex=replace(vertex,"v_VertDistance = fog_distance(pos);",
            "vec4 iceEye = iceFogModelView * vec4(pos, 1.0);\n"
            +"    v_VertDistance = iceFogDistance == 1 ? length(iceEye.xyz) : -iceEye.z;",1);
        fragment=replace(fragment,"return t * t * (3.0 - 2.0 * t);","return t;",1);
        // Keep depth signed through clipping/interpolation; taking abs at a
        // vertex behind the near plane introduces a false fog gradient.
        fragment=replace(fragment,"apply_fog(color, v_VertDistance)","apply_fog(color, abs(v_VertDistance))",1);
        // Fixed-function fog blends RGB independently of the fog color's alpha.
        fragment=replace(fragment,"fogValue * u_FogColor.a","fogValue",3);
        return new String[]{vertex,fragment};
    }
    private static String replace(String text,String from,String to,int expected) {
        int count=0,at=0; while((at=text.indexOf(from,at))>=0) {count++;at+=from.length();}
        if(count!=expected) throw new IllegalArgumentException("Shader contract occurrence mismatch");
        return text.replace(from,to);
    }
    private static String normalize(String source) {
        if(source==null) throw new IllegalArgumentException("Missing shader source");
        source=source.replace("\r\n","\n").replace('\r','\n');
        // ResourceSupplier reads lines and appends a final LF even when the
        // packaged resource has none. Normalize only that trailing line break.
        while(source.endsWith("\n")) source=source.substring(0,source.length()-1);
        return source;
    }
    private static String hash(String source) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(byte value:digest) {hex.append(Character.forDigit((value>>>4)&15,16));hex.append(Character.forDigit(value&15,16));}
            return hex.toString();
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}

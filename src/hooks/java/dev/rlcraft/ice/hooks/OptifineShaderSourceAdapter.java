package dev.rlcraft.ice.hooks;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Captures OptiFine's include/option-resolved vertex/geometry/fragment text. */
final class OptifineShaderSourceAdapter implements OptimizerBytecodeAdapter {
    static final String SHADERS = "net/optifine/shaders/Shaders";
    static final String PROGRAM = "net/optifine/shaders/Program";
    static final String METHOD_DESC = "(L" + PROGRAM + ";Ljava/lang/String;)I";
    static final String BOOTSTRAP =
        "dev/rlcraft/ice/hooks/OptifineShaderSourceBootstrap";
    static final String SOURCE_DESC =
        "(ILjava/lang/CharSequence;Ljava/lang/Object;Ljava/lang/String;I)V";
    static final int VERTEX = 0;
    static final int GEOMETRY = 1;
    static final int FRAGMENT = 2;
    private static final String ARB = "org/lwjgl/opengl/ARBShaderObjects";

    @Override public byte[] transform(String transformedName, byte[] originalClass,
                                      TargetSpec target) {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode(Opcodes.ASM5);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (!SHADERS.equals(node.name)) {
            throw new IllegalStateException("OptiFine shader source target changed");
        }
        Map<String, Integer> methods = new LinkedHashMap<String, Integer>();
        methods.put("createVertShader", Integer.valueOf(VERTEX));
        methods.put("createGeomShader", Integer.valueOf(GEOMETRY));
        methods.put("createFragShader", Integer.valueOf(FRAGMENT));
        for (Map.Entry<String, Integer> entry : methods.entrySet()) {
            MethodNode method = require(node, entry.getKey());
            patch(method, entry.getValue().intValue());
        }
        ClassWriter writer = new SafeClassWriter(reader,
            ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode require(ClassNode node, String name) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !METHOD_DESC.equals(method.desc)) continue;
            if (found != null) throw new IllegalStateException(
                "duplicate OptiFine shader source method " + name);
            found = method;
        }
        if (found == null || (found.access & Opcodes.ACC_STATIC) == 0
            || (found.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException("OptiFine shader source method changed: "
                + name);
        }
        return found;
    }

    private static void patch(MethodNode method, int stage) {
        MethodInsnNode source = null;
        int create = 0;
        int submit = 0;
        int compile = 0;
        int status = 0;
        int reader = 0;
        int includes = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (ARB.equals(call.owner) && "glCreateShaderObjectARB".equals(call.name)
                && "(I)I".equals(call.desc)) create++;
            if (ARB.equals(call.owner) && "glShaderSourceARB".equals(call.name)
                && "(ILjava/lang/CharSequence;)V".equals(call.desc)) {
                source = call;
                submit++;
            }
            if (ARB.equals(call.owner) && "glCompileShaderARB".equals(call.name)
                && "(I)V".equals(call.desc)) compile++;
            if ("org/lwjgl/opengl/GL20".equals(call.owner)
                && "glGetShaderi".equals(call.name) && "(II)I".equals(call.desc)) {
                status++;
            }
            if (SHADERS.equals(call.owner) && "getShaderReader".equals(call.name)
                && "(Ljava/lang/String;)Ljava/io/Reader;".equals(call.desc)) reader++;
            if ("net/optifine/shaders/config/ShaderPackParser".equals(call.owner)
                && "resolveIncludes".equals(call.name)) includes++;
        }
        if (create != 1 || submit != 1 || compile != 1 || status != 1
            || reader != 1 || includes != 1 || source == null) {
            throw new IllegalStateException("OptiFine resolved shader graph changed: "
                + method.name + '=' + create + '/' + submit + '/' + compile
                + '/' + status + '/' + reader + '/' + includes);
        }
        InsnList metadata = new InsnList();
        metadata.add(new VarInsnNode(Opcodes.ALOAD, 0));
        metadata.add(new VarInsnNode(Opcodes.ALOAD, 1));
        metadata.add(new InsnNode(stage == VERTEX ? Opcodes.ICONST_0
            : stage == GEOMETRY ? Opcodes.ICONST_1 : Opcodes.ICONST_2));
        method.instructions.insertBefore(source, metadata);
        source.owner = BOOTSTRAP;
        source.name = "submit";
        source.desc = SOURCE_DESC;
        source.itf = false;
        source.setOpcode(Opcodes.INVOKESTATIC);
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override protected String getCommonSuperClass(String left, String right) {
            return "java/lang/Object";
        }
    }
}

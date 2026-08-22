import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Small, dependency-free report used to review exact 1.12.2 target bytecode. */
public final class BytecodeAudit {
    private BytecodeAudit() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("usage: BytecodeAudit <jar> <class>...");
        }
        JarFile jar = new JarFile(arguments[0]);
        try {
            for (int i = 1; i < arguments.length; i++) audit(jar, arguments[i]);
        } finally {
            jar.close();
        }
    }

    private static void audit(JarFile jar, String className) throws Exception {
        String methodFilter = null;
        int separator = className.indexOf('#');
        if (separator >= 0) {
            methodFilter = className.substring(separator + 1);
            className = className.substring(0, separator);
        }
        String entryName = className.replace('.', '/') + ".class";
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) throw new IllegalArgumentException("missing " + entryName);
        byte[] bytes = read(jar.getInputStream(entry));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        System.out.println("CLASS " + className);
        System.out.println("SHA256 " + sha256(bytes));
        System.out.println("SUPER " + node.superName + " FIELDS " + node.fields.size() + " METHODS " + node.methods.size());
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            if (methodFilter == null || method.name.equals(methodFilter)) auditMethod(method);
        }
        System.out.println();
    }

    private static void auditMethod(MethodNode method) {
        int instructions = 0;
        int renders = 0;
        int children = 0;
        int modelWrites = 0;
        Map<String, Integer> allocations = new LinkedHashMap<String, Integer>();
        Map<String, Integer> interestingCalls = new LinkedHashMap<String, Integer>();
        List<String> renderSites = new ArrayList<String>();
        List<String> writes = new ArrayList<String>();
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) instructions++;
            if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW) {
                increment(allocations, ((TypeInsnNode) instruction).desc);
            } else if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.PUTFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if ("net/minecraft/client/model/ModelRenderer".equals(field.owner)) {
                    modelWrites++;
                    if (writes.size() < 24) writes.add(field.owner + "." + field.name + field.desc);
                }
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("net/minecraft/client/model/ModelRenderer".equals(call.owner)
                    && ("func_78785_a".equals(call.name) || "render".equals(call.name))
                    && "(F)V".equals(call.desc)) {
                    renders++;
                    renderSites.add(context(instruction));
                }
                if ("net/minecraft/client/model/ModelRenderer".equals(call.owner)
                    && ("func_78792_a".equals(call.name) || "addChild".equals(call.name))) children++;
                if (interesting(call.owner, call.name)) increment(interestingCalls,
                    call.owner + "." + call.name + call.desc);
            }
        }
        System.out.println(" METHOD " + method.name + method.desc + " insns=" + instructions
            + " renders=" + renders + " addChild=" + children + " modelWrites=" + modelWrites);
        if (!allocations.isEmpty()) System.out.println("  NEW " + allocations);
        if (!interestingCalls.isEmpty()) System.out.println("  CALLS " + interestingCalls);
        for (String site : renderSites) System.out.println("  RENDER " + site);
        if (!writes.isEmpty()) System.out.println("  WRITES " + writes + (modelWrites > writes.size() ? " ..." : ""));
    }

    private static boolean interesting(String owner, String name) {
        return owner.startsWith("net/minecraft/pathfinding/")
            || owner.startsWith("net/minecraft/world/")
            || owner.startsWith("net/minecraft/block/")
            || owner.startsWith("net/minecraft/entity/ai/")
            || owner.startsWith("com/dhanantry/scapeandrunparasites/")
            || owner.startsWith("com/lycanitesmobs/")
            || name.toLowerCase().contains("effect")
            || name.toLowerCase().contains("path");
    }

    private static String context(AbstractInsnNode center) {
        List<String> values = new ArrayList<String>();
        AbstractInsnNode cursor = center;
        for (int i = 0; i < 7 && cursor != null; i++) {
            values.add(describe(cursor));
            cursor = cursor.getPrevious();
        }
        Collections.reverse(values);
        return values.toString();
    }

    private static String describe(AbstractInsnNode instruction) {
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) instruction;
            return opcode(field.getOpcode()) + " " + field.owner + "." + field.name + field.desc;
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            return opcode(call.getOpcode()) + " " + call.owner + "." + call.name + call.desc;
        }
        if (instruction instanceof TypeInsnNode) {
            TypeInsnNode type = (TypeInsnNode) instruction;
            return opcode(type.getOpcode()) + " " + type.desc;
        }
        return opcode(instruction.getOpcode());
    }

    private static String opcode(int opcode) {
        if (opcode < 0) return "meta";
        try {
            return org.objectweb.asm.util.Printer.OPCODES[opcode];
        } catch (Throwable ignored) {
            return String.valueOf(opcode);
        }
    }

    private static void increment(Map<String, Integer> values, String key) {
        Integer previous = values.get(key);
        values.put(key, previous == null ? 1 : previous + 1);
    }

    private static byte[] read(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) value.append(String.format("%02x", item & 255));
        return value.toString();
    }
}

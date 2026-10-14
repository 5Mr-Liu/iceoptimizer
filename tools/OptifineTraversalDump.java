package dev.rlcraft.ice.hooks;

import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;

/** Read-only disassembly of the actual OptiFine-patched traversal. */
public final class OptifineTraversalDump {
    public static void main(String[] args) throws Exception {
        try (OptifinePatchedClassSupport source = OptifinePatchedClassSupport.openOrSkip();
             PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[0]),"UTF-8"))) {
            byte[] bytes = source.patchAndRemap(args.length > 1 ? args[1] : "buy",
                args.length > 2 ? args[2] : "net.minecraft.client.renderer.RenderGlobal");
            final String selected = args.length > 3 ? args[3] : "func_174970_a";
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5,new TraceClassVisitor(out)) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                    return selected.equals("ALL") || name.equals(selected) ? super.visitMethod(access,name,desc,sig,exceptions) : null;
                }
            },0);
        }
    }
}

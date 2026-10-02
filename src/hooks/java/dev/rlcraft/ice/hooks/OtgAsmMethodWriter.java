package dev.rlcraft.ice.hooks;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

/** Keep unrelated OTG methods/frames verbatim; only edited methods need new frames. */
final class OtgAsmMethodWriter {
    private OtgAsmMethodWriter() { }
    static byte[] write(ClassReader reader, ClassWriter writer, final MethodNode... edited) {
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                for (MethodNode method : edited) if (method.name.equals(name) && method.desc.equals(descriptor)) return null;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            @Override public void visitEnd() {
                for (MethodNode method : edited) method.accept(cv);
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}

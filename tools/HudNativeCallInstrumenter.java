import java.nio.file.*;
import org.objectweb.asm.*;

/** Offline-only call counting, kept entirely outside the shipped renderer. */
public final class HudNativeCallInstrumenter {
    public static void main(String[] args) throws Exception {
        for (int i=2;i<args.length;i++) {
            Path source=Paths.get(args[0],args[i]+".class"), output=Paths.get(args[1],args[i]+".class");
            ClassReader reader=new ClassReader(Files.readAllBytes(source));
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM5,writer) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5,super.visitMethod(access,name,desc,signature,exceptions)) {
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf) {
                            if (owner.startsWith("org/lwjgl/opengl/") && method.startsWith("gl")
                                || method.equals("callLegacy")) {
                                super.visitLdcInsn(method);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/rlcraft/ice/optimizer/client/HudBoundaryNativeProbe","count","(Ljava/lang/String;)V",false);
                            }
                            super.visitMethodInsn(opcode,owner,method,descriptor,itf);
                        }
                    };
                }
            },0);
            Files.createDirectories(output.getParent());Files.write(output,writer.toByteArray());
        }
    }
}

import java.nio.file.*;
import org.objectweb.asm.*;

/** Instruments scratch class copies only; never used for timing or packaged code. */
public final class TerrainGlCallInstrumenter {
    public static void main(String[] args) throws Exception {
        final Path source = Paths.get(args[0]), target = Paths.get(args[1]);
        Files.walk(source).filter(path -> path.toString().endsWith(".class")).forEach(path -> {
            try {
                Path output = target.resolve(source.relativize(path));
                Files.createDirectories(output.getParent());
                byte[] bytes = Files.readAllBytes(path);
                String name = path.getFileName().toString();
                if (name.startsWith("LwjglTerrainBatchRenderer") || name.startsWith("LegacyTerrainBatchRenderer")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, method, desc, signature, exceptions)) {
                                @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                                    if (owner.startsWith("org/lwjgl/opengl/") && name.startsWith("gl")) {
                                        super.visitLdcInsn(name);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "dev/rlcraft/ice/optimizer/render/terrain/TerrainScopeNativeProbe", "count", "(Ljava/lang/String;)V", false);
                                    }
                                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                                }
                            };
                        }
                    }, 0);
                    bytes = writer.toByteArray();
                }
                Files.write(output, bytes);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
}

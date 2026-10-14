package dev.rlcraft.ice.hooks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Applies the production SRG font adapter to the mapped development class. Test-only. */
public final class HudFontProbePrepare {
    public static void main(String[] args) throws Exception {
        Map<String, String> forward = new HashMap<>(), reverse = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            String[] p = line.split(" ");
            if (p[0].equals("MD:")) {
                forward.put(key(p[1]) + p[2], simple(p[3]));
                reverse.put(key(p[3]) + p[4], simple(p[1]));
            } else if (p[0].equals("FD:")) {
                forward.put(key(p[1]), simple(p[2])); reverse.put(key(p[2]), simple(p[1]));
            }
        }
        String font = "net/minecraft/client/gui/FontRenderer";
        byte[] srg = remap(read(font), forward);
        byte[] patched = new HudRenderAdapter(HudRenderAdapter.Part.FONT).transform(
            font.replace('/', '.'), srg, null);
        write(args[1], font, remap(patched, reverse));
        String gl = "net/minecraft/client/renderer/GlStateManager";
        ClassReader reader = new ClassReader(read(gl));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int a, String name, String d, String s, String[] e) {
                MethodVisitor mv = super.visitMethod(a, name, d, s, e);
                boolean texture = name.equals("bindTexture") && d.equals("(I)V");
                boolean color = name.equals("color") && d.equals("(FFFF)V");
                if (!texture && !color) return mv;
                return new MethodVisitor(Opcodes.ASM5, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (texture) super.visitVarInsn(Opcodes.ILOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "dev/rlcraft/ice/optimizer/compat/hud/HudRenderBridge",
                            texture ? "textureBarrier" : "barrier", texture ? "(I)V" : "()V", false);
                    }
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if (texture) super.visitVarInsn(Opcodes.ILOAD, 0);
                            else for (int i = 0; i < 4; i++) super.visitVarInsn(Opcodes.FLOAD, i);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/rlcraft/ice/optimizer/compat/gl/EarlyGlStateTracker",
                                texture ? "bindTexture" : "color", d, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        write(args[1], gl, writer.toByteArray());
        System.out.println("Prepared real mapped FontRenderer with production HUD adapter");
    }
    private static String key(String name) {
        int split = name.lastIndexOf('/'); return name.substring(0, split) + '.' + name.substring(split + 1);
    }
    private static String simple(String name) { return name.substring(name.lastIndexOf('/') + 1); }
    private static byte[] remap(byte[] bytes, Map<String, String> names) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
        return writer.toByteArray();
    }
    private static byte[] read(String name) throws IOException {
        try (InputStream input = HudFontProbePrepare.class.getClassLoader().getResourceAsStream(name + ".class")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
            int n; while ((n = input.read(buffer)) >= 0) bytes.write(buffer, 0, n); return bytes.toByteArray();
        }
    }
    private static void write(String root, String name, byte[] bytes) throws IOException {
        Path output = Paths.get(root, name + ".class"); Files.createDirectories(output.getParent()); Files.write(output, bytes);
    }
}

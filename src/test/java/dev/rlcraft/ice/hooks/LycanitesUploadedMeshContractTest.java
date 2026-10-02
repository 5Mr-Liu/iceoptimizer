package dev.rlcraft.ice.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Verifies the native ownership boundary used by the uploaded mesh shortcut. */
public final class LycanitesUploadedMeshContractTest {
    private static final String MESH = "com/lycanitesmobs/client/obj/Mesh";

    @Test
    public void reviewedVboDrawReadsOnlyUploadedBufferAndIndexCount() throws Exception {
        String configured = System.getProperty("ice.lycanites.jar", "").trim();
        Assume.assumeTrue("run with -PlycanitesJar=<jar>", !configured.isEmpty());
        File source = new File(configured);
        assertTrue(source.isFile());
        try (JarFile jar = new JarFile(source)) {
            MethodNode draw = method(read(jar, "com/lycanitesmobs/client/obj/VBOModel"),
                "renderGroupImpl");
            int uploads = 0;
            int indexCounts = 0;
            for (AbstractInsnNode instruction : code(draw)) {
                assertTrue("VBO draw must not read CPU array elements",
                    instruction.getOpcode() != Opcodes.IALOAD
                        && instruction.getOpcode() != Opcodes.AALOAD);
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (MESH.equals(field.owner)) {
                        assertEquals(Opcodes.GETFIELD, field.getOpcode());
                        assertEquals("indices", field.name);
                        indexCounts++;
                    }
                }
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (MESH.equals(call.owner) && "getVbo".equals(call.name)) uploads++;
                }
            }
            assertEquals(1, uploads);
            assertEquals(1, indexCounts);

            ClassNode mesh = read(jar, MESH);
            List<AbstractInsnNode> upload = code(method(mesh, "getVbo"));
            int[] uploadedEarlyReturn = { Opcodes.ALOAD, Opcodes.GETFIELD,
                Opcodes.ICONST_M1, Opcodes.IF_ICMPEQ, Opcodes.ALOAD,
                Opcodes.GETFIELD, Opcodes.IRETURN };
            for (int i = 0; i < uploadedEarlyReturn.length; i++) {
                assertEquals("getVbo must return its published name before reading arrays",
                    uploadedEarlyReturn[i], upload.get(i).getOpcode());
            }
            assertVboField(upload.get(1));
            assertVboField(upload.get(5));

            List<AbstractInsnNode> deletion = code(method(mesh, "delete"));
            assertEquals(7, deletion.size());
            assertEquals(Opcodes.INVOKESTATIC, deletion.get(2).getOpcode());
            MethodInsnNode destroy = (MethodInsnNode) deletion.get(2);
            assertEquals("org/lwjgl/opengl/GL15", destroy.owner);
            assertEquals("glDeleteBuffers", destroy.name);
            assertEquals(Opcodes.ICONST_M1, deletion.get(4).getOpcode());
            assertVboField(deletion.get(5));
            assertEquals(Opcodes.PUTFIELD, deletion.get(5).getOpcode());
        }
    }

    private static void assertVboField(AbstractInsnNode instruction) {
        assertTrue(instruction instanceof FieldInsnNode);
        FieldInsnNode field = (FieldInsnNode) instruction;
        assertEquals(MESH, field.owner);
        assertEquals("vbo", field.name);
        assertEquals("I", field.desc);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        assertNotNull(jar.getJarEntry(name + ".class"));
        ClassNode node = new ClassNode(Opcodes.ASM5);
        try (InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name)) return method;
        }
        throw new AssertionError("missing " + owner.name + '.' + name);
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
        }
        return instructions;
    }
}

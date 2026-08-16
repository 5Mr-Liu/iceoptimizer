package dev.rlcraft.ice.optimizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class OptimizationModuleLookupTest {
    @Test
    public void resolvesCanonicalIdsAndLegacyEnumNames() {
        for (OptimizationModule module : OptimizationModule.values()) {
            assertSame(module, OptimizationModule.byId(module.getId()));
            assertSame(module, OptimizationModule.byId(module.name()));
            assertSame(module, OptimizationModule.byId(module.name().toLowerCase(java.util.Locale.ROOT)));
        }
        assertNull(OptimizationModule.byId(null));
        assertNull(OptimizationModule.byId("BETTER-CAVES-NOISE"));
        assertNull(OptimizationModule.byId("not-a-module"));
    }

    @Test
    public void canonicalLookupDoesNotRegressToLinearEnumScanning() throws Exception {
        String resource = "/" + OptimizationModule.class.getName().replace('.', '/') + ".class";
        InputStream input = OptimizationModule.class.getResourceAsStream(resource);
        assertNotNull(input);
        final boolean[] linearScan = { false };
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!"byId".equals(name) || !"(Ljava/lang/String;)Ldev/rlcraft/ice/optimizer/OptimizationModule;"
                        .equals(descriptor)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String calledDescriptor, boolean isInterface) {
                            if (("dev/rlcraft/ice/optimizer/OptimizationModule".equals(owner)
                                && "values".equals(calledName))
                                || ("java/lang/String".equals(owner)
                                && "equalsIgnoreCase".equals(calledName))) {
                                linearScan[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            if (input != null) input.close();
        }
        assertFalse("module lookup must stay O(1) on canonical ids", linearScan[0]);
    }
}

package dev.rlcraft.ice.optimizer.compat.bettercaves;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.rlcraft.ice.optimizer.ModuleCircuitBreaker;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class BetterCavesOptimizationBridgeTest {
    @Test
    public void cachedBreakerStillReadsLiveModuleState() {
        ModuleCircuitBreaker breaker = OptimizerRegistry.breaker(OptimizationModule.BETTER_CAVES_NOISE);
        breaker.configure(true, 3);
        breaker.patchInstalled("test.NoiseTuple", "test");
        try {
            assertTrue(BetterCavesOptimizationBridge.isEnabled());
            breaker.configure(false, 3);
            assertFalse(BetterCavesOptimizationBridge.isEnabled());
            breaker.configure(true, 3);
            assertTrue(BetterCavesOptimizationBridge.isEnabled());
        } finally {
            breaker.configure(false, 3);
        }
    }

    @Test
    public void hotGateDoesNotResolveStringModuleIds() throws Exception {
        String resource = "/" + BetterCavesOptimizationBridge.class.getName().replace('.', '/') + ".class";
        InputStream input = BetterCavesOptimizationBridge.class.getResourceAsStream(resource);
        assertNotNull(input);
        final boolean[] stringLookup = { false };
        final boolean[] liveBreakerRead = { false };
        try {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!"isEnabled".equals(name) || !"()Z".equals(descriptor)) return null;
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String calledDescriptor, boolean isInterface) {
                            if (("dev/rlcraft/ice/optimizer/bridge/OptimizerBridge".equals(owner)
                                && "isEnabled".equals(calledName))
                                || ("dev/rlcraft/ice/optimizer/OptimizationModule".equals(owner)
                                && "byId".equals(calledName))) {
                                stringLookup[0] = true;
                            }
                            if ("dev/rlcraft/ice/optimizer/ModuleCircuitBreaker".equals(owner)
                                && "isOperational".equals(calledName)) {
                                liveBreakerRead[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } finally {
            if (input != null) input.close();
        }
        assertFalse("Better Caves hot gate must not parse a module id", stringLookup[0]);
        assertTrue("Better Caves hot gate must preserve live circuit-breaker reads", liveBreakerRead[0]);
    }
}

package dev.rlcraft.ice.hooks;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Offline-only installation of the production compound adapter in a mapped JVM. */
public final class NbtProbeAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!NbtCompoundAdapter.TARGET.equals(name)) return null;
                return new NbtCompoundAdapter().transform(name.replace('/', '.'), bytes, null);
            }
        });
    }
}

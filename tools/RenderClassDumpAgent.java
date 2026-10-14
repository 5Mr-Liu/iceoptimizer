package dev.rlcraft.ice.benchmark;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Read-only capture after LaunchWrapper/Mixin transformations, before definition. */
public final class RenderClassDumpAgent {
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        Path game = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(game.resolve("ICE_ISOLATED_BENCHMARK.marker")))
            throw new IllegalStateException("Class capture requires an isolated benchmark game");
        final Path output = game.resolve("ice-benchmark/class-dump");
        final boolean sections = "sections".equals(arguments);
        final boolean gameplay = "gameplay".equals(arguments);
        Files.createDirectories(output);
        final Set<String> selected = new HashSet<>(Arrays.asList(
            "net/minecraft/client/renderer/RenderGlobal",
            "net/minecraft/client/renderer/chunk/RenderChunk",
            "net/minecraft/client/renderer/chunk/CompiledChunk",
            "net/minecraft/client/renderer/chunk/SetVisibility",
            "net/minecraft/client/renderer/culling/Frustum",
            "net/minecraft/client/renderer/culling/ClippingHelper",
            "net/minecraft/client/renderer/ChunkRenderContainer",
            "net/minecraft/client/renderer/VboRenderList"));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if(gameplay&&loader!=null)try {
                    return GameplayRandomInstrumenter.transform(name,bytes);
                } catch(Throwable failure) {
                    System.setProperty("ice.bench.randomHook.failed","true");
                    System.err.println("ICE_BENCH deterministic_random_failed="+name+":"+failure);
                    return null;
                }
                if (name != null && selected.contains(name)) try {
                    Files.write(output.resolve(name.replace('/', '_') + ".class"), bytes);
                } catch (Exception failure) {
                    System.err.println("ICE_BENCH class_capture_failed=" + name + ":" + failure.getClass().getName());
                }
                if (sections && loader != null) try {
                    byte[] timed = RenderSectionInstrumenter.transform(name, bytes);
                    if (timed != null) {
                        loader.getClass().getMethod("addClassLoaderExclusion", String.class)
                            .invoke(loader, "dev.rlcraft.ice.benchmark.timing.");
                        return timed;
                    }
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                    if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                    System.err.println("ICE_BENCH section_instrumentation_failed=" + name + ":" + failure);
                }
                return null; // Default mode never changes execution or bytecode.
            }
        });
    }
}

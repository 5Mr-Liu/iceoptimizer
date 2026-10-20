package dev.rlcraft.ice.benchmark;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.security.ProtectionDomain;

/** External read-only shader crash evidence; never part of the optimizer JAR. */
public final class ShaderClassDumpAgent {
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        Path game = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(game.resolve("ICE_ISOLATED_BENCHMARK.marker")))
            throw new IllegalStateException("Shader class capture requires an isolated game");
        final Path output = game.resolve("ice-benchmark/shader-classes");
        Files.createDirectories(output);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if ("net/minecraft/client/renderer/BufferBuilder".equals(name)
                    || "net/minecraft/client/renderer/chunk/RenderChunk".equals(name)
                    || "net/minecraft/client/renderer/chunk/ChunkRenderWorker".equals(name)
                    || "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher".equals(name)
                    || "net/minecraft/client/renderer/chunk/ChunkCompileTaskGenerator".equals(name)
                    || "net/optifine/shaders/Shaders".equals(name)
                    || "mods/octarinecore/client/render/ModelRenderer".equals(name)
                    || "net/optifine/shaders/SVertexBuilder".equals(name)
                    || "net/minecraft/client/renderer/vertex/DefaultVertexFormats".equals(name)) {
                    try { Files.write(output.resolve(name.replace('/', '_') + ".class"), bytes); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return null;
            }
        });
    }
}

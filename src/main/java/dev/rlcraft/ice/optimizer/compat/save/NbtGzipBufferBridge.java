package dev.rlcraft.ice.optimizer.compat.save;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/** Larger compressed-output batches; standard gzip lifecycle and defaults. */
public final class NbtGzipBufferBridge {
    private static final int MODULE = OptimizationModule.VANILLA_CHUNK_COMPRESSION.ordinal();
    private NbtGzipBufferBridge() { }

    public static OutputStream create(OutputStream output) throws IOException {
        return OptimizerBridge.isEnabled(MODULE)
            ? buffered(output) : new GZIPOutputStream(output);
    }

    static GZIPOutputStream buffered(OutputStream output) throws IOException {
        return new GZIPOutputStream(output, 64 * 1024);
    }
}

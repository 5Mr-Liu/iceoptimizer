package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

/** Same default zlib stream; reuse only the output scratch, never a native compressor. */
public final class OtgStructureCompressionBridge {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[BUFFER_BYTES]; }
    };
    private OtgStructureCompressionBridge() { }

    public static boolean isEnabled() {
        try { return OptimizerRegistry.isOperational(OptimizationModule.OTG_SYNC_FILE_CACHE); }
        catch (LinkageError | RuntimeException unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return false;
        }
    }

    public static byte[] compress(byte[] input) throws IOException {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
            deflater.finish();
            byte[] scratch = SCRATCH.get();
            while (!deflater.finished()) {
                int count = deflater.deflate(scratch);
                output.write(scratch, 0, count);
            }
            output.close();
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }
}

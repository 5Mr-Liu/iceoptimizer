package dev.rlcraft.ice.optimizer.compat.otg;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;

/** Same default zlib stream, with one reusable compressor per calling thread. */
public final class OtgStructureCompressionBridge {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final ThreadLocal<Workspace> WORKSPACE = new ThreadLocal<Workspace>() {
        @Override protected Workspace initialValue() { return new Workspace(); }
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
        if (input == null) throw new NullPointerException("input");
        Workspace workspace = WORKSPACE.get();
        boolean borrowed = !workspace.inUse;
        Deflater deflater = borrowed ? workspace.deflater : new Deflater();
        if (borrowed) workspace.inUse = true;
        try {
            deflater.setInput(input);
            deflater.finish();
            // Write directly into the owned result; no input-sized preallocation
            // and no scratch -> ByteArrayOutputStream copy on every deflate call.
            byte[] output = new byte[Math.min(BUFFER_BYTES, Math.max(32, input.length / 8))];
            int length = 0;
            while (!deflater.finished()) {
                if (length == output.length) {
                    int expanded = output.length <= (Integer.MAX_VALUE - 8) / 2
                        ? output.length * 2 : Integer.MAX_VALUE - 8;
                    if (expanded <= output.length) throw new OutOfMemoryError("compressed structure too large");
                    output = Arrays.copyOf(output, expanded);
                }
                length += deflater.deflate(output, length, output.length - length);
            }
            return length == output.length ? output : Arrays.copyOf(output, length);
        } finally {
            if (borrowed) {
                try { deflater.reset(); }
                finally { workspace.inUse = false; }
            } else deflater.end();
        }
    }

    private static final class Workspace {
        private final Deflater deflater = new Deflater();
        private boolean inUse;
    }
}

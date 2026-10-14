package dev.rlcraft.ice.optimizer.compat.otg;

import java.io.File;
import java.util.Map;

/** Retains the installed registration-hook ABI without speculative file I/O. */
public final class OtgObjectPreparationBridge {
    private OtgObjectPreparationBridge() { }

    public static void prepare(Map<String, File> registered) {
        // OTG already owns the name -> file index. Its actual settings/metadata
        // loaders enter OtgSynchronousIoBridge on demand, with bounded caches
        // and mutation authentication. Walking all registered files and then
        // their dependency graph reads unused objects and duplicates that work.
    }
}

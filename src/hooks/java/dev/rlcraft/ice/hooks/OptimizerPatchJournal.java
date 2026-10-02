package dev.rlcraft.ice.hooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Retains early patch status until the normal Forge runtime is initialized,
 * without linking the startup classes against that runtime.
 */
public final class OptimizerPatchJournal {
    private static final int TARGET_OBSERVED = 1;
    private static final int PATCH_INSTALLED = 2;
    private static final List<Record> RECORDS = new ArrayList<Record>();
    private static boolean runtimeReady;
    private static boolean pluginInitialized;
    private static boolean transformerInitialized;

    private OptimizerPatchJournal() {
    }

    static synchronized void pluginInitialized() {
        pluginInitialized = true;
    }

    static synchronized void transformerInitialized() {
        transformerInitialized = true;
    }

    /** Read once by runtime initialization; class presence alone proves nothing. */
    public static synchronized boolean isStartupRegistered() {
        return pluginInitialized && transformerInitialized;
    }

    static synchronized void targetObserved(String moduleId, String className, String fingerprint, boolean supported) {
        Record record = new Record(TARGET_OBSERVED, moduleId, className, fingerprint, supported);
        RECORDS.add(record);
        if (runtimeReady) record.delivered = deliver(record);
    }

    static synchronized void patchInstalled(String moduleId, String className, String fingerprint) {
        Record record = new Record(PATCH_INSTALLED, moduleId, className, fingerprint, true);
        RECORDS.add(record);
        if (runtimeReady) record.delivered = deliver(record);
    }

    public static synchronized void replay() {
        runtimeReady = true;
        for (Record record : RECORDS) {
            if (!record.delivered) record.delivered = deliver(record);
        }
    }

    private static boolean deliver(Record record) {
        if (record.kind == TARGET_OBSERVED) {
            return OptimizerRegistryLink.targetObserved(record.moduleId, record.className,
                record.fingerprint, record.supported);
        }
        return OptimizerRegistryLink.patchInstalled(record.moduleId, record.className, record.fingerprint);
    }

    private static final class Record {
        private final int kind;
        private final String moduleId;
        private final String className;
        private final String fingerprint;
        private final boolean supported;
        private boolean delivered;

        private Record(int kind, String moduleId, String className, String fingerprint, boolean supported) {
            this.kind = kind;
            this.moduleId = moduleId;
            this.className = className;
            this.fingerprint = fingerprint;
            this.supported = supported;
        }
    }
}

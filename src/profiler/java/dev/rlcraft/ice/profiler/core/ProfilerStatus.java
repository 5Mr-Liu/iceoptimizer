package dev.rlcraft.ice.profiler.core;

import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProfilerStatus {
    private final boolean initialized;
    private final boolean recording;
    private final boolean deepMode;
    private final boolean manualSession;
    private final String sessionId;
    private final long sessionDurationMillis;
    private final long triggers;
    private final boolean activeCapture;
    private final TimelinePoint latest;
    private final List<String> diagnoses;
    private final int uniqueStacks;
    private final long stackOverflows;
    private final File lastReport;
    private final String exportError;

    public ProfilerStatus(boolean initialized, boolean recording, boolean deepMode, boolean manualSession, String sessionId, long sessionDurationMillis,
                          long triggers, boolean activeCapture, TimelinePoint latest, List<String> diagnoses,
                          int uniqueStacks, long stackOverflows, File lastReport, String exportError) {
        this.initialized = initialized;
        this.recording = recording;
        this.deepMode = deepMode;
        this.manualSession = manualSession;
        this.sessionId = sessionId;
        this.sessionDurationMillis = sessionDurationMillis;
        this.triggers = triggers;
        this.activeCapture = activeCapture;
        this.latest = latest;
        this.diagnoses = Collections.unmodifiableList(new ArrayList<String>(diagnoses));
        this.uniqueStacks = uniqueStacks;
        this.stackOverflows = stackOverflows;
        this.lastReport = lastReport;
        this.exportError = exportError;
    }

    public boolean isInitialized() { return initialized; }
    public boolean isRecording() { return recording; }
    public boolean isDeepMode() { return deepMode; }
    public boolean isManualSession() { return manualSession; }
    public String getSessionId() { return sessionId; }
    public long getSessionDurationMillis() { return sessionDurationMillis; }
    public long getTriggers() { return triggers; }
    public boolean isActiveCapture() { return activeCapture; }
    public TimelinePoint getLatest() { return latest; }
    public List<String> getDiagnoses() { return diagnoses; }
    public int getUniqueStacks() { return uniqueStacks; }
    public long getStackOverflows() { return stackOverflows; }
    public File getLastReport() { return lastReport; }
    public String getExportError() { return exportError; }
}

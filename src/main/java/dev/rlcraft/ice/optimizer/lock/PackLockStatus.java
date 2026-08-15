package dev.rlcraft.ice.optimizer.lock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PackLockStatus {
    private final PackLockState state;
    private final String detail;
    private final List<PackComponent> observedComponents;
    private final File observationFile;

    public PackLockStatus(PackLockState state, String detail, List<PackComponent> observedComponents, File observationFile) {
        this.state = state;
        this.detail = detail == null ? "" : detail;
        this.observedComponents = Collections.unmodifiableList(new ArrayList<PackComponent>(observedComponents));
        this.observationFile = observationFile;
    }

    public PackLockState getState() { return state; }
    public String getDetail() { return detail; }
    public List<PackComponent> getObservedComponents() { return observedComponents; }
    public File getObservationFile() { return observationFile; }
    public boolean permitsPatches() {
        return state == PackLockState.CAPABILITY || state == PackLockState.VERIFIED
            || state == PackLockState.DISABLED;
    }
}

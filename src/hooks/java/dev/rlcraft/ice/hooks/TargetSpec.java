package dev.rlcraft.ice.hooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

final class TargetSpec {
    final String className;
    final String moduleId;
    final List<String> moduleIds;
    final String adapterId;
    private final Set<String> acceptedFingerprints;

    TargetSpec(String className, String moduleId, String adapterId, Set<String> acceptedFingerprints) {
        this.className = className;
        this.moduleId = moduleId;
        List<String> modules = new ArrayList<String>();
        for (String value : moduleId.split(",")) {
            String clean = value.trim();
            if (!clean.isEmpty()) modules.add(clean);
        }
        this.moduleIds = Collections.unmodifiableList(modules);
        this.adapterId = adapterId;
        this.acceptedFingerprints = Collections.unmodifiableSet(new HashSet<String>(acceptedFingerprints));
    }

    boolean accepts(String fingerprint) {
        return acceptedFingerprints.contains(fingerprint);
    }

    boolean hasReviewedFingerprint(String fingerprint) {
        return accepts(fingerprint);
    }
}

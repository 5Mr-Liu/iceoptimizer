package dev.rlcraft.ice.profiler.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Diagnosis {
    private final RootCause rootCause;
    private final ModIdentity mod;
    private final String hotMethod;
    private final double confidence;
    private final List<String> evidence;
    private final List<String> recommendations;

    public Diagnosis(
        RootCause rootCause,
        ModIdentity mod,
        String hotMethod,
        double confidence,
        List<String> evidence,
        List<String> recommendations
    ) {
        this.rootCause = rootCause;
        this.mod = mod == null ? ModIdentity.UNKNOWN : mod;
        this.hotMethod = hotMethod == null ? "未识别" : hotMethod;
        this.confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        this.evidence = Collections.unmodifiableList(new ArrayList<String>(evidence));
        this.recommendations = Collections.unmodifiableList(new ArrayList<String>(recommendations));
    }

    public RootCause getRootCause() { return rootCause; }
    public ModIdentity getMod() { return mod; }
    public String getHotMethod() { return hotMethod; }
    public double getConfidence() { return confidence; }
    public List<String> getEvidence() { return evidence; }
    public List<String> getRecommendations() { return recommendations; }

    public String clusterKey() {
        return rootCause.name() + '|' + mod.getId() + '|' + hotMethod;
    }
}

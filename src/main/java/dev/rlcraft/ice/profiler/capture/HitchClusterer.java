package dev.rlcraft.ice.profiler.capture;

import dev.rlcraft.ice.profiler.analysis.Diagnosis;
import dev.rlcraft.ice.profiler.analysis.RootCauseAnalyzer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HitchClusterer {
    private final RootCauseAnalyzer analyzer;
    private final int maximumClusters;
    private final int representativesPerCluster;
    private final int maximumDetailedSamples;
    private final Map<String, HitchCluster> clusters = new LinkedHashMap<String, HitchCluster>();
    private int detailedSamples;
    private long discardedClusterEvents;

    public HitchClusterer(RootCauseAnalyzer analyzer, int maximumClusters, int representativesPerCluster, int maximumDetailedSamples) {
        this.analyzer = analyzer;
        this.maximumClusters = maximumClusters;
        this.representativesPerCluster = representativesPerCluster;
        this.maximumDetailedSamples = maximumDetailedSamples;
    }

    public synchronized HitchCluster add(HitchCapture capture) {
        capture.seal();
        Diagnosis diagnosis = analyzer.analyze(capture);
        capture.setDiagnosis(diagnosis);
        String key = diagnosis.clusterKey();
        HitchCluster cluster = clusters.get(key);
        if (cluster == null) {
            if (clusters.size() >= maximumClusters) {
                discardedClusterEvents++;
                return null;
            }
            cluster = new HitchCluster(key, diagnosis, representativesPerCluster);
            clusters.put(key, cluster);
        }
        int delta = cluster.add(capture, Math.max(0, maximumDetailedSamples - detailedSamples));
        detailedSamples = Math.max(0, detailedSamples + delta);
        return cluster;
    }

    public synchronized List<HitchCluster> snapshot() {
        List<HitchCluster> result = new ArrayList<HitchCluster>(clusters.values());
        Collections.sort(result, new Comparator<HitchCluster>() {
            @Override public int compare(HitchCluster left, HitchCluster right) {
                int occurrences = Long.compare(right.getOccurrences(), left.getOccurrences());
                return occurrences != 0 ? occurrences : Double.compare(right.getMaximumDurationMs(), left.getMaximumDurationMs());
            }
        });
        return Collections.unmodifiableList(result);
    }

    public synchronized int getDetailedSamples() { return detailedSamples; }
    public synchronized long getDiscardedClusterEvents() { return discardedClusterEvents; }
}

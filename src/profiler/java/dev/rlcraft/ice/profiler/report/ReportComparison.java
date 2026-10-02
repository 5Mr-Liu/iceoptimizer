package dev.rlcraft.ice.profiler.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReportComparison {
    private final String leftId;
    private final String rightId;
    private final List<String> lines;

    ReportComparison(String leftId, String rightId, List<String> lines) {
        this.leftId = leftId;
        this.rightId = rightId;
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    public String getLeftId() { return leftId; }
    public String getRightId() { return rightId; }
    public List<String> getLines() { return lines; }
}

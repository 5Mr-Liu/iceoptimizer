package dev.rlcraft.ice.optimizer.render.optifine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PreprocessedShader {
    private final String source;
    private final List<String> dependencies;
    private final int macroCount;

    PreprocessedShader(String source, List<String> dependencies, int macroCount) {
        this.source = source;
        this.dependencies = Collections.unmodifiableList(new ArrayList<String>(dependencies));
        this.macroCount = macroCount;
    }

    public String getSource() { return source; }
    public List<String> getDependencies() { return dependencies; }
    public int getMacroCount() { return macroCount; }
}

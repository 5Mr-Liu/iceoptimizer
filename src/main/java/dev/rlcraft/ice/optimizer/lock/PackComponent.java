package dev.rlcraft.ice.optimizer.lock;

public final class PackComponent {
    private final String modId;
    private final String name;
    private final String version;
    private final String sourceName;
    private final String sourceSha256;

    public PackComponent(String modId, String name, String version, String sourceName, String sourceSha256) {
        this.modId = clean(modId);
        this.name = clean(name);
        this.version = clean(version);
        this.sourceName = clean(sourceName);
        this.sourceSha256 = clean(sourceSha256);
    }

    public String getModId() { return modId; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getSourceName() { return sourceName; }
    public String getSourceSha256() { return sourceSha256; }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

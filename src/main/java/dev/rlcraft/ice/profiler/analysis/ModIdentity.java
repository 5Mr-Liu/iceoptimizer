package dev.rlcraft.ice.profiler.analysis;

public final class ModIdentity {
    public static final ModIdentity UNKNOWN = new ModIdentity("unknown", "未知来源", "");
    public static final ModIdentity MINECRAFT = new ModIdentity("minecraft", "Minecraft", "");
    public static final ModIdentity FORGE = new ModIdentity("forge", "Minecraft Forge", "");
    public static final ModIdentity JVM = new ModIdentity("jvm", "Java/JVM", "");
    public static final ModIdentity LWJGL = new ModIdentity("lwjgl", "LWJGL/显卡驱动", "");
    public static final ModIdentity ICE = new ModIdentity("ice", "ICE Optimizer/Profiler", "");

    private final String id;
    private final String name;
    private final String version;

    public ModIdentity(String id, String name, String version) {
        this.id = id == null ? "unknown" : id;
        this.name = name == null ? this.id : name;
        this.version = version == null ? "" : version;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
}

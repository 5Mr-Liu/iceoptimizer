package dev.rlcraft.ice.profiler.sampling;

public final class ThreadDescriptor {
    private final long id;
    private final String name;
    private final ThreadRole role;

    public ThreadDescriptor(long id, String name, ThreadRole role) {
        this.id = id;
        this.name = name == null ? "unknown" : name;
        this.role = role == null ? ThreadRole.OTHER : role;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public ThreadRole getRole() { return role; }
}

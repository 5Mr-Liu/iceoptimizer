package dev.rlcraft.ice.profiler.capture;

public enum TriggerType {
    CLIENT_FRAME("客户端长帧"),
    CLIENT_TICK("客户端长 Tick"),
    SERVER_TICK("服务端长 Tick"),
    GC_PAUSE("GC 暂停"),
    MANUAL("手动捕获"),
    MARKER("用户标记");

    private final String displayName;

    TriggerType(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}

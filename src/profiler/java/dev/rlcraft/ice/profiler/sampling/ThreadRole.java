package dev.rlcraft.ice.profiler.sampling;

public enum ThreadRole {
    CLIENT_MAIN("客户端主线程"),
    SERVER_MAIN("服务端主线程"),
    CHUNK_WORKER("区块构建线程"),
    CHUNK_IO("区块 I/O 线程"),
    NETWORK("网络线程"),
    FILE_IO("文件 I/O 线程"),
    WORKER("工作线程"),
    OTHER("其他线程");

    private final String displayName;

    ThreadRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

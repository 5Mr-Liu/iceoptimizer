package dev.rlcraft.ice.profiler.analysis;

public enum RootCause {
    GARBAGE_COLLECTION("JVM / 垃圾回收"),
    WORLD_GENERATION("世界生成"),
    CHUNK_LOADING("区块读取"),
    CHUNK_SAVING("区块保存"),
    CHUNK_LIGHTING("光照计算"),
    CHUNK_RENDERING("区块网格构建/上传"),
    ENTITY_TICK("实体 Tick"),
    TILE_ENTITY_TICK("方块实体 Tick"),
    AI_PATHFINDING("AI / 寻路"),
    COLLISION("碰撞计算"),
    EVENT_HANDLER("Forge 事件监听器"),
    NETWORK("网络收发/解包"),
    CLIENT_RENDER("客户端渲染"),
    GPU_DRIVER("GPU 驱动/同步等待"),
    FRAME_LIMITER_WAIT("帧率限制/主动等待"),
    ICE_RUNTIME("ICE 优化模块开销"),
    RESOURCE_LOADING("资源加载"),
    GAME_LIFECYCLE("世界加载/保存/关闭阶段"),
    THREAD_CONTENTION("线程阻塞/锁竞争"),
    JVM_CPU("JVM / CPU 饱和"),
    UNKNOWN("尚未确定");

    private final String displayName;

    RootCause(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}

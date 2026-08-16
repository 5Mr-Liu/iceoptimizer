package dev.rlcraft.ice.profiler.analysis;

import dev.rlcraft.ice.profiler.capture.HitchCapture;
import dev.rlcraft.ice.profiler.capture.HitchTrigger;
import dev.rlcraft.ice.profiler.capture.TriggerType;
import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.StackSampleFilter;
import dev.rlcraft.ice.profiler.sampling.StackTraceRepository;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RootCauseAnalyzer {
    private final StackTraceRepository stacks;
    private final ModResolver modResolver;

    public RootCauseAnalyzer(StackTraceRepository stacks, ModResolver modResolver) {
        this.stacks = stacks;
        this.modResolver = modResolver;
    }

    public Diagnosis analyze(HitchCapture capture) {
        AttributionSelection attribution = selectAttributionSamples(capture);
        EnumMap<RootCause, Integer> scores = new EnumMap<RootCause, Integer>(RootCause.class);
        Map<String, Integer> hotFrames = new HashMap<String, Integer>();
        Map<String, String> hotClasses = new HashMap<String, String>();
        Map<Integer, AnalyzedStack> analyzedStacks = new HashMap<Integer, AnalyzedStack>();
        int blocked = 0;
        int classifiedSamples = 0;
        int relevantSamples = 0;
        int idleWorkerSamples = 0;
        long allocationBytes = 0L;
        for (StackSample sample : capture.getSamples()) {
            StackTraceElement[] trace = stacks.get(sample.getStackTraceId());
            if (StackSampleFilter.isIdleWorkerWait(sample, trace)) idleWorkerSamples++;
        }
        for (StackSample sample : attribution.samples) {
            StackTraceElement[] trace = stacks.get(sample.getStackTraceId());
            if (StackSampleFilter.isIdleWorkerWait(sample, trace)) {
                continue;
            }
            relevantSamples++;
            if (sample.getState() == Thread.State.BLOCKED) blocked++;
            allocationBytes += sample.getAllocatedBytesDelta();
            Integer stackId = Integer.valueOf(sample.getStackTraceId());
            AnalyzedStack analyzed = analyzedStacks.get(stackId);
            if (analyzed == null) {
                StackTraceElement hot = firstActionable(trace);
                analyzed = new AnalyzedStack(classify(sample, trace), hot == null ? null : hot.getClassName(),
                    hot == null ? null : hot.getClassName() + "." + hot.getMethodName());
                analyzedStacks.put(stackId, analyzed);
            }
            RootCause classified = analyzed.cause;
            if (classified != RootCause.UNKNOWN) {
                increment(scores, classified, 1);
                classifiedSamples++;
            }
            if (analyzed.method != null) {
                String method = analyzed.method;
                Integer count = hotFrames.get(method);
                hotFrames.put(method, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
                hotClasses.put(method, analyzed.className);
            }
        }

        for (HitchTrigger trigger : capture.getTriggers()) {
            if (trigger.getType() == TriggerType.GC_PAUSE) increment(scores, RootCause.GARBAGE_COLLECTION, 12);
            if (trigger.getType() == TriggerType.CLIENT_FRAME) increment(scores, RootCause.CLIENT_RENDER, 1);
        }
        if (blocked >= Math.max(3, relevantSamples / 5)) increment(scores, RootCause.THREAD_CONTENTION, 8);

        RootCause winner = RootCause.UNKNOWN;
        int winnerScore = 0;
        int totalScore = 0;
        for (Map.Entry<RootCause, Integer> entry : scores.entrySet()) {
            totalScore += entry.getValue().intValue();
            if (entry.getValue().intValue() > winnerScore) {
                winner = entry.getKey();
                winnerScore = entry.getValue().intValue();
            }
        }

        String hotMethod = "未识别";
        String hotClass = null;
        int hotCount = 0;
        for (Map.Entry<String, Integer> entry : hotFrames.entrySet()) {
            if (entry.getValue().intValue() > hotCount) {
                hotMethod = entry.getKey();
                hotClass = hotClasses.get(entry.getKey());
                hotCount = entry.getValue().intValue();
            }
        }
        ModIdentity mod = hotClass == null ? defaultOwner(winner) : modResolver.resolve(hotClass);
        double dominance = totalScore == 0 ? 0.0D : (double) winnerScore / totalScore;
        double coverage = relevantSamples == 0 ? 0.0D : (double) classifiedSamples / relevantSamples;
        double confidence = Math.min(0.98D, 0.22D + dominance * 0.50D + Math.min(0.20D, coverage * 0.20D) + (hotCount >= 3 ? 0.06D : 0.0D));

        List<String> evidence = new ArrayList<String>();
        evidence.add("保留 " + capture.getSamples().size() + " 个线程栈样本（触发前 "
            + capture.getPreSampleCount() + "，触发后 " + capture.getPostSampleCount() + "），"
            + capture.getTriggers().size() + " 个触发信号。");
        evidence.add("实际样本覆盖触发前 " + String.format(Locale.ROOT, "%.2f", capture.getCapturedPreMillis() / 1000.0D)
            + " 秒、触发后 " + String.format(Locale.ROOT, "%.2f", capture.getCapturedPostMillis() / 1000.0D) + " 秒。");
        evidence.add(hitchShape(capture));
        evidence.add("根因归因使用 " + attribution.samples.size() + " 个" + attribution.detail
            + "样本；其余 " + Math.max(0, capture.getSamples().size() - attribution.samples.size())
            + " 个线程样本仅作为旁证保留。");
        if (capture.getDroppedSampleCount() > 0L) evidence.add("按前/后窗口与线程角色配额丢弃了 "
            + capture.getDroppedSampleCount() + " 个冗余样本，游戏主线程样本受到优先保护。");
        if (idleWorkerSamples > 0) evidence.add("归因时忽略 " + idleWorkerSamples
            + " 个处于队列等待/park/select 的空闲 Worker 样本。");
        if (hotCount > 0) evidence.add("最高频可归属方法出现 " + hotCount + " 次：" + hotMethod + "。");
        if (blocked > 0) evidence.add("有 " + blocked + " 个样本处于 BLOCKED 状态。");
        if (allocationBytes > 0L) evidence.add("采样窗口内目标线程分配约 " + formatBytes(allocationBytes) + "（JVM 支持范围内）。");
        HitchTrigger primary = primaryTrigger(capture);
        if (primary != null) evidence.add(primary.getType().getDisplayName() + "达到 " + String.format(Locale.ROOT, "%.2f", primary.getDurationMillis()) + " ms。");

        return new Diagnosis(winner, mod, hotMethod, confidence, evidence, recommendations(winner, mod));
    }

    private static HitchTrigger primaryTrigger(HitchCapture capture) {
        HitchTrigger result = null;
        for (HitchTrigger trigger : capture.getTriggers()) {
            if (result == null || trigger.getDurationNanos() > result.getDurationNanos()) result = trigger;
        }
        return result;
    }

    private static String hitchShape(HitchCapture capture) {
        int repeated = 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (HitchTrigger trigger : capture.getTriggers()) {
            TriggerType type = trigger.getType();
            if (type != TriggerType.CLIENT_FRAME && type != TriggerType.CLIENT_TICK
                && type != TriggerType.SERVER_TICK) continue;
            repeated++;
            first = Math.min(first, trigger.getTimestampNanos());
            last = Math.max(last, trigger.getTimestampNanos());
        }
        long span = repeated < 2 ? 0L : Math.max(0L, last - first);
        if (repeated >= 3 && span >= 500_000_000L) {
            return "卡顿形态：持续帧/Tick 税（" + repeated + " 次触发，跨度 "
                + String.format(Locale.ROOT, "%.2f", span / 1_000_000_000.0D) + " 秒）。";
        }
        if (repeated >= 2) {
            return "卡顿形态：短时连续尖峰（" + repeated + " 次相邻触发）。";
        }
        return "卡顿形态：单次尖峰；不应把等待/限帧样本误判为持续计算。";
    }

    private static AttributionSelection selectAttributionSamples(HitchCapture capture) {
        List<StackSample> all = capture.getSamples();
        if (all.isEmpty()) return new AttributionSelection(all, "触发窗口内主线程");
        HitchTrigger primary = primaryTrigger(capture);
        ThreadRole preferred = preferredRole(primary);
        long intervalStart = Long.MIN_VALUE;
        long intervalEnd = Long.MAX_VALUE;
        if (primary != null && primary.getDurationNanos() > 0L) {
            intervalEnd = primary.getTimestampNanos();
            long duration = primary.getDurationNanos();
            intervalStart = intervalEnd < Long.MIN_VALUE + duration
                ? Long.MIN_VALUE : intervalEnd - duration;
        }

        List<StackSample> preferredInterval = new ArrayList<StackSample>();
        List<StackSample> mainInterval = new ArrayList<StackSample>();
        List<StackSample> preferredAll = new ArrayList<StackSample>();
        List<StackSample> mainAll = new ArrayList<StackSample>();
        List<StackSample> intervalAll = new ArrayList<StackSample>();
        for (StackSample sample : all) {
            boolean inInterval = sample.getTimestampNanos() >= intervalStart
                && sample.getTimestampNanos() <= intervalEnd;
            boolean main = sample.getRole() == ThreadRole.CLIENT_MAIN
                || sample.getRole() == ThreadRole.SERVER_MAIN;
            if (preferred != null && sample.getRole() == preferred) {
                preferredAll.add(sample);
                if (inInterval) preferredInterval.add(sample);
            }
            if (main) {
                mainAll.add(sample);
                if (inInterval) mainInterval.add(sample);
            }
            if (inInterval) intervalAll.add(sample);
        }
        if (!preferredInterval.isEmpty()) {
            return new AttributionSelection(preferredInterval,
                preferred == ThreadRole.SERVER_MAIN ? "实际触发区间服务端主线程" : "实际触发区间客户端主线程");
        }
        if (!mainInterval.isEmpty()) return new AttributionSelection(mainInterval, "实际触发区间主线程");
        if (!preferredAll.isEmpty()) {
            return new AttributionSelection(preferredAll,
                preferred == ThreadRole.SERVER_MAIN ? "邻近服务端主线程" : "邻近客户端主线程");
        }
        if (!mainAll.isEmpty()) return new AttributionSelection(mainAll, "邻近主线程");
        if (!intervalAll.isEmpty()) return new AttributionSelection(intervalAll, "实际触发区间非主线程");
        return new AttributionSelection(all, "捕获窗口");
    }

    private static ThreadRole preferredRole(HitchTrigger trigger) {
        if (trigger == null) return null;
        switch (trigger.getType()) {
            case CLIENT_FRAME:
            case CLIENT_TICK:
                return ThreadRole.CLIENT_MAIN;
            case SERVER_TICK:
                return ThreadRole.SERVER_MAIN;
            default:
                return null;
        }
    }

    private static RootCause classify(StackSample sample, StackTraceElement[] trace) {
        StringBuilder joined = new StringBuilder();
        for (StackTraceElement frame : trace) {
            joined.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(' ');
        }
        String value = joined.toString().toLowerCase(Locale.ROOT);
        if (containsAny(value,
            "dev.rlcraft.ice.optimizer.compat.",
            "dev.rlcraft.ice.optimizer.runtime.clientworkerruntime",
            "dev.rlcraft.ice.optimizer.runtime.boundedrenderqueue",
            "dev.rlcraft.ice.hooks.textureuploadbootstrap.tryupload")) {
            return RootCause.ICE_RUNTIME;
        }
        if (containsAny(value,
            "smoothsync", "display.sync", "framelimiter", "limitframerate",
            "syncframerate", "waitfornextframe")) {
            return RootCause.FRAME_LIMITER_WAIT;
        }
        if (sample != null && (sample.getRole() == ThreadRole.CLIENT_MAIN
            || sample.getRole() == ThreadRole.SERVER_MAIN)
            && sample.getState() == Thread.State.TIMED_WAITING
            && containsAny(value, "java.lang.thread.sleep", "thread.sleep")) {
            return RootCause.FRAME_LIMITER_WAIT;
        }
        if (containsAny(value,
            "glclientwaitsync", "nglclientwaitsync", "glfencesync", "nglfencesync",
            "swapbuffers", "wglswap", "glfinish", "nglfinish",
            "nvoglv", "atio6axx", "ig7icd", "opengl32")) {
            return RootCause.GPU_DRIVER;
        }
        if (containsAny(value,
            "integratedserver.func_71260_j", "minecraftserver.func_71260_j",
            "minecraftserver.stopserver", "handleserverstopped",
            "fmlclienthandler.serverstopped", "unloadworld", "loadworld",
            "freeworld", "shuttingdown")) {
            return RootCause.GAME_LIFECYCLE;
        }
        if (containsAny(value, "chunkgenerator", "providechunk", "generatechunk", "worldgen", "mapgen", "genlayer", ".populate")) return RootCause.WORLD_GENERATION;
        if (containsAny(value, "savechunk", "saveallchunks", "writechunk", "anvilchunkloader.save", "regionfile.write", "compressedstreamtools.write")) return RootCause.CHUNK_SAVING;
        if (containsAny(value, "chunkioprovider", "loadchunk", "anvilchunkloader.load", "regionfile.read", "randomaccessfile.read", "inflaterinputstream")) return RootCause.CHUNK_LOADING;
        if (containsAny(value, "checklight", "relight", "lightengine", "generateheightmap")) return RootCause.CHUNK_LIGHTING;
        if (containsAny(value, "chunkrenderdispatcher", "renderchunk.rebuild", "renderchunk.resort", "uploadchunk", "compiledchunk")) return RootCause.CHUNK_RENDERING;
        if (containsAny(value, "pathnavigate", "pathfinder", "pathfind", "walknodeprocessor", "entityai")) return RootCause.AI_PATHFINDING;
        if (containsAny(value, "collision", "getcollisionboxes", "axisalignedbb", "raytraceblocks")) return RootCause.COLLISION;
        if (containsAny(value, "tileentity", "itickable.update")) return RootCause.TILE_ENTITY_TICK;
        if (containsAny(value, "updateentity", "entitylivingbase.onupdate", "onlivingupdate", "entity.onupdate")) return RootCause.ENTITY_TICK;
        if (containsAny(value, "asmeventhandler", "eventbus.post", "listenerlist")) return RootCause.EVENT_HANDLER;
        if (containsAny(value, "networkmanager", "netty", "packetbuffer", "processpacket", "decoder", "encoder")) return RootCause.NETWORK;
        if (containsAny(value, "reloadresources", "resourcemanager", "texturemap", "modelbakery", "soundmanager")) return RootCause.RESOURCE_LOADING;
        if (containsAny(value, "entityrenderer", "renderglobal", "rendermanager", "tessellator", "glstatemanager", "display.update")) return RootCause.CLIENT_RENDER;
        if (containsAny(value, "unsafe.park", "object.wait", "locksupport.park", "reentrantlock")) {
            return RootCause.THREAD_CONTENTION;
        }
        return RootCause.UNKNOWN;
    }

    static RootCause classifyForTest(ThreadRole role, Thread.State state,
                                     StackTraceElement[] trace) {
        StackSample sample = new StackSample(0L, 1L, "test", role, 0, 0L, 0L, state);
        return classify(sample, trace);
    }

    private StackTraceElement firstActionable(StackTraceElement[] trace) {
        StackTraceElement fallback = null;
        for (StackTraceElement frame : trace) {
            String name = frame.getClassName();
            if (name.startsWith("dev.rlcraft.ice.profiler.")) continue;
            if (name.startsWith("java.lang.Thread") || name.startsWith("sun.reflect.") || name.startsWith("java.lang.reflect.")) continue;
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.") || name.startsWith("com.sun.")) continue;
            if (name.equals("net.minecraft.client.Minecraft") || name.equals("net.minecraft.server.MinecraftServer")) continue;
            if (fallback == null) fallback = frame;
            ModIdentity owner = modResolver.resolve(name);
            if (owner != ModIdentity.MINECRAFT && owner != ModIdentity.FORGE && owner != ModIdentity.JVM && owner != ModIdentity.LWJGL) return frame;
        }
        return fallback == null && trace.length > 0 ? trace[0] : fallback;
    }

    private static void increment(Map<RootCause, Integer> values, RootCause key, int amount) {
        Integer old = values.get(key);
        values.put(key, Integer.valueOf((old == null ? 0 : old.intValue()) + amount));
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static ModIdentity defaultOwner(RootCause cause) {
        if (cause == RootCause.GARBAGE_COLLECTION || cause == RootCause.JVM_CPU || cause == RootCause.THREAD_CONTENTION) return ModIdentity.JVM;
        if (cause == RootCause.GPU_DRIVER) return ModIdentity.LWJGL;
        if (cause == RootCause.ICE_RUNTIME) return ModIdentity.ICE;
        if (cause == RootCause.CLIENT_RENDER) return ModIdentity.MINECRAFT;
        return ModIdentity.UNKNOWN;
    }

    private static List<String> recommendations(RootCause cause, ModIdentity mod) {
        List<String> result = new ArrayList<String>();
        switch (cause) {
            case GARBAGE_COLLECTION:
                result.add("检查堆内存是否过小或过大、分配速率和 GC 参数；先不要用清实体等会改变玩法的手段掩盖问题。");
                break;
            case WORLD_GENERATION:
                result.add("优先检查报告中的生成器方法及其所属模组，并复测相同地形区域；可离线预生成世界，但不要异步执行 1.12.2 世界生成。");
                break;
            case CHUNK_LOADING:
            case CHUNK_SAVING:
                result.add("检查存档磁盘延迟、杀毒软件扫描和区块 NBT 体积；针对报告指出的模组数据结构优化序列化。");
                break;
            case ENTITY_TICK:
            case TILE_ENTITY_TICK:
                result.add("按报告中的类名核对热点实例密度和单次 Tick 成本；优化代码路径，不删除或跳过原有 Tick。");
                break;
            case CHUNK_RENDERING:
            case CLIENT_RENDER:
                result.add("检查区块重建队列、材质/模型和驱动时间；优化缓存与批处理时必须保持方块和实体的可见内容不变。");
                break;
            case GPU_DRIVER:
                result.add("检查报告中的 Fence、交换缓冲或同步调用；小上传应直接走原路径，GPU 队列忙时不得轮询等待。");
                break;
            case FRAME_LIMITER_WAIT:
                result.add("该样本属于限帧或主动 sleep/park，不是计算瓶颈；比较性能时请同时查看未限帧帧时和 GPU 利用率。");
                break;
            case ICE_RUNTIME:
                result.add("优先关闭报告点名的单个 ICE 模块复测；该模块应缩小触发阈值或在队列/驱动繁忙时立即回原实现。");
                break;
            case GAME_LIFECYCLE:
                result.add("这是世界加载、保存或关闭阶段；应单独比较阶段耗时，避免与稳定游戏帧率混为一谈。");
                break;
            case THREAD_CONTENTION:
                result.add("查看代表调用栈中的锁持有者与等待者，缩短锁区间或移动只读计算，不能把世界状态写入异步线程。");
                break;
            default:
                result.add("使用深度录制复现一次，并结合代表调用栈确认具体方法后再写针对性优化器。");
                break;
        }
        if (!"unknown".equals(mod.getId())) result.add("当前最高可能归属：" + mod.getName() + "（" + mod.getId() + "）。建议先单独审查该模组对应方法。");
        return result;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
    }

    private static final class AnalyzedStack {
        private final RootCause cause;
        private final String className;
        private final String method;
        private AnalyzedStack(RootCause cause, String className, String method) {
            this.cause = cause;
            this.className = className;
            this.method = method;
        }
    }

    private static final class AttributionSelection {
        private final List<StackSample> samples;
        private final String detail;

        private AttributionSelection(List<StackSample> samples, String detail) {
            this.samples = samples;
            this.detail = detail;
        }
    }
}

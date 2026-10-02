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
        AttributionSamples attribution = AttributionSamples.select(capture);
        EnumMap<RootCause, Integer> scores = new EnumMap<RootCause, Integer>(RootCause.class);
        Map<String, Integer> hotFrames = new HashMap<String, Integer>();
        Map<String, String> hotClasses = new HashMap<String, String>();
        Map<Integer, AnalyzedStack> analyzedStacks = new HashMap<Integer, AnalyzedStack>();
        Map<Long, Boolean> idleStates = new HashMap<Long, Boolean>();
        int blocked = 0;
        int classifiedSamples = 0;
        int relevantSamples = 0;
        int idleWorkerSamples = 0;
        int prefixOnlySamples = 0;
        int wrappedNativeSamples = 0;
        long allocationBytes = 0L;
        for (StackSample sample : capture.getSamples()) {
            StackTraceElement[] trace = stacks.get(sample.getStackTraceId());
            if (isIdle(sample, trace, idleStates)) idleWorkerSamples++;
        }
        for (StackSample sample : attribution.samples) {
            StackTraceElement[] trace = stacks.get(sample.getStackTraceId());
            if (isIdle(sample, trace, idleStates)) {
                continue;
            }
            relevantSamples++;
            if (StackTraceRepository.isPrefixOnly(sample.getStackTraceId())) {
                prefixOnlySamples++;
            }
            if (sample.getState() == Thread.State.BLOCKED) blocked++;
            allocationBytes += sample.getAllocatedBytesDelta();
            Integer stackId = Integer.valueOf(sample.getStackTraceId());
            AnalyzedStack analyzed = analyzedStacks.get(stackId);
            if (analyzed == null) {
                String methods = StackWorkClassifier.joinedMethods(trace);
                RootCause traceCause = StackWorkClassifier.classifyTrace(trace, methods);
                StackTraceElement hot = StackWorkClassifier.firstActionable(trace, modResolver, traceCause);
                analyzed = new AnalyzedStack(traceCause, StackWorkClassifier.mainSleepMayOverride(traceCause, methods),
                    hot == null ? null : hot.getClassName(),
                    hot == null ? null : hot.getClassName() + "." + hot.getMethodName(),
                    trace.length > 0 && trace[0].isNativeMethod() && traceCause != RootCause.ICE_RUNTIME
                        && methods.contains("dev.rlcraft.ice."));
                analyzedStacks.put(stackId, analyzed);
            }
            RootCause classified = StackWorkClassifier.classifySample(sample, analyzed.cause, analyzed.mainSleepMayOverride);
            if (analyzed.wrappedNative) wrappedNativeSamples++;
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
            if (trigger.getType() == TriggerType.CLIENT_FRAME && relevantSamples > 0) increment(scores, RootCause.CLIENT_RENDER, 1);
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
        double confidence = winner == RootCause.UNKNOWN ? 0.0D : Math.min(0.98D, 0.22D + dominance * 0.50D + Math.min(0.20D, coverage * 0.20D) + (hotCount >= 3 ? 0.06D : 0.0D));

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
        if (capture.getDroppedSampleCount() > 0L) evidence.add("按触发区间优先级与线程角色配额丢弃了 "
            + capture.getDroppedSampleCount() + " 个样本；优先保留实际卡顿区间，但缺失区间仍不能确定根因。");
        if (capture.getSourceDroppedSampleCount() > 0L) evidence.add("该诊断来自分角色捕获；合并源窗口共丢弃 "
            + capture.getSourceDroppedSampleCount() + " 个样本，丢弃量无法可靠拆分到各角色。");
        if (attribution.missingTriggers > 0) evidence.add("缺少 " + attribution.missingTriggers
            + " 个实际触发区间内的目标线程样本；不使用邻近栈代替缺失根因。");
        if (relevantSamples == 0 && winner == RootCause.UNKNOWN) evidence.add("实际触发区间无可归因样本，根因尚未确定。");
        if (wrappedNativeSamples > 0) evidence.add("有 " + wrappedNativeSamples
            + " 个样本停在 ICE 调用下的原生被调用者；不能把文件/驱动/JVM工作或整段卡顿时长算作 ICE 自身计算。");
        if (!scores.isEmpty()) evidence.add("实际区间分类计分：" + scores.toString() + "。");
        if (idleWorkerSamples > 0) evidence.add("归因时忽略 " + idleWorkerSamples
            + " 个处于队列等待/park/select 的空闲 Worker 样本。");
        if (prefixOnlySamples > 0) evidence.add("字典容量后有 " + prefixOnlySamples
            + " 个归因样本仅保留顶部 3 个方法；后续调用者及源代码行未保留，不用于归属推断。");
        if (hotCount > 0) evidence.add("最高频可归属方法出现 " + hotCount + " 次：" + hotMethod + "。");
        if (blocked > 0) evidence.add("有 " + blocked + " 个样本处于 BLOCKED 状态。");
        if (allocationBytes > 0L) evidence.add("采样窗口内目标线程分配约 " + formatBytes(allocationBytes) + "（JVM 支持范围内）。");
        HitchTrigger primary = primaryTrigger(capture);
        if (primary != null) {
            evidence.add(primary.getType().getDisplayName() + "达到 " + String.format(Locale.ROOT, "%.2f", primary.getDurationMillis()) + " ms。");
            if (attribution.missingTriggers > 0 && !attribution.covers(primary)) {
                evidence.add("最长" + primary.getType().getDisplayName() + "缺少区间栈；"
                    + (attribution.samples.isEmpty() ? "当前没有可归因的目标线程样本" : "当前归因来自其他触发区间")
                    + "，不能解释该峰值。");
            }
        }

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

    static RootCause classifyForTest(ThreadRole role, Thread.State state, StackTraceElement[] trace) {
        StackSample sample = new StackSample(0L, 1L, "test", role, 0, 0L, 0L, state);
        RootCause cause = StackWorkClassifier.classifyTrace(trace);
        return StackWorkClassifier.classifySample(sample, cause,
            StackWorkClassifier.mainSleepMayOverride(cause, StackWorkClassifier.joinedMethods(trace)));
    }

    private static boolean isIdle(StackSample sample, StackTraceElement[] trace, Map<Long, Boolean> cache) {
        if (sample.getRole() == ThreadRole.CLIENT_MAIN || sample.getRole() == ThreadRole.SERVER_MAIN) return false;
        long encoded = ((long) sample.getStackTraceId() << 16)
            | ((long) sample.getRole().ordinal() << 8) | sample.getState().ordinal();
        Long key = Long.valueOf(encoded);
        Boolean result = cache.get(key);
        if (result == null) {
            result = Boolean.valueOf(StackSampleFilter.isIdleWorkerWait(sample, trace));
            cache.put(key, result);
        }
        return result.booleanValue();
    }

    private static void increment(Map<RootCause, Integer> values, RootCause key, int amount) {
        Integer old = values.get(key);
        values.put(key, Integer.valueOf((old == null ? 0 : old.intValue()) + amount));
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
        private final boolean mainSleepMayOverride;
        private final String className;
        private final String method;
        private final boolean wrappedNative;
        private AnalyzedStack(RootCause cause, boolean mainSleepMayOverride,
                              String className, String method, boolean wrappedNative) {
            this.cause = cause;
            this.mainSleepMayOverride = mainSleepMayOverride;
            this.className = className;
            this.method = method;
            this.wrappedNative = wrappedNative;
        }
    }

}

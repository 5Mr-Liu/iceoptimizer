package dev.rlcraft.ice.command;

import dev.rlcraft.ice.IceProfilerMod;
import dev.rlcraft.ice.profiler.FatalErrors;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.core.ProfilerStatus;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import dev.rlcraft.ice.profiler.report.ReportComparison;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

public final class CommandIce extends CommandBase {
    private static final List<String> ACTIONS = Arrays.asList("status", "start", "stop", "mark", "export", "list", "compare", "deep", "reload");

    @Override public String getName() { return "iceprofiler"; }
    @Override public String getUsage(ICommandSender sender) { return "/iceprofiler <status|start|stop|mark|export|list|compare|deep|reload>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public List<String> getAliases() { return Arrays.asList("iceperf", "iceprofile"); }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] rawArgs) throws CommandException {
        int offset = rawArgs.length > 0 && "profile".equalsIgnoreCase(rawArgs[0]) ? 1 : 0;
        String action = rawArgs.length <= offset ? "status" : rawArgs[offset].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(rawArgs, Math.min(rawArgs.length, offset + 1), rawArgs.length);
        if ("status".equals(action)) { sendStatus(sender); return; }
        if ("list".equals(action)) { sendList(sender); return; }
        requireOperator(sender);
        if ("start".equals(action)) {
            ProfilerRuntime.INSTANCE.startManual(args.length == 0 ? "命令手动录制" : join(args, 0));
            send(sender, "§b[ICE] 已开始完整手动录制。");
        } else if ("stop".equals(action)) {
            if (ProfilerRuntime.INSTANCE.stopManual(true) == null) send(sender, "§e[ICE] 当前没有正在录制的会话。");
            else send(sender, "§b[ICE] 录制已停止，报告正在后台生成。");
        } else if ("mark".equals(action)) {
            ProfilerRuntime.INSTANCE.mark(args.length == 0 ? "命令标记" : join(args, 0));
            send(sender, "§b[ICE] 已加入标记并触发前后窗口捕获。");
        } else if ("export".equals(action)) {
            if (ProfilerRuntime.INSTANCE.exportAndContinue() == null) send(sender, "§e[ICE] 没有可导出的活动会话。");
            else send(sender, "§b[ICE] 当前会话已封存，报告正在后台生成，并已继续新会话。");
        } else if ("deep".equals(action)) {
            if (args.length != 1 || !("on".equalsIgnoreCase(args[0]) || "off".equalsIgnoreCase(args[0]))) throw new CommandException("用法：/iceprofiler deep <on|off>");
            boolean enabled = "on".equalsIgnoreCase(args[0]);
            ProfilerRuntime.INSTANCE.setDeepMode(enabled);
            send(sender, "§b[ICE] 深度采样已" + (enabled ? "开启（10 ms + 精确探针）" : "关闭（恢复标准频率）") + "。");
        } else if ("compare".equals(action)) {
            if (args.length != 2) throw new CommandException("用法：/iceprofiler compare <会话A> <会话B>");
            try {
                ReportComparison comparison = ProfilerRuntime.INSTANCE.compareReports(args[0], args[1]);
                send(sender, "§b[ICE] 对比 " + comparison.getLeftId() + " → " + comparison.getRightId());
                for (String line : comparison.getLines()) send(sender, "§7" + line);
            } catch (Exception error) {
                FatalErrors.rethrowIfFatal(error);
                throw new CommandException("报告对比失败：" + error.getMessage());
            }
        } else if ("reload".equals(action)) {
            ConfigManager.sync(IceProfilerMod.MOD_ID, Config.Type.INSTANCE);
            send(sender, "§b[ICE] 配置已重新载入；固定容量变更将在下次启动时完全生效。");
        } else {
            throw new CommandException(getUsage(sender));
        }
    }

    private static void sendStatus(ICommandSender sender) {
        ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
        send(sender, "§b[ICE] " + (status.isRecording() ? "录制中 " + status.getSessionId() : "被动监测")
            + "；模式 " + (status.isDeepMode() ? "深度" : "标准") + "；触发 " + status.getTriggers() + "；唯一调用栈 " + status.getUniqueStacks());
        TimelinePoint point = status.getLatest();
        if (point != null) {
            send(sender, String.format(Locale.ROOT, "§7客户端：%d FPS，帧 P95 %.2f / 最大 %.2f ms，GPU %.2f ms，渲染队列 %d/%d",
                point.getFramesPerSecond(), point.getClientFrames().getP95Ms(), point.getClientFrames().getMaximumMs(), point.getGpuFrameMillis(), point.getRenderQueueSize(), point.getChunkUploadQueueSize()));
            send(sender, String.format(Locale.ROOT, "§7服务端：Tick P95 %.2f / 最大 %.2f ms；区块 %d，实体 %d，方块实体 %d",
                point.getServerTicks().getP95Ms(), point.getServerTicks().getMaximumMs(), point.getLoadedChunks(), point.getEntities(), point.getTileEntities()));
            send(sender, String.format(Locale.ROOT, "§7JVM：堆 %.1f/%.1f MiB，GC %d ms，进程 CPU %.0f%%；网络入/出 %d/%d 包",
                point.getJvm().getHeapUsedBytes() / 1048576.0D, point.getJvm().getHeapCommittedBytes() / 1048576.0D,
                point.getJvm().getGcPauseMillisDelta(), Math.max(0.0D, point.getJvm().getProcessCpuLoad()) * 100.0D,
                point.getInboundPackets(), point.getOutboundPackets()));
        }
        int shown = 0;
        for (String diagnosis : status.getDiagnoses()) {
            if (shown++ >= 3) break;
            send(sender, "§e根因：" + diagnosis);
        }
        if (status.getLastReport() != null) send(sender, "§7最近报告：" + status.getLastReport().getName());
        if (!status.getExportError().isEmpty()) send(sender, "§c最近导出失败：" + status.getExportError());
    }

    private static void sendList(ICommandSender sender) {
        List<File> reports = ProfilerRuntime.INSTANCE.listReports();
        send(sender, "§b[ICE] 已保存报告 " + reports.size() + " 个（最多显示 10 个）：");
        for (int i = 0; i < Math.min(10, reports.size()); i++) send(sender, "§7- " + reports.get(i).getName());
    }

    private static void requireOperator(ICommandSender sender) throws CommandException {
        if (!sender.canUseCommand(2, "iceprofiler")) throw new CommandException("commands.generic.permission");
    }

    private static void send(ICommandSender sender, String message) { sender.sendMessage(new TextComponentString(message)); }

    private static String join(String[] values, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < values.length; i++) {
            if (result.length() > 0) result.append(' ');
            result.append(values[i]);
        }
        return result.toString();
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, Arrays.asList("profile", "status", "start", "stop", "mark", "export", "list", "compare", "deep", "reload"));
        if (args.length == 2 && "profile".equalsIgnoreCase(args[0])) return getListOfStringsMatchingLastWord(args, ACTIONS);
        String action = args.length > 1 && "profile".equalsIgnoreCase(args[0]) ? args[1] : args[0];
        if ("deep".equalsIgnoreCase(action)) return getListOfStringsMatchingLastWord(args, Arrays.asList("on", "off"));
        return Collections.emptyList();
    }
}

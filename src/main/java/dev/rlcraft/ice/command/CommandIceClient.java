package dev.rlcraft.ice.command;

import dev.rlcraft.ice.client.ProfilerDashboardScreen;
import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.core.ProfilerStatus;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

public final class CommandIceClient extends CommandBase {
    @Override public String getName() { return "iceprofilerclient"; }
    @Override public String getUsage(ICommandSender sender) { return "/iceprofilerclient <status|start|stop|mark|export|deep|dashboard>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public List<String> getAliases() { return Arrays.asList("iceclient", "icec"); }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String action = args.length == 0 ? "status" : args[0].toLowerCase();
        if ("status".equals(action)) {
            ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
            sender.sendMessage(new TextComponentString("§b[ICE] " + (status.isRecording() ? "录制中 " + status.getSessionId() : "被动监测")
                + "；触发 " + status.getTriggers() + "；根因聚类 " + status.getDiagnoses().size() + "；调用栈 " + status.getUniqueStacks()));
        } else if ("start".equals(action)) {
            ProfilerRuntime.INSTANCE.startManual("客户端命令录制");
            sender.sendMessage(new TextComponentString("§b[ICE] 已开始手动录制。"));
        } else if ("stop".equals(action)) {
            ProfilerRuntime.INSTANCE.stopManual(true);
            sender.sendMessage(new TextComponentString("§b[ICE] 已停止并在后台生成报告。"));
        } else if ("mark".equals(action)) {
            ProfilerRuntime.INSTANCE.mark(args.length > 1 ? join(args, 1) : "客户端命令标记");
            sender.sendMessage(new TextComponentString("§b[ICE] 已标记当前窗口。"));
        } else if ("export".equals(action)) {
            ProfilerRuntime.INSTANCE.exportAndContinue();
            sender.sendMessage(new TextComponentString("§b[ICE] 已封存当前会话并继续录制。"));
        } else if ("deep".equals(action)) {
            if (args.length != 2 || !("on".equalsIgnoreCase(args[1]) || "off".equalsIgnoreCase(args[1]))) throw new CommandException("用法：/iceprofilerclient deep <on|off>");
            ProfilerRuntime.INSTANCE.setDeepMode("on".equalsIgnoreCase(args[1]));
            sender.sendMessage(new TextComponentString("§b[ICE] 深度采样已" + (ProfilerRuntime.INSTANCE.isDeepMode() ? "开启" : "关闭") + "。"));
        } else if ("dashboard".equals(action)) {
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(new ProfilerDashboardScreen(minecraft.currentScreen));
        } else {
            throw new CommandException(getUsage(sender));
        }
    }

    private static String join(String[] values, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < values.length; i++) { if (result.length() > 0) result.append(' '); result.append(values[i]); }
        return result.toString();
    }
}

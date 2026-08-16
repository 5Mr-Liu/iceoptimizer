package dev.rlcraft.ice.client;

import dev.rlcraft.ice.profiler.core.ProfilerRuntime;
import dev.rlcraft.ice.profiler.core.ProfilerStatus;
import dev.rlcraft.ice.profiler.metrics.TimelinePoint;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public final class ProfilerDashboardScreen extends GuiScreen {
    private final GuiScreen parent;

    public ProfilerDashboardScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int y = height - 34;
        ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
        buttonList.add(new GuiButton(1, width / 2 - 154, y, 100, 20, status.isManualSession() ? "停止并导出" : "开始手动录制"));
        buttonList.add(new GuiButton(2, width / 2 - 50, y, 100, 20, "立即导出"));
        buttonList.add(new GuiButton(3, width / 2 + 54, y, 100, 20, "关闭"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 1) {
            ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
            if (status.isManualSession()) ProfilerRuntime.INSTANCE.stopManual(true);
            else ProfilerRuntime.INSTANCE.startManual("Dashboard 手动录制");
            initGui();
        } else if (button.id == 2) {
            ProfilerRuntime.INSTANCE.exportAndContinue();
        } else if (button.id == 3) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ProfilerStatus status = ProfilerRuntime.INSTANCE.status();
        drawCenteredString(fontRenderer, "ICE Performance Recorder", width / 2, 16, 0x8BD5FF);
        int x = 24;
        int y = 42;
        fontRenderer.drawString("状态：" + (status.isRecording() ? "正在录制 " + status.getSessionId() : "被动监测"), x, y, 0xFFFFFF); y += 14;
        fontRenderer.drawString("模式：" + (status.isDeepMode() ? "深度采样" : "标准采样") + "　触发：" + status.getTriggers()
            + "　调用栈字典：" + status.getUniqueStacks(), x, y, 0xCCCCCC); y += 18;
        TimelinePoint point = status.getLatest();
        if (point != null) {
            fontRenderer.drawString(String.format(Locale.ROOT, "客户端：%d FPS，帧 P95 %.2f ms，GPU %.2f ms，渲染队列 %d/%d",
                point.getFramesPerSecond(), point.getClientFrames().getP95Ms(), point.getGpuFrameMillis(), point.getRenderQueueSize(), point.getChunkUploadQueueSize()), x, y, 0xA6E3A1); y += 14;
            fontRenderer.drawString(String.format(Locale.ROOT, "服务端：Tick P95 %.2f ms，最大 %.2f ms；区块 %d，实体 %d，方块实体 %d",
                point.getServerTicks().getP95Ms(), point.getServerTicks().getMaximumMs(), point.getLoadedChunks(), point.getEntities(), point.getTileEntities()), x, y, 0xFAB387); y += 14;
            fontRenderer.drawString(String.format(Locale.ROOT, "JVM：堆 %.1f/%.1f MiB，最近 GC %d ms，CPU %.0f%%",
                point.getJvm().getHeapUsedBytes() / 1048576.0D, point.getJvm().getHeapCommittedBytes() / 1048576.0D,
                point.getJvm().getGcPauseMillisDelta(), Math.max(0.0D, point.getJvm().getProcessCpuLoad()) * 100.0D), x, y, 0xCBA6F7); y += 20;
        }
        fontRenderer.drawString("已识别根因（重复事件已合并）：", x, y, 0x8BD5FF); y += 14;
        List<String> diagnoses = status.getDiagnoses();
        if (diagnoses.isEmpty()) fontRenderer.drawString("尚无完成捕获。复现卡顿后会在这里显示类别、模组和方法。", x, y, 0x999999);
        else {
            for (int i = 0; i < Math.min(10, diagnoses.size()); i++) {
                fontRenderer.drawString((i + 1) + ". " + trim(diagnoses.get(i), Math.max(40, width / 6)), x, y, 0xEEEEEE);
                y += 13;
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) mc.displayGuiScreen(parent);
        else super.keyTyped(typedChar, keyCode);
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    private static String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…";
    }
}

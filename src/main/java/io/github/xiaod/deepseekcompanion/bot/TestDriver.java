package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.command.LocalCommandHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件驱动的测试指令队列：进世界后自动读取 {@code <gameDir>/xd-test-commands.txt}，
 * 每隔几秒执行一行（{@code .xd ...} 走本地命令，其它当聊天/命令发出），执行完把文件
 * 改名成 {@code .done}。
 *
 * <p>为什么需要它：agent 没有抢鼠标键盘焦点的可靠手段（SetForegroundWindow 会被前台锁
 * 挡掉，窗口一失焦单人世界就暂停），而模组自己在客户端 tick 里发命令完全不受影响。
 * 这样"启动 → 进世界 → 跑一串指令 → 看日志"就能全自动。</p>
 */
public final class TestDriver {
    private static final long START_DELAY_MS = 12000L;
    private static final long STEP_MS = 3000L;

    private static boolean loaded;
    private static List<String> lines = List.of();
    private static int index;
    private static long nextAt;
    private static long stepMs = 3000L;

    private TestDriver() {
    }

    public static void reset() {
        loaded = false;
        lines = List.of();
        index = 0;
        nextAt = 0L;
    }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!loaded) {
            loaded = true;
            Path file = file();
            if (Files.isRegularFile(file)) {
                try {
                    lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
                    lines.removeIf(String::isBlank);
                    index = 0;
                    nextAt = now + START_DELAY_MS;
                    DeepseekCompanionClient.LOGGER.info("[XiaoD][test] loaded {} commands from {}",
                            lines.size(), file.getFileName());
                } catch (IOException e) {
                    DeepseekCompanionClient.LOGGER.warn("[XiaoD][test] cannot read {}", file, e);
                    lines = List.of();
                }
            }
        }
        if (lines.isEmpty() || now < nextAt) {
            return;
        }
        if (index >= lines.size()) {
            finish();
            return;
        }
        String line = lines.get(index++).strip();
        if (line.startsWith("#step=")) {
            try {
                stepMs = Math.max(50L, Long.parseLong(line.substring(6).trim()));
            } catch (NumberFormatException ignored) {
                // keep the previous step delay
            }
            nextAt = now + 50L;
            return;
        }
        if (line.startsWith("#sleep=")) {
            try {
                nextAt = now + Math.max(0L, Long.parseLong(line.substring(7).trim()));
            } catch (NumberFormatException ignored) {
                nextAt = now + stepMs;
            }
            return;
        }
        nextAt = now + stepMs;
        DeepseekCompanionClient.LOGGER.info("[XiaoD][test] ({}/{}) >> {}", index, lines.size(), line);
        try {
            if (line.startsWith(".xd")) {
                LocalCommandHandler.handle(line);
            } else if (mc.getNetworkHandler() != null) {
                if (line.startsWith("/")) {
                    // 命令必须走 sendChatCommand（不带前导 /），sendChatMessage 会原样发到公屏
                    mc.getNetworkHandler().sendChatCommand(line.substring(1));
                } else {
                    mc.getNetworkHandler().sendChatMessage(line);
                }
            }
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD][test] command failed: {}", line, error);
        }
        if (index >= lines.size()) {
            nextAt = now + stepMs;
        }
    }

    private static void finish() {
        lines = List.of();
        Path file = file();
        try {
            Files.move(file, file.resolveSibling("xd-test-commands.done.txt"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DeepseekCompanionClient.LOGGER.info("[XiaoD][test] all commands executed, file renamed to .done");
        } catch (IOException e) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD][test] cannot rename command file", e);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("xd-test-commands.txt");
    }
}

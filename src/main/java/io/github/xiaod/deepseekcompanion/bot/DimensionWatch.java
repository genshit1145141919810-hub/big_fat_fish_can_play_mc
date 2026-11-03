package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;

/**
 * 维度监视：换维度时把"上一个维度的状态"全部清掉。
 *
 * <p>不做这件事会有一堆幽灵任务：地狱里还在挖主世界记下的那个坐标、计划里的 goto 用着
 * 旧维度的数字、挖掘目标早就不存在。进传送门这件事真正的坑在这，而不是走进去那几秒。</p>
 */
public final class DimensionWatch {
    private static String last;

    private DimensionWatch() {
    }

    public static String current(MinecraftClient mc) {
        if (mc.world == null) {
            return last == null ? "" : last;
        }
        return mc.world.getRegistryKey().getValue().toString();
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world == null) {
            return;
        }
        String now = current(mc);
        if (last == null) {
            last = now;
            return;
        }
        if (last.equals(now)) {
            return;
        }
        String from = last;
        last = now;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] dimension {} -> {}", from, now);
        MiningController.cancel(mc);
        TaskTracker.clear();
        FleeController.clear();
        PlanRunner.onDimensionChanged(from, now);
    }

    /** 断线/回主菜单后，下一次进世界重新认维度。 */
    public static void reset() {
        last = null;
    }
}

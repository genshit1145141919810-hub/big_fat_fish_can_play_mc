package com.example.companionaddon;

import io.github.xiaod.deepseekcompanion.api.ActionProvider;
import io.github.xiaod.deepseekcompanion.api.ArgSpec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;

/**
 * 示例：第三方模组把自己的能力注册成小D 的动作。
 *
 * <p>注册方式（你的 fabric.mod.json）：</p>
 * <pre>
 * "entrypoints": {
 *   "deepseekcompanion:actions": ["com.example.companionaddon.MyActions"]
 * }
 * </pre>
 * 或者在你的 onInitialize 里直接
 * {@code ActionRegistry.register(new ExampleAction(), "my-mod");}。
 *
 * <p>下面这个例子让模型可以请求“给最近的动物喂食”，演示了参数校验、可用性判断和
 * 危险动作标注三件事。</p>
 */
public final class ExampleAction implements ActionProvider {
    @Override
    public String id() {
        return "feed_nearest_animal";
    }

    @Override
    public String description() {
        return "给最近的一只动物喂手上的食物（arg 留空即可）";
    }

    @Override
    public ArgSpec argSpec() {
        return ArgSpec.NONE;
    }

    @Override
    public boolean available() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.world != null;
    }

    @Override
    public String validate(String arg) {
        if (arg != null && !arg.isBlank()) {
            throw new IllegalArgumentException("这个动作不需要参数");
        }
        return "";
    }

    @Override
    public boolean run(String arg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }
        Entity nearest = mc.world.getEntitiesByClass(AnimalEntity.class,
                        mc.player.getBoundingBox().expand(6.0), Entity::isAlive)
                .stream()
                .min((a, b) -> Double.compare(a.squaredDistanceTo(mc.player), b.squaredDistanceTo(mc.player)))
                .orElse(null);
        if (nearest == null) {
            return false;
        }
        mc.interactionManager.interactEntity(mc.player, nearest, net.minecraft.util.Hand.MAIN_HAND);
        return true;
    }
}

package io.github.xiaod.deepseekcompanion.api;

/** 插件动作的参数类型，语义与内置动作一致（BLOCKS=方块id，ITEM=物品id，POS=三个整数坐标…）。 */
public enum ArgSpec {
    NONE,
    ITEM,
    ITEMS,
    BLOCKS,
    POS,
    ENTITY,
    STRING
}

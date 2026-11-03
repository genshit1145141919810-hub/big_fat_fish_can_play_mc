package io.github.xiaod.deepseekcompanion.api;

/**
 * 第三方模组实现这个接口，就能把自己的能力注册成小D 的一个动作。
 *
 * <p>注册方式（Fabric entrypoint，代价于 {@code fabric.mod.json}）：</p>
 * <pre>
 * "entrypoints": {
 *   "deepseekcompanion:actions": ["com.example.MyActions"]
 * }
 * </pre>
 *
 * <p>约定与限制：</p>
 * <ul>
 *   <li>{@link #id()} 必须是小写蛇形、2~32 字符，不能和内置动作重名；</li>
 *   <li>{@link #run} 在客户端主线程被调用，必须<b>快速返回</b>（<100ms），
 *       长任务请自己起状态机并在 {@link #tick} 里推进；</li>
 *   <li>参数会先过 {@link #validate(String)}，你的实现不能信任模型给的原始字符串；</li>
 *   <li>标注 {@link #dangerous()} 的动作会在日志里被单独记录，方便审计。</li>
 * </ul>
 *
 * <p>一句话：模型只能“请求”这个动作，参数校验、是否允许、以及实际怎么执行都由你决定。</p>
 */
public interface ActionProvider {
    /** 动作 id，例如 {@code yes_steve_model_change_skin}。 */
    String id();

    /** 给模型看的一行说明（中文，≤60 字，说清 arg 是什么）。 */
    String description();

    /** 参数类型，决定模型该怎么填 arg。 */
    ArgSpec argSpec();

    /** 这个动作此刻是否可用（比如模组没加载、玩家不在船上）。不可用就不进提示词。 */
    default boolean available() {
        return true;
    }

    /**
     * 校验并规范化模型给的 arg。抛 {@link IllegalArgumentException} 表示参数非法，
     * 异常信息会原样喂回模型让它改正。
     */
    default String validate(String arg) {
        return arg == null ? "" : arg.strip();
    }

    /** 危险动作（破坏、给予、消耗资源）——只影响日志与审计。 */
    default boolean dangerous() {
        return false;
    }

    /**
     * 执行一次。返回 false 表示“没做成”，上层会把它当作一步失败（可能触发重规划）。
     * 长任务：这里只负责启动，然后返回 true，并在 {@link #tick} 里推进。
     */
    boolean run(String arg);

    /** 每个客户端 tick 调用一次，用于你启动的长任务（默认什么都不做）。 */
    default void tick(boolean busy) {
    }
}

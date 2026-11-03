# 大肥鱼能玩MC（Big Fat Fish Can Play MC）

<p align="center"><img src="docs/promo-maid.png" alt="大肥鱼能玩MC" width="420"></p>

给你的 Minecraft 配一个**真实玩家账号**当搭子。

它不是 NPC 生物，也不是假客户端：模组跑在你的第二个正版账号所在的真实客户端里，用普通玩家动作（走、挖、右键、点格子、发聊天）做事，寻路交给 Baritone。你在聊天里喊它，它会回话、跟着你、挖矿砍树、做饭、合成、打架、存箱子；一件事一个动作做不完时，它会自己拆成多步计划。

**Alpha 版本**，能用但会有毛边；出问题看日志。

---

## 一、它真的会做什么

| 方面 | 行为 |
| --- | --- |
| 聊天 | 读聊天、用 DeepSeek 回你；`.xd` 开头的命令只在本地处理，永远不上服务器 |
| 移动 | Baritone：`#follow` / `#goto` / `#mine`；没装 Baritone 时有本地挖掘兜底 |
| 战斗 | **默认就打**（十个僵尸也照打）；贴脸时边飘边打，用击退维持距离；只有血 ≤2 或苦力怕贴身才脱离，且优先**垒墙自保** |
| 合成 | **递归合成树**：木镐 → 木板 → 木棍 → 木镐；背包 2×2、放下工作台用 3×3，缺原料自动先合 |
| 站点 | 熔炉/高炉/烟熏炉/营火（带优先级）、酿造台、炼药锅、砂轮、织布机、锻造台、制图台、切石机、铁砧、附魔台 |
| 仓储 | 最近箱子存取；给你东西时丢下并后退，避免又捡回来 |
| 家 | 睡过的床就是家；家的正方形保护区（默认边心距 64）禁止破坏、允许交互；三层拦截，Baritone 也挖不动 |
| 传送门 | 站进下界门/末地门（不是右键）；换维度后自动清掉旧任务和失效坐标 |
| 投影 | 直接读 `.litematic`（不需要 Litematica 的 API）：尺寸、材料清单、背包缺口 |
| 结构 | 读 SeedcrackerX 破解的种子与已定位结构，自己跑去搜刮 |
| Agent 层 | 只有"一步做不到"才升级：模型输出 `{"need_plan": "..."}` 或单步失败；计划里只能放合法动作，失败自动重规划 |

## 二、安装

1. 装 **Fabric 1.21.11**（Loader ≥ 0.16，Java 21+）
2. `mods/` 里放：本模组 + [Baritone](https://github.com/cabaletta/baritone)（1.21.11 fabric）+ Fabric API
3. 可选：Xaero 小地图（读航点）、JEI、Jade、Litematica、SeedcrackerX
4. 启动一次，编辑 `config/deepseek-companion.json`：填 `apiKey`、`botAccount`、`ownerName`
5. 用**小号**登录，聊天框敲 `.xd status`

> 模组**不打包 Baritone**。它自带一个 mixin 修掉 Baritone 1.21.x 的渲染线程死锁；
> 如果你更想改 Baritone 本体，用 `tools/PatchBaritone.java` 把同样的补丁烧进它的 jar。

## 三、命令（全部本地）

```
.xd status | commands | help
.xd key <key> | model <名字> | owner <ID> | login <密码>
.xd follow | come | stop | chop | mine <方块> | hunt [生物]
.xd craft <物品> | cook [物品] | give <物品> | hold <物品> | place <方块>
.xd use <方块> | brew <材料> | station <名字>[,物品] | enchant <物品>[,附魔]
.xd store [物品] | take <物品> | inventory
.xd sethome | home [clear] | protect [64|on|off]
.xd portal [nether|end] | schematic [名字] | structures [范围] | seed
.xd plan <目标> | plan status | plan stop
.xd mods | chat | debug on|off
```

模型**不能**自己拼命令：它只能从封闭的动作枚举里选，参数过注册表校验，真正下发给 Baritone 的只有白名单里那几条。

## 四、当前状态（alpha）

真实 1.21.11 客户端里验证过：模组正常加载、挖掘不再死锁、8 个原木能自动做出木镐、10 只僵尸能打下来。
**没**验证过：多人生存服、反作弊、内容模组的配方。

已知短板：没有跨会话记忆；计划中途不能查询；战斗只在平地打僵尸调过；站点自动化假设原版 GUI。

## 五、许可

本模组代码 MIT。Baritone 是 LGPL-3.0，本仓库不重新分发它。Minecraft 归 Mojang；
jar 里打包的配方表是客户端用到的游戏数据，仅用于本地配方查询。

## 六、插件动作 API（0.2.0+）

别的模组可以把**自己的功能**注册成小D 的动作，模型就能在聊天里请求它——和 Numen 的插件思路一样，但接口是可编译的 Java，不是一个 Lua 沙箱。

```java
// 你的模组里实现这个接口
public final class FeedAction implements io.github.xiaod.deepseekcompanion.api.ActionProvider {
    public String id() { return "feed_nearest_animal"; }
    public String description() { return "给最近的动物喂食（arg 留空）"; }
    public ArgSpec argSpec() { return ArgSpec.NONE; }
    public boolean available() { return true; }              // 不可用时不会出现在提示词里
    public String validate(String arg) { return ""; }        // 参数校验，抛异常=打回
    public boolean run(String arg) { /* 在这里做你自己的事 */ return true; }
}
```

注册（`fabric.mod.json`）：

```json
"entrypoints": { "deepseekcompanion:actions": ["com.example.FeedAction"] }
```

或者在你的 `onInitialize` 里直接 `ActionRegistry.register(new FeedAction(), "your-mod");`。

约定：

- id 必须是小写蛇形、2~32 字符，**不能和内置动作重名**（重名会被拒绝并打日志）
- `run` 在客户端主线程调用，必须快速返回；长任务自己起状态机，在 `tick(boolean busy)` 里推进
- 参数先过你的 `validate`，我们只保证"模型不能凭空造出没注册的动作"，**不保证参数对你有意义**
- 默认开关 `enablePluginActions`，关掉后插件动作既不出现在提示词里也不会被解析
- 危险动作标 `dangerous() == true`，日志里会单独标 `[dangerous]` 方便审计

完整示例：`examples/ExampleAction.java`。

> 和 Numen 的差别：他们给模型的是 Lua 程序能力（循环/分支/一次几十步）；我们这一步先给的是**可扩展的原子动作**，线性表达层不变、token 不变贵。升级到"程序化动作"（受限 JSON 脚本：`repeat` / `if` / 条件）是下一步。

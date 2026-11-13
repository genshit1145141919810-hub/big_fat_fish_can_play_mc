# 其它 Minecraft Bot 的功能调研（给「大肥鱼能玩MC」）

调研时间：2026-11 · 对象：Altoclef、Mineflayer 生态、Mindcraft、Voyager、Baritone 未用能力、
以及客户端 QoL 系（Tweakeroo / ItemScroller / MiniHUD）与女仆类陪伴模组。
（Meteor Client 已单独调研，见 `docs/meteor-feature-survey.md`）

---

## 一、先说结论

1. **我们缺的不是"更多动作"，而是"任务失败时的结构化反馈"和"可复用的技能库"**。
   Altoclef 靠"把任务切小 + 每个原子任务自带失败重试"跑通全游戏；
   Voyager/Mindcraft 靠"把成功经验存成技能、失败时把错误喂回模型"。
2. **它们的行动空间都很窄**：Altoclef 把 `collect/smelt/craft/kill` 这类原语组合起来，
   而不是让模型自由发挥。这一点我们方向是对的，但**组合层（计划模板）太少**。
3. **可观测性决定可维护性**：Altoclef 有 `@task` 命令能打印任务树，
   Mindcraft 有任务 JSON 与超时/阻断动作配置。我们的 `.xd why` 只是第一步。

---

## 二、对象与强项对照

| 项目 | 定位 | 强项 | 对我们有用的部分 |
|---|---|---|---|
| [Altoclef](https://github.com/gaucho-matrero/altoclef) | 客户端 bot（Baritone 驱动） | 400+ 物品可从头获取；任务树；自动躲弹幕；聊天指令（白名单/黑名单）；配置可热重载；**2021 年首个全自动通关** | 任务树设计、失败重试策略、"butler" 式指令权限、物品获取覆盖度 |
| [Mineflayer](https://github.com/PrismarineJS/mineflayer) + 插件 | Node.js bot 框架 | 插件化：pathfinder / collectblock / craft / pvp / armor-manager / autoeat / blockfinder / furnace / chest / villager | 模块边界划分（每个能力一个插件）、容器与交易抽象 |
| [Mindcraft](https://github.com/kolbytn/mindcraft) | LLM 驱动的多 bot 框架 | 角色 profile（人格+提示词）、任务 JSON（目标/初始背包/超时/阻断动作/tech tree）、多 bot 协作、embedding 选例、聊天/代码/视觉/TTS 分开配模型 | 任务 JSON 的"可测试性"、profile 化人格、多模型分工 |
| [Voyager](https://arxiv.org/abs/2305.16291) | 研究型 LLM agent | 自动课程、**技能库（代码形式）**、环境反馈 + 自我校验的迭代提示 | 技能库 + 自校验循环（我们的"重算"只是弱化版） |
| Baritone（已装，未用满） | 寻路/挖掘/建造 | `#build` 投影建造、`#explore`、`#farm`、`#sel/#set`、waypoint、`#tunnel`、elytra | 建造（我们有 Litematica 解析）、自动探索、农田 |
| Tweakeroo / ItemScroller / MiniHUD | 客户端 QoL | 批量合成、物品栏快速整理、信息叠层（光照/结构/生物群系） | 批量合成交互（一次合多份、多结果格）、HUD 信息组织 |
| 女仆类陪伴模组（如 Touhou Little Maid） | 陪伴 NPC | 任务清单 UI、跟随/守卫/农场模式、物品栏与装备管理 | **交互设计**：让玩家能"指挥"而不是只靠聊天 |

---

## 三、值得学的 12 个点（按收益排序）

| # | 学到什么 | 我们现在 | 差距 / 要做的事 | 量级 |
|---|---|---|---|---|
| 1 | **任务树 + 每节点失败重试** | 线性计划 + 失败重算，最多 3 次 | 把 `.xd why` 的依赖图变成可查询的任务树：每个节点带状态（待办/进行/失败/完成）与失败原因，重试策略挂在节点上而不是整条计划 | 中 |
| 2 | **技能库（Voyager 式）** | 每次重新规划 | 把"成功过的计划"按目标+初始背包特征存下来（`memory/skills.json`），下次同目标直接复用并微调 | 中 |
| 3 | **自我校验** | 只校验"物品变多了" | 校验**结果语义**：合成后检查产物+材料消耗是否对得上；挖矿后检查背包/掉落；失败时把"期望 vs 实际"喂给模型 | 小 |
| 4 | **任务 JSON 化（Mindcraft）** | 无正式测试基准 | 给一条链写可重复的验收用例：目标、初始背包、超时、允许动作、期望结果 —— 我们已经有 TestDriver，补一层断言就能跑回归 | 小 |
| 5 | **profile 化人格** | 单一提示词 | 多份人格 JSON（闲聊型/干活型/话少型），`.xd profile <名>` 切换 | 小 |
| 6 | **指令白名单 + 主任识别（Altoclef Butler）** | 有 owner 判定 | 补"只接受白名单玩家的指令"、私聊指令（`/msg`）解析、危险动作二次确认 | 小 |
| 7 | **容器/交易抽象（Mineflayer）** | 容器是散在控制器里的 | 抽一层 `ContainerApi`（读/写/找同类箱/记录坐标），交易（村民）单独一层 | 中 |
| 8 | **批量合成（ItemScroller）** | 一次一份 | 一次点多次合成、处理"多结果格/副产物"、材料不够时自动降级配方 | 小 |
| 9 | **建造（Baritone `#build` / 我们的 Litematica）** | 只算材料清单 | 把 `.litematic` 变成真正的放置任务（分层、缺料报告、进度可续） | 大 |
| 10 | **自动探索（`#explore`）** | 只有去指定点 | 无人区探索 + 记录发现（村庄/矿洞/结构），和 SeedcrackerX 结构定位合并 | 中 |
| 11 | **多 bot 协作（MineCollab）** | 单 bot | 多个实例分工（一个挖矿一个运输），接口上要"共享任务板" | 大 |
| 12 | **热门配置项热重载** | 改配置要重启 | 配置改动自动生效 + `.xd reload` | 小 |

---

## 四、明确不学

- **Altoclef 的"terminator"模式**（偷偷攒装备再打玩家）：定位不符，也会被服务器封。
- **Mindcraft 的 `allow_insecure_coding`**（让模型写 JS 执行）：虽然强大（Voyager 也走这条），
  但沙箱逃逸风险高；我们目前用"封闭动作枚举 + 计划 JSON"更稳。
- **服务器端假人/多开刷资源**：同一条流水线，但性质是作弊。
- **任何战斗光环、透视渲染**：见 Meteor 调研文档第五节。

---

## 五、建议落地顺序

**P0（这一周）**
1. 任务树 + 节点级失败原因（`.xd plan tree` 可打印）
2. 自我校验补强：合成产物与材料消耗对账
3. 任务 JSON + 断言，跑 3 条回归链（木镐/石镐/铁镐）

**P1（下一版）**
4. 技能库（成功计划缓存）
5. 指令白名单 + 私聊指令
6. 批量合成

**P2（看兴趣）**
7. `.litematic` 真正建造
8. 自动探索 + 结构记录
9. profile 人格

---

## 六、一句话总结

Altoclef 教我们**怎么把大目标拆成能失败重试的小任务**；
Voyager 教我们**把成功经验存下来**；
Mindcraft 教我们**怎么让任务可测试、人格可切换**；
Baritone 还欠我们**建造和探索**。
这些都不需要新的"外挂能力"，全是把现有动作组合得更聪明。

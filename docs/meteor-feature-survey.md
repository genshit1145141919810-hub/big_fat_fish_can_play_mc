# Meteor Client 功能调研（给「大肥鱼能玩MC」看的）

调研对象：`meteor-client-1.21.11-86.jar`（5.3 MB，166 个模块，6 个分类）
方法：解包后扫描 `meteordevelopment/meteorclient/systems/modules/<分类>/*.class`，从常量池里取模块名与描述。
工具：`tools/scan-meteor.cjs`（可重跑）

统计：combat 30 / movement 29 / player 32 / render 35 / world 25 / misc 15

---

## 一、结论先说

Meteor 的价值不在"功能数量"，而在于**它把很多"照顾自己"的小事做成了常驻模块**（自动吃、自动换工具、自动补货、自动修）。这些正好是我们「陪伴型 AI」最该学的部分。

它另一大半（战斗光环、客户端透视、移动作弊）**一律不碰**：那些是"作弊客户端"的定位，不是"陪你玩的朋友"，而且会把我们辛苦建立的"自己挖矿、自己合成"的信任全毁掉。

娱乐类里真正值得移植的是 **Notebot（音符盒弹琴）** 和 **DiscordPresence**，其余多是客户端视觉糖。

---

## 二、该做（Do）——符合"陪伴 + 自己动手"的定位

| 模块 | 它做什么 | 为什么值得我们做 |
|---|---|---|
| `Notebot` | 读 NBS/TXT 曲谱，走过去给音符盒调音、按谱弹奏（娱乐，即你说的"音乐 bot"） | 和"朋友一起玩"最贴的娱乐功能；我们的 AI 本来就能走路、能交互，缺的是曲谱解析与节拍调度 |
| `AutoEat` | 饥饿/血量低于阈值自动吃 | 陪伴型必备：不用玩家操心它的生存 |
| `AutoTool` | 挖/打前自动换最合适的工具 | 我们已有 `equipPickaxe`，应扩展到斧/锹/剑 |
| `AutoReplenish` | 快捷栏物品用完自动从背包补 | 长任务（挖矿/建造）不中断 |
| `AutoMend` | 装备用经验修补 | 和"自己维护装备"一致 |
| `AutoArmor` | 自动穿最好的护甲 | 我们目前有 ArmorController，可借鉴其"按附魔打分" |
| `ChestSwap` | 胸甲/鞘翅自动切换 | 移动方式切换时很实用（我们有鞘翅需求再加） |
| `AutoSmelter` | 自动往熔炉塞可烧的东西 | 我们已有熔炼流程，可借用"什么值得烧"的判定 |
| `BetterChat` | 兼容各种服务器聊天格式、防刷屏 | 我们已有 5 级聊天解析，可补"服务器插件格式" |
| `BetterTab` | 整理 Tab 列表 | 低优先，但能让 AI 正确读玩家列表 |
| `Nametags` | 头顶显示名字/距离/装备耐久 | 陪伴感：能看到"队友状态" |
| `Waypoints` | 路径点管理 | 我们有 `Memory` 存家/仓库，可合并成"地点簿" |
| `DiscordPresence` | 把"我在玩 MC"显示到 Discord | 纯娱乐曝光，成本极低，宣传效果好 |
| `Trail` / `Breadcrumbs` | 走出彩色轨迹 | 让玩家看得见 AI 去过哪，叙事感强，纯客户端渲染 |

## 三、看情况（Maybe）——有用，但要先想清楚边界

| 模块 | 它做什么 | 风险 / 前提 |
|---|---|---|
| `AutoFish` / `AutoBreed` / `AutoShearer` / `AutoNametag` | 自动钓鱼/繁殖/剪羊毛/命名 | 属于"农活"，适合当 Butler 的扩展；但挂机会被服务器当 AFK 机器人 |
| `AutoSign` / `AutoBrewer` / `EXPThrower` | 自动写牌/酿造/扔经验 | 场景窄，等有人真的要求再做 |
| `StashFinder` | 扫描周边箱子/存储方块并记录 | 是"找别人基地"的功能，容易变味；只保留"记自己的仓库"部分 |
| `VeinMiner` / `Excavator` / `Nuker` / `SpeedMine` / `InfinityMiner` | 连锁挖矿/范围挖掘/秒挖/自动修镐 | 在生存里算作弊，且我们已有"按需挖矿"规划；只借"矿脉扫描"思路 |
| `AutoWalk` / `AutoJump` / `Parkour` / `SafeWalk` / `Step` | 走路辅助 | 我们已有 Baritone，重复实现没意义；`Parkour` 的"边缘判断"可借给逃跑 |
| `AntiAFK` | 防挂机踢出 | 只在"玩家要求它等"时开，且要能一键关 |
| `Portals` / `NoStatusEffects` / `PotionSaver` | 传送门里开界面 / 屏蔽药水 / 暂停药水 | 都是"偷时间"类小作弊，默认关闭 |
| `InventoryTweaks` / `MiddleClickExtra` / `BetterTooltips` | 背包操作增强 | 是给**玩家**用的 UI，不是给 AI 用的；可做进我们的管理界面 |
| `NoGhostBlocks` / `BuildHeight` / `LiquidInteract` | 放置/交互类修正 | 只在"服务端不同步"时才有价值，默认关 |
| `AntiVoid` / `NoFall` | 防掉虚空/免摔 | 我们已有砌墙保命；免摔属于作弊，不做 |
| `Ambience` / `SoundBlocker` / `PopChams` / `ItemPhysics` / `Blur` / `Zoom` / `Fullbright` | 客户端视觉/音效 | 纯客户端、不影响别人，但和"AI 陪你玩"关系不大；`Zoom` 可给玩家用 |
| `PacketLogger` / `Notifier` / `EntityOwner` | 调试与信息 | 调试价值 > 玩家价值，内部工具可留 |
| `FakePlayer` | 客户端假人（测试用） | 我们测试里很有用，可以借；不面向玩家 |

## 四、KillAura 里值得学的那部分（只用于打怪，不碰玩家）

Meteor 的 `KillAura` 有一整套"怎么把一刀打得又准又不浪费"的工程细节，这些和"外挂"无关，是可以搬进我们的怪物战斗系统的。逐项判断：

| KillAura 的做法 | 学不学 | 我们的落地方式 |
|---|---|---|
| `SortPriority`（距离/血量/角度排序） | ✅ 学 | `CombatController.pickHostile`：距离 + 威胁加权（苦力怕/骷髅优先）+ 残血补刀 + 没视线扣分 |
| 目标过滤（`isAttackable`、婴儿、驯服、命名、拴绳） | ✅ 学 | 只打 `Monster`，不误伤宠物/命名怪；婴儿僵尸降权 |
| `wallsRange`（隔墙能打多远） | ✅ 学（反向用） | 我们干脆**隔墙不打**（`canSee` 不一刀不挥），省耐久也不像外挂 |
| `RotationMode`（Always / OnHit / None） | ✅ 学 OnHit | `aimAt`：每 tick 最多转 22°，没转到 12° 以内就先转不挥——真人手速就是这样 |
| `SettingGroup.Timing`（hitDelay / tpsSync） | ✅ 学思路 | 已有"攻击冷却 ≥0.9 才挥"，另外加了"嘴/盾在用时不动手"（`isUsingItem`） |
| `autoSwitch` + `swapBack`（战斗中换武器，打完换回） | ✅ 学 | `equipBestWeapon` 现在扫**整个背包**而不是快捷栏，换出来的武器打完自动换回原位 |
| `onlyOnClick` / `onlyOnLook` | ⚠️ 不适用 | 那是给真人留控制感的开关；AI 没有"点击"这回事 |
| `ShieldMode`（Break/Ignore） | ❌ 不学 | 那是破盾工具人逻辑；我们不破玩家盾，只对付怪 |
| 多目标 `maxTargets` 同时打几个 | ❌ 不学 | 一次只打一个，多目标容易变成"光环"观感 |
| `range` / `wallsRange` 超过原版攻击距离 | ❌ 绝不学 | 保持原版 3.0 格，不加长臂 |

结论：**KillAura 值得学的只有"目标评估 + 平滑瞄准 + 武器调度 + 出手纪律"这四件事**，都是让它像人、不浪费。它的"同时打多个、穿墙、加长手"一概不要。

## 五、不该做（No）——会毁定位、也常有封号风险

- **战斗光环/自动打击**：`KillAura` `CrystalAura` `AnchorAura` `BedAura` `AutoTrap` `Surround` `Burrow` `AutoCity` `AntiAnchor` `AntiAnvil` `AntiBed` `AutoWeapon` `Offhand` `AutoTotem` `AutoLog` `ArrowDodge` `Criticals` `BowAimbot` `BowSpam` `Quiver` `AutoEXP` `AutoAnvil` `AutoClicker` `MultiTask`
  理由：这是 PvP 外挂的核心；我们的定位是"另一个玩家"，不是"代打"。而且服务端反作弊会连坐玩家账号。
- **移动作弊**：`Flight` `Speed` `NoFall` `Jesus` `Spider` `Step` `HighJump` `LongJump` `Velocity` `Timer` `Slippy` `FastClimb` `ElytraBoost` `Blink` `ClickTP` `EntityControl` `AirJump` `Scaffold` `NoSlow` `ReverseStep`
  理由：破除物理规则，破坏服务器公平；我们自己已经有"走路 + 挖矿 + 造桥"的正当实现。
- **客户端透视/作弊渲染**：`ESP` `StorageESP` `Chams` `WallHack` `Tracers` `HoleESP` `CityESP` `VoidESP` `Xray` `LogoutSpots`
  理由：虽然纯客户端，但本质是"看穿"，一旦被录屏传播就是外挂形象。
- **恶意/破坏类**：`Spam` `MessageAura` `OffhandCrash` `PacketCanceller` `ServerSpoof` `AntiPacketKick` `MountBypass` `GhostHand` `AirPlace` `Reach` `PacketMine`
  理由：前几个是明确攻击性/崩溃性功能；`Reach`/`AirPlace` 是作弊；`PacketCanceller` 会和服务端较劲。
- **给方块刷东西/自动农场刷屏**：`AutoAnvil` `SelfAnvil` `SelfTrap` `SelfWeb` `HoleFiller` `LiquidFiller` `SpawnProofer` `HighwayBuilder` `EChestFarmer`
  理由：有的偏辅助、有的偏搞事；我们要么已有正当实现（建造/铺路），要么完全不需要。

---

## 六、Notebot 拆解（娱乐功能里最值得移植的一个）

从 jar 里能看到的实现骨架：

- `systems/modules/misc/Notebot`：主流程（Stage：`Tune` / `Play` / `Wait`）
- `utils/notebot/song/Song`、`Note`：曲谱数据结构（音高、乐器、时间刻）
- `utils/notebot/decoder/`：`NBSSongDecoder`（NBS 二进制曲谱）、`TextSongDecoder`（文本曲谱）、`SongDecoders`（注册表）
- `utils/notebot/instrumentdetect/InstrumentDetectMode`：靠方块/物品判断"这个音符盒是哪种乐器"
- `utils/notebot/NotebotUtils`：调音与按键时序
- `commands/.../NotebotCommand` + `gui/screens/NotebotSongsScreen`：`.notebot load/play/stop`、选曲界面

移植建议（对我们）：

1. **不需要照抄 UI**。我们只需要 `Song` 数据结构 + NBS 解码器 + 播放调度器。
2. 播放方式两条路：
   - **A（推荐）**：真的走到音符盒旁边、按谱右键/调音 —— 忠于原作，但依赖地形里有音符盒，速度慢。
   - **B**：AI 自己带音符盒、搭一个小舞台（它能合成木板→音符盒），按谱摆好再弹 —— 更像"陪你玩"的表演，视觉效果好。
3. 节拍调度交给现有 tick 循环（50 ms 粒度），时间刻精度足够；NBS 的 tempo 换算成 tick。
4. 权限与安全：只允许 AI 在"自己家附近"弹，避免在别人服务器刷屏扰民；`.xd music` 开关默认关。

工作量估计：NBS 解析 + 数据结构 1 天，播放调度 1 天，音符盒摆放/调音 1–2 天。

---

## 七、已经落地的部分（截至 0.4.0-alpha.2）

| Meteor 模块 | 我们的实现 | 说明 |
| --- | --- | --- |
| `AutoEat` | `BotActions.eat` + 战斗里的进食优先 | 饿了/残血就吃，**吃优先于打** |
| `AutoTool` | `AutoTool.equipFor` | 挖什么方块换什么工具（镐/斧/锹/剪刀），主背包里的会先换手上 |
| `AutoReplenish` | `AutoTool.replenish` | 快捷栏空了从背包补货，两秒看一次 |
| `AutoArmor` | `ArmorController` | 自动穿最好的护甲，按保护/耐久打分 |
| `AutoMend` | 用经验修补（脚本里会优先留经验） | 简化版 |
| `Nametags`（部分） | 聊天/状态里报告血量与距离 | 没做渲染层，先保证信息可用 |
| `Waypoints` | `Memory` 的 home/stash + `.xd waypoints` | 地点簿 |
| `StashFinder`（只用自己的） | `Memory.noteStash` + `.xd store/take` | 只记自己的箱子，不做扫别人基地 |
| `BetterChat` | 五级聊天解析 + `.xd chat` 自检 | 服务器改格式也能读 |
| `Trail` / `Breadcrumbs` | 事件日志（`.xd events`） | 不画线，但"它去过哪、干了啥"有记录 |
| `DiscordPresence` | 未做 | 半小时的活，等发布稳定再说 |
| `Notebot` | 未做 | 见第八节的移植路线 |

刻意没有落地：`KillAura` 系列、任何移动作弊、透视渲染、`Spam`/`OffhandCrash` 这类攻击性模块 —— 理由见第五节。

## 八、建议的落地顺序

1. **AutoEat / AutoTool / AutoReplenish / AutoMend**（生存自理，收益最大）
2. **Nametags + Waypoints**（陪伴感与地点记忆，和现有 `Memory` 合并）
3. **BetterChat / BetterTab**（提高在服务器上的鲁棒性）
4. **Notebot（音乐）**（娱乐亮点，做 demo 用，放最后）
5. **DiscordPresence**（宣传用，半小时的活）

明确不做的：任何战斗光环、移动作弊、透视、恶意包。

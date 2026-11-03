# 大肥鱼能玩MC (Big Fat Fish Can Play MC)

**A DeepSeek-powered Minecraft companion that plays on a real second account.**

> 中文速览：它不是 NPC，也不是假客户端。它跑在**你的第二个正版账号**所在的真实客户端里，
> 用普通玩家动作（走路、挖、右键、点格子、发聊天）做事，寻路交给 Baritone。
> 你在聊天里叫它，它会回话、跟着你、挖矿砍树、做饭、合成、打架、存箱子；
> 一件事一步做不完时，它自己拆成多步计划。
> **Alpha：能用，但一定有毛边，出问题先看日志。**

---

## What it actually does

This is a *survival companion*, not a chat toy and not a cheat client. It keeps its own
inventory, mines what it needs, crafts intermediate items, smelts, eats, and defends itself.

| Area | What works today (verified in a real 1.21.11 client) |
|---|---|
| Chat | Understands mentions in normal chat, replies in character, tolerates server plugins that rewrite chat formats |
| Following | Follow, come, stop, and "hold still" commands; walks with Baritone |
| Gather | `get <item>` chains: chopping → planks → sticks → crafting table → tool, with staged milestones (wooden → stone → iron → diamond pickaxe) |
| Mining | Target selection from **vanilla loot tables** (iron ore and deepslate iron ore are the same thing to it), item families (any log is wood), staircase/vertical descent to where the ore actually is |
| Crafting | Real GUI clicks: 2×2 in inventory, 3×3 on a placed table, correct craft counts, clears the grid before placing |
| Smelting | Builds and places a furnace when there is none, waits the real cooking time, takes the output |
| Self-care | Eats before fighting, swaps to the best weapon in the whole inventory, picks its tool back up after use |
| Cleanup | Picks up and re-places its own crafting table/furnace, and **recovers them** as soon as the plan no longer needs them |
| Combat | Mob-only combat: threat-weighted target picking (creeper/skeleton first), human-like smooth aiming, vanilla attack cooldown, critical hits, wall-in at low HP |
| Memory | Home + stash locations and past failures survive restarts |
| Diagnostics | `.xd why <item>` prints the whole plan and why a recipe was chosen or rejected |

## What it deliberately does **not** do

- No kill aura / multi-target swinging, no reach extension, no flight, no x-ray, no packet tricks.
- No player-vs-player automation. It fights monsters, full stop.
- No “one click wins” magic: it needs the same materials you would.

If a feature would make it look like a cheat client, it is not in here — that is a design
decision, not an oversight.

## Requirements

- **Minecraft 1.21.11**, **Fabric Loader 0.20.1+**
- **Baritone** (strongly recommended: all walking/movement uses it; without it the bot can't travel)
- Optional but used if present: Xaero's Minimap/World Map, Litematica, SeedcrackerX
- A DeepSeek API key (or any OpenAI-compatible endpoint — `.xd base`, `.xd model`, `.xd header`)
- A **second premium Minecraft account** (the mod runs inside that account's client)

## Install

1. Install Fabric Loader for 1.21.11 and put `fabric-api` + `baritone` in `mods/`.
2. Drop `big-fat-fish-can-play-mc-<version>.jar` into the same `mods/`.
3. Launch that instance with your **second** account and join the world/server.
4. In game: `.xd key sk-...` → `.xd owner <your name>` → start talking to it in chat.

## Commands (all local, none of them spam server chat)

```
.xd key <api key>        set DeepSeek (or compatible) key
.xd base <url>           any OpenAI-compatible base URL
.xd model <name>         model name
.xd header <name>        custom auth header (Azure style)
.xd owner <name>         who it listens to
.xd status / .xd see     what it thinks it is doing
.xd get <item> [n]       acquire something end-to-end
.xd why <item>           print the computed plan and why
.xd stop                 cancel everything
.xd events               recent events (hurt, death, failures)
.xd recipe <item>        recipe lookup
.xd chat                 chat-parsing self check
```

## Config

`config/bigfatfish.json` next to the game directory. Notable switches: `enableButler`
(pickup/unstick/junk), `enablePluginActions`, `useBaritoneAvoidance`, combat tuning
(`retreatHealth`, `critEnabled`, `switchWeapon`), and `followDistance`.

## Known limitations (alpha, honestly)

- Deep-ore mining is the weakest part: it digs a shaft, but very deep targets on a server
  with anti-cheat may still fail.
- Occasional crafting-grid hiccups; it detects them, prints the grid contents, and re-plans.
- The model is only asked when a single step provably cannot finish the job, so odd edge
  cases can end with “不知道 X 从哪来” instead of a guess.
- Baritone can log a caught `NullPointerException` from Fabric's loot API while mining;
  it does not crash the game.
- Multiplayer servers with strict anti-cheat are **not** tested. Use your own judgement.

## Safety / ethics

- Use your **own** account and **your own** API key. Do not run it on accounts you do not own.
- It behaves like a player because it *is* a client: any rule that forbids automation on a
  server applies to it too. Ask the server owner first.
- Not affiliated with Mojang, Microsoft, DeepSeek, or Baritone.

## Fan art credit

The character artwork shown in the gallery is **fan art**, not official art of this project:
- the artist allows redistribution; it is used here for **non-commercial** promotion only,
- it is not affiliated with Mojang, Microsoft, DeepSeek, or the character's rights holder,
- if the artist wants the credit changed or the image removed, open an issue and it will be handled.

## Source / issues

Source and issue tracker: <https://github.com/genshit1145141919810-hub/big_fat_fish_can_play_mc>
License: MIT.

---

# 中文说明（简版）

**大肥鱼能玩MC** 是给 Minecraft 配一个"真玩家账号"当搭子：它跑在你的**第二个正版账号**的客户端里，
用普通玩家动作做事，寻路交给 Baritone。你在聊天里叫它，它会回话、跟随、挖矿砍树、做饭合成、
打架、存箱子；一步做不完的事情它会自己拆成多步计划。

它**不做**任何打架光环、透视、飞行、加长手这类外挂功能；也**不打玩家**，只打怪。
需要：1.21.11 + Fabric + Baritone（强烈建议）+ DeepSeek 或任意 OpenAI 兼容接口的 key。

安装：把 jar 丢进第二个账号的 `mods/`，进游戏 `.xd key sk-...`、`.xd owner 你的ID`，
然后正常在聊天里跟它说话就行。**Alpha 版本**，能用但会有毛边，出问题先看日志，
`.xd why <物品>` 能看到它到底怎么想的。

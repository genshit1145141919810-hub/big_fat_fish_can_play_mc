**A survival companion that plays on your second premium account.** It is not an NPC and not a fake client: the mod runs inside a real Minecraft client logged in with your second account, and it does things with ordinary player actions (walk, mine, right-click, click inventory slots, send chat). Pathfinding is delegated to Baritone.

It chats with you, follows you, chops wood, mines, smelts, crafts, fights monsters, stores items, and when a single action cannot finish a job it splits it into a short plan and works through it.

**Alpha:** usable, but rough around the edges. If something goes wrong, check the log; `.xd why <item>` prints what it was thinking.

## What it actually does

- **Chat**: understands mentions in normal chat, replies in character, tolerates server plugins that rewrite chat formats.
- **Follow / come / stop**: walking is done through Baritone.
- **Gathering**: `get <item>` resolves the whole chain — chop → planks → sticks → crafting table → tool — with milestones (wooden → stone → iron → diamond pickaxe).
- **Material discovery from the game itself**: it reads vanilla loot tables and item tags, so iron ore and deepslate iron ore are the same thing to it, and any log counts as wood.
- **Crafting**: real GUI clicks, 2×2 in the inventory, 3×3 on a placed table, correct craft counts, clears the grid before placing.
- **Smelting**: builds and places a furnace when there is none, waits the real cooking time, takes the output.
- **Self-care**: eats before fighting, swaps to the best weapon in the whole inventory, picks its tool back up afterwards.
- **Cleanup**: recovers its own crafting table and furnace as soon as the plan no longer needs them.
- **Combat (monsters only)**: threat-weighted targeting (creeper and skeleton first), human-like smooth aiming, vanilla attack cooldown, critical hits, walls itself in at low health. Eating always takes priority over attacking.
- **Memory**: home and stash locations plus past failures survive restarts.
- **Diagnostics**: `.xd why <item>`, `.xd events`, `.xd see`, `.xd status`.

## What it deliberately does NOT do

- No kill aura or multi-target swinging, no reach extension, no flight, no x-ray, no packet tricks.
- No player-vs-player automation. It fights monsters, full stop.
- No "one click wins": it needs the same materials you would need.

If a feature would make it look like a cheat client, it is not in here. That is a design decision, not an oversight.

## Requirements

- Minecraft **1.21.11**, Fabric Loader **0.20.1+**
- **Baritone** (strongly recommended: all movement goes through it; without it the bot cannot travel)
- Optional and used when present: Xaero's Minimap/World Map, Litematica, SeedcrackerX
- A DeepSeek API key, or any OpenAI-compatible endpoint
- A **second premium Minecraft account** — the mod runs inside that account's client

## Install

1. Install Fabric Loader for 1.21.11, put `fabric-api` and `baritone` in `mods/`.
2. Drop `big-fat-fish-can-play-mc-<version>.jar` into the same `mods/`.
3. Launch that instance with your second account and join the world or server.
4. In game: `.xd key sk-...` then `.xd owner <your name>`, and start talking to it in chat.

## Commands

All commands are local; none of them spam server chat.

- `.xd key <api key>` — set the DeepSeek key
- `.xd base <url>` — any OpenAI-compatible base URL
- `.xd model <name>` — model name
- `.xd header <name>` — custom auth header (Azure style)
- `.xd owner <name>` — who it listens to
- `.xd get <item> [n]` — acquire something end to end
- `.xd why <item>` — print the computed plan and why a recipe was chosen or rejected
- `.xd status` / `.xd see` — what it thinks it is doing
- `.xd events` — recent events (hurt, death, failures)
- `.xd stop` — cancel everything
- `.xd recipe <item>` — recipe lookup
- `.xd chat` — chat parsing self check

## Config

`config/bigfatfish.json` next to the game directory. Notable switches: `enableButler` (pickup / unstick / junk), `enablePluginActions`, `useBaritoneAvoidance`, combat tuning (`retreatHealth`, `critEnabled`, `switchWeapon`), `followDistance`.

## Known limitations (alpha, honestly)

- Deep-ore mining is the weakest part: it digs a shaft, but very deep targets on servers with strict anti-cheat may still fail.
- Occasional crafting-grid hiccups; it detects them, prints the grid contents, and re-plans.
- The model is only consulted when a single step provably cannot finish the job, so odd edge cases can end with "I don't know where X comes from" instead of a guess.
- Baritone can log a caught `NullPointerException` from Fabric's loot API while mining. It does not crash the game.
- Multiplayer servers with strict anti-cheat are not tested. Use your own judgement.

## Safety and ethics

- Use your own account and your own API key. Do not run it on accounts you do not own.
- It behaves like a player because it is a client: any server rule that forbids automation applies to it too. Ask the server owner first.
- Not affiliated with Mojang, Microsoft, DeepSeek, or Baritone.

## Fan art credit

The character artwork shown in the gallery is **fan art**, not official art of this project:
- the artist allows redistribution; it is used here for **non-commercial** promotion only,
- it is not affiliated with Mojang, Microsoft, DeepSeek, or the character's rights holder,
- if the artist wants the credit changed or the image removed, open an issue and it will be handled.

## Source

Source and issue tracker: https://github.com/genshit1145141919810-hub/big_fat_fish_can_play_mc

License: MIT.

---

# 中文说明

**大肥鱼能玩MC** 给 Minecraft 配一个"真玩家账号"当搭子：它跑在你的**第二个正版账号**的客户端里，用普通玩家动作做事，寻路交给 Baritone。你在聊天里叫它，它会回话、跟随、挖矿砍树、做饭合成、打架、存箱子；一步做不完的事情它会自己拆成多步计划。

它**不做**任何打架光环、透视、飞行、加长手这类外挂功能，也**不打玩家**，只打怪。

需要：1.21.11 + Fabric + Baritone（强烈建议）+ DeepSeek 或任意 OpenAI 兼容接口的 key，以及一个第二正版账号。

安装：把 jar 丢进第二个账号的 `mods/`，进游戏 `.xd key sk-...`、`.xd owner 你的ID`，然后正常在聊天里跟它说话。

**Alpha 版本**，能用但会有毛边，出问题先看日志，`.xd why <物品>` 能看到它到底怎么想的。

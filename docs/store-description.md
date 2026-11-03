# Big Fat Fish Can Play MC（大肥鱼能玩MC）— Modrinth / CurseForge description

## Summary (short, 200 chars)

大肥鱼能玩MC (Big Fat Fish Can Play MC) — a real second-account AI teammate: chat, follow,
mine, craft, fight and plan. Works with any OpenAI-compatible endpoint.

A real player-account AI companion: chat with a DeepSeek-driven teammate that follows,
mines, crafts, cooks, fights and plans, all through normal player actions.

## Body

**Not an NPC. Not a fake client.** The mod runs inside a second, real premium account's
client and does only what a player can do: walk, mine, right-click, click slots, type in
chat. Pathing is Baritone's job.

### Talk to it

Type in chat. It answers in one short teammate-style line, because the DeepSeek model is
allowed to pick exactly one action (or, when one action is not enough, ask for a plan).

```
You: 小D 帮我砍点树
Bot: 行，那边有棵杉木我去了
```

Local commands start with `.xd` and never leave your client (that is also how the API key
stays local): `.xd status`, `.xd craft iron_pickaxe`, `.xd plan 弄 20 个铁锭`, …

### What it does

* **Follow / come / stop**, chop trees, mine a block type, hunt animals, give items,
  store and take from the nearest chest.
* **Craft tree**: asks for a wooden pickaxe and it works out planks and sticks first,
  crafting in the 2×2 grid and then on a crafted-and-placed table.
* **Cooking** across furnace, blast furnace, smoker and campfire, choosing the right one.
* **Stations**: brewing, cauldron, grindstone, loom, smithing, cartography, stonecutter,
  anvil, enchanting table.
* **Combat**: fights by default — ten zombies is not a reason to run. It drifts back while
  swinging so knockback keeps the spacing, and only at ≤2 HP or a close creeper does it
  disengage — by walling itself in, not by turning its back.
* **Home protection**: the bed it sleeps in becomes home; a square zone (apothem 64 by
  default) cannot be broken by the bot, though it can still interact.
* **Portals**: walks into nether/end portals, and cleans up stale tasks when the dimension
  changes.
* **Litematica**: reads `.litematic` directly — size, material list, inventory diff.
* **SeedcrackerX**: reads the cracked seed and located structures so it can go looting.

### Safety and control

The model can never write a command. It picks from a closed enum, arguments are checked
against the game registry, and only a whitelisted set of Baritone commands is emitted.
Everything that should not be public (API key, plans, status) stays client-side.

### Requirements

* Minecraft **1.21.11**, Fabric Loader ≥ 0.16, Java 21+
* **Baritone** (fabric 1.21.11) + Fabric API
* optional: Xaero minimap/world map, JEI, Jade, Litematica, SeedcrackerX

### Alpha

Verified in a real client: loads, no mining deadlock, builds a wooden pickaxe from eight
logs, survives ten zombies. Not yet verified on multiplayer servers or with content mods.
Expect rough edges and check `logs/latest.log` for `[XiaoD]` lines.

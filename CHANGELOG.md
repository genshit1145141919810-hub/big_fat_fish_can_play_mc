# Changelog

## 0.4.0-alpha.3 — English UI, Meteor-style self-care, polish (1.21.11)

First build with an English interface and the "look after yourself" modules borrowed from
Meteor Client (the safe ones). Everything below was verified in a real 1.21.11 Fabric client.

### Added
- **Localisation**: the game language decides the interface language. Chinese is the source
  language; `assets/big_fat_fish_can_play_mc/lang/en_us.json` carries 181 strings plus 14
  template rules, applied at the three chat output funnels. Other languages fall back to
  English, then to Chinese. `tools/check-lang.cjs` reports untranslated strings and typos.
- **AutoTool**: mining picks the fastest tool for the block (pickaxe/axe/shovel/shears) across
  the whole inventory, instead of always holding a pickaxe.
- **AutoReplenish**: an emptied hotbar slot is refilled from the main inventory (2 s throttle,
  one slot at a time).
- **Fair play mode** (`.xd fair on`): targets must be inside the line of sight (raycast) and
  Baritone's `#mine` is bypassed, so ores are not picked from chunk data. Off by default,
  because that is how it can actually mine.
- `.xd fair`, `.xd base`, `.xd header` and a help entry for each.

### Changed
- Weapon swap-back after combat now really swaps the weapon back with the item that was in the
  hand, instead of only changing the selected slot.
- Descending for deep ores relocates to a nearby solid spot when the shaft hits bedrock, fluid
  or a large drop, instead of giving up immediately.

### Fixed
- Crafting verifies the result slot is actually empty after taking the output (and retries),
  guarded so the furnace input slot is never touched.
- English/Chinese wording for the new settings, plus documentation for the localisation
  workflow (`docs/LOCALIZATION.md`).

## 0.4.0-alpha.2 — acquisition rework, real survival loop (1.21.11)

This is the first alpha that survives **from nothing**: chop → planks → table → tools → mine →
smelt → better tools. Everything below was verified in a real 1.21.11 Fabric client.

### Added
- **Loot-table driven material discovery** (`BlockDrops`): reads the vanilla
  `data/minecraft/loot_table/blocks/*.json` through the classpath, so iron ore and deepslate
  iron ore (and modded ores) are understood without hand-written maps.
- **Item families** (`ItemFamilies`): built from vanilla item tags — any log counts as wood,
  any planks as planks, stone/deepslate as stone-tool material.
- **Requirement-graph planner** (`AcquireTask`): collects every demand, merges counts, and
  emits one batched pass ("mine 10 logs, then craft 3× planks") instead of
  mine-craft-mine-craft interleaving. Demands iterate to a fixed point before yields are fixed.
- **Milestone staging**: iron tool = wooden pickaxe → stone pickaxe → iron pickaxe, each stage
  planned independently against the current inventory.
- **Estimation with slack**: raw materials are mined with a buffer (hard floor of 10 logs);
  shortfalls are re-mined to the exact missing amount, not inflated.
- **Re-plan on failure**: a craft/smelt that cannot finish recomputes what is actually missing
  from the live inventory, up to a bounded number of times.
- **Deep descent**: staircase/vertical digging with bedrock/fluid/large-drop guards, used only
  when the target ore family is not already nearby; the rest of movement stays with Baritone.
- **Placed-block recycling** (`PlacedBlocks`): remembers every crafting table/furnace it placed
  and mines it back **as soon as the remaining plan no longer needs it**.
- **Loot-safe placement**: clears a spot before placing, walks elsewhere if there is no room,
  and treats "a table is already nearby" as success.
- **Cooking data generated from the game jar** (`tools/gen-smelting.ps1`): smelting/blasting/
  smoking/campfire tables regenerated from `data/minecraft/recipe/*.json` (raw iron → iron ingot).
- **OpenAI-compatible endpoints**: any base URL (`/v1` handled), custom auth header, automatic
  `max_tokens` / `max_completion_tokens` fallback, tolerant response parsing.
- **Combat improvements borrowed from KillAura's engineering, mob-only**: threat-weighted target
  selection (creeper/skeleton first, low HP finish), human-like smooth aiming (≤22°/tick, swing
  only when aligned), no hitting through walls, weapon switching across the whole inventory with
  swap-back after combat, and **eating takes priority over attacking**.
- **Diagnostics**: `.xd why <item>` prints candidate plans, per-item sources and planner notes;
  `.xd dig <n>`; updated `.xd base` / `.xd header`.

### Fixed
- Craft steps only crafted once regardless of the planned count.
- `SMELT` passed the ingredient name instead of the output name.
- Mining completion counted the exact item id, so chopping spruce never satisfied "need 10 oak logs".
- Ore tags resolved to deepslate variants when only stone was around (and vice versa).
- Crafting grid was not cleared between attempts, producing "wrong recipe" junk items.
- Interacting with a crafting table 5–6 blocks away silently failed; it now walks over first.
- Butler's stuck detection fought mining/placement and cancelled tasks.
- Baritone `#mine` and the mod's own `goto` were cancelling each other during acquisition.

### Tooling
- `tools/new-world.mjs` (fresh test world from a template `level.dat`),
  `tools/watch-test.ps1` (a test run returns the moment it settles),
  `tools/gen-smelting.ps1`, `tools/scan-meteor.cjs` (module survey),
  `docs/meteor-feature-survey.md` (what we borrow from Meteor Client and what we refuse to).

## 0.3.0-alpha.1 — perception layer

### Added
- **Perception layer** (`Perception`): the model now gets a light-level indicator, a one-line
  threat summary and a live event feed in every snapshot.
- New query actions: `look` (what am I facing), `scan <block>` (where/how many nearby),
  `danger` (hostiles with distance, health and creeper fuse), `containers` (chests, furnaces,
  tables around), `path` (task/plan state, stuck detection), `drops` (items on the ground),
  `players` (who is nearby and what they hold).
- `.xd see` prints exactly what the perception layer feeds the model — handy for debugging.
- Event notes from plan failures, flee interrupts and block-in, so re-planning has context.

### Rationale
Instead of a programmatic action language, we thickened perception: a linear loop that always
sees fresh state re-plans cheaply, without paying for a sandboxed script VM.

## 0.2.0-alpha.1 — plugin actions

### Added
- **Plugin action API** (`io.github.xiaod.deepseekcompanion.api.ActionProvider`): other mods can
  register their own actions through the `deepseekcompanion:actions` entrypoint (or
  `ActionRegistry.register`). The model sees them in its action catalogue; arguments are
  validated by the plugin; calls are logged, and `dangerous()` actions are flagged.
- `enablePluginActions` switch: with it off, plugin actions are neither advertised nor parsed.

### Changed
- `Intent` carries an optional plugin action id, so the closed-enum pipeline and plugin
  actions share one validation path.

## 0.1.0-alpha.1 — first public alpha (1.21.11)

First release of the 1.21.11 port. Everything below is verified in a real client unless
marked otherwise.

### Added
- **DeepSeek chat companion** on a real premium account: chat replies, follow, come, stop,
  chop, mine, hunt, give, store, take, use, brew, station, enchant, portal, plan.
- **Agent layer**: model escalates with `{"need_plan": "..."}` or when a single step fails;
  plans are validated against the same closed action enum, executed locally, and re-planned
  on failure (max 2 by default).
- **Craft tree**: recursive dependency resolution (planks → sticks → pickaxe), 2×2 in the
  player inventory, 3×3 on a placed crafting table, tag ingredients resolved at runtime.
- **Home protection**: the bed becomes home; a square apothem-64 zone forbids breaking and
  allows interaction, enforced in the interaction manager *and* on the outgoing packet.
- **Block-in**: when nearly dead, walls itself in with two blocks instead of turning its back.
- **Knockback kiting**: hits while drifting back, so the mob's own knockback keeps spacing.
- **Portal traversal**: stands inside nether/end portals, detects the dimension change, and
  clears stale tasks (mining target, task markers, plan coordinates) afterwards.
- **Litematica `.litematic` reader**: size, per-material counts and inventory diff without
  needing Litematica's API.
- **SeedcrackerX bridge**: reads the cracked seed and located structures so the bot can loot.
- **Optional-mod awareness** (`.xd mods`): Baritone, Xaero, Litematica and SeedcrackerX are
  actually used; JEI/Jade/VeinMiner/InventoryTotem/Chunky and friends are reported to the model.
- **Chat parsing ladder**: tolerates server plugins that prefix chat or rewrite mentions.

### Fixed
- Baritone 1.21.x render-thread deadlock in `BlockOptionalMeta#getRegistryManager`
  (mixin, plus an optional bytecode patcher for people using stock Baritone elsewhere).
- Messages that used to spam public chat (owner-missing, flee notices, plan status) are now
  client-local, and identical lines are de-duplicated for 10 s.
- `.xd stop` now really stops: plans, mining, crafting, storage, interaction, portals, Baritone.
- In-flight model replies can no longer resurrect a plan the player just cancelled.

### Known issues
- Not tested on multiplayer servers or against anti-cheat.
- Recipes from content mods are not in the craft tree (vanilla table only).
- Combat tuning was done on flat ground versus zombies; skeletons and creepers are code-complete
  but only lightly exercised.
- No long-term memory across sessions.

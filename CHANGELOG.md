# Changelog

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

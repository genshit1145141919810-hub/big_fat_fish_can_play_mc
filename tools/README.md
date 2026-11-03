# tools/

Development and release tooling. Nothing here runs at game time except through the mod itself.

## Build and release

| file | purpose |
| --- | --- |
| `release-check.ps1` | Validate a built jar before publishing: version placeholder, icon, mixin configs, bundled data tables, and that Baritone is **not** inside the jar. |
| `publish.ps1` | Push a build to GitHub / Modrinth / CurseForge. Credentials come from `GITHUB_TOKEN`, `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN` (+ project ids); any platform without credentials is skipped. |
| `PatchBaritone.java` | Bakes the `BlockOptionalMeta` deadlock fix into Baritone's own jar with ASM, for people who prefer a patched Baritone over our runtime mixin. Requires JDK 21+ and `asm`/`asm-tree` on the classpath. |
| `make-icon.ps1` | Regenerates `assets/<id>/icon.png`. |

## In-game test harness

The mod can drive itself from a command file, so a test run needs no input automation.

| file | purpose |
| --- | --- |
| `launch.ps1` | Start the Fabric instance from the CLI (offline account, optional `-QuickPlay <world>`), writing to `logs/agent-launch.log`. |
| `mc-shot.ps1` | Focus the game window, send keystrokes, capture a screenshot. |
| `level-cheats.mjs` | Flip `Data.allowCommands` in a `level.dat` so a test world has cheats. |
| `litematic-inspect.mjs` | Decode a `.litematic` and print its palette/bit-packing analysis — this is how the reader's format assumptions were verified. |
| `chat-parse-check.mjs` | Mirror of the chat parsing rules with real-world server-plugin samples, so format regressions are caught without booting the game. |

Usage of the command file (`xd-test-commands.txt` in the game directory):

```
#step=3000          set delay between commands (ms)
/gamemode creative  lines starting with / go through sendChatCommand
.xd inv             local commands are handled in-process
#sleep=60000        wait, then continue
```

## Optional-mod and world tooling

| file | purpose |
| --- | --- |
| `mod-audit.ps1` | Read every `fabric.mod.json` in a mods folder (including jar-in-jar) and verify every declared dependency is provided. |
| `fetch-schematic.ps1` | Download a `.litematic` (or a zip containing them) into an instance's `schematics/` folder. |
| `gh-find-schematic.mjs` | Find downloadable `.litematic` files on GitHub via the API. |

# Alloy Printer: design

Records the decisions the printer is built on. Read it before changing placement, fixing or the server helper.

## Goal
A fast Litematica printer that never places a block wrong. Survival and creative. One jar for client and server.

## Layout
- `src/client`: the printer (needs Litematica + MaLiLib). `printer/` holds the core, `config/` + `gui/` the MaLiLib
  settings, and `mixin/` the rotation, sign, teleport and use hooks.
- `src/main`: common code and the optional server helper (`net/IntentPayload`, `server/`, a `mixin` on `BlockItem.place`).
- `src/gametest`: the client game test.

## Placement (PlacementSolver)
- Before every click, the solver simulates vanilla `BlockItem` placement against the real world. It tries all
  6 faces, 4 spots on each face, about 18 look directions, with and without sneaking.
- It clicks only when the simulated state equals the schematic state. Otherwise the spot is skipped and counted on the HUD.
- The server reads HEAD yaw for blocks that face where the player looks (updated once per server tick), but
  `LocalPlayer.getViewYRot` returns body yaw. `LocalPlayerViewMixin` and `Look` track the head yaw. Teleports reset
  the server rotation.

## Server helper (optional)
- The client sends an `IntentPayload` with the exact state it wants.
- On the server, a WrapOperation on `BlockItem.place` copies only the orientation properties onto the block the held
  item places normally. Item counts, waterlogging and neighbour-dependent properties stay vanilla.
- It can be turned off server-wide with `config/cytraprinter-server.json` (`enabled`).
- Without the helper (vanilla mode), the printer skips blocks it can't place exactly from where the player stands.

## User decisions (2026-10-14 to 10-17)
- Wrong blocks are always fixed: creative breaks them instantly, survival mines them with the best tool. Containers
  are never broken unless allowed.
- Clearing extra blocks (schematic air) is a setting, off by default.
- Never place liquids. Sign text, click-fix states and safe redstone order are on by default.
- Restocking goes through Alloy Container (`containerautofill`). Reflection calls
  `TakeItOutFeatures.onPrinterMissing`, so there is no hard dependency.
- The server is vanilla + Carpet with no anticheat, so the speed defaults can be high.
- Bedrock breaking is off by default. The client never receives the Efficiency `mining_efficiency` attribute, so
  `BedrockBreaker` sends START_DESTROY for the piston directly and clears the spot client-side.

## Testing
- `./gradlew runClientGameTest` builds every placement state of about 70 blocks, saves it as a schematic, clears it
  and prints it back in creative (with and without the helper) and in survival.
- Env vars: `CYTRA_TEST_BLOCKS=bell,observer` limits the blocks, `CYTRA_TEST_ONLY=extras` runs only the extras test
  (about 30 s), and `CYTRA_PRINTER_DEBUG=1` logs spots the printer can't solve.

## Settings UI (1.2.0)
Follows the workspace rule: settings live in the mod's own Nocturne screen and on an Alloy Hub page, both built from
the same config.
- **Source of truth stays MaLiLib** (`Configs`, `config/cytraprinter.json`). There is no migration and old configs keep
  working. Hotkeys stay MaLiLib hotkeys because they support combos and work in-game without the Hub.
- **One option list.** `PrinterOptions` turns each `Configs` group into rows. Booleans become toggles, integers
  steppers, doubles sliders, strings text fields, and string lists a comma-separated text field (`a, b, #tag`).
  The own screen and the Hub page both iterate the same groups, so a new option shows up in both.
- **Own screen** `PrinterSettingsScreen` (Alloy UI `AlloyScreen`, jar-in-jar alloy-ui 1.3.0). It has four tabs:
  Printer (incl. HUD), Accuracy, Blocks, Extras. The server helper status sits on the Printer tab. The footer has
  *Hotkeys* (opens the MaLiLib screen on its Hotkeys tab) and *Done*. Mod Menu, MaLiLib's config menu and the
  Open Settings hotkey all open this screen. The old `GuiConfigs` is only used for hotkeys.
- **Hub page** `HubPlugin` (entrypoint `cytra-hub`, compile-only against `libs/alloy-hub-*.jar`):
  - Category Building, with Printing as the master switch.
  - Main key: Toggle printing.
  - Status: printing, server helper, rate, remaining, missing items, problems.
  - Settings: the same groups as sections.
  - Actions: open settings, hotkeys, exclude looked-at block.
  - A HUD element for the HUD Layout editor.
  - Server tab (ops): helper on/off, saved to `cytraprinter-server.json` and re-announced to online players.
  - Without Litematica/MaLiLib the page only shows that they are missing.
- Player messages (printing on/off, exclude, death) are translatable.

## Open ideas
- Auto-craft missing items through Alloy Mass Craft (optional hook, like Restock).
- A server GameTest for the helper.

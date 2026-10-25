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

## Skipped blocks (1.3.0)
- `printer/Skipped` remembers spots the printer left alone, keyed by position. It keeps them after the player walks
  away so they can be found in a big build. There are three kinds:
  - WRONG: a wrong block it won't break (fixing off, protected, container, unbreakable).
  - UNSOLVABLE: no exact click from where the player stood.
  - STUCK: parked after too many retries.
- The scan updates spots in reach every printer tick.
- Spots out of reach are dropped every 10 s once they match the schematic.
- All spots are dropped on Retry, a world change, or a disconnect. The list is capped at 4096.
- `SkippedRenderer` (MaLiLib world-last renderer, the same line pipeline as Alloy Container) outlines them through
  walls within *Outline Range* (default 64): red = wrong, orange = can't place, yellow = gave up.
  The spot picked by *Next Skipped Block* pulses.
- Hotkeys *Next Skipped Block* and *Retry Skipped Blocks* are also actions on the Hub page.
  Next turns the view only while printing is off, because the printer owns the view otherwise.
  The Hub status shows the counts.

## Frame time (1.3.1)
- The printer tick runs on the client thread, so its cost lands on a frame. Measured with
  `CYTRA_TEST_ONLY=perf`: `Stats.timingSummary()` gives scan/place/tick avg, p95 and max.
- Scanning is cheap (~0.1 ms). The expensive part is a *failed* placement search: up to about 800 simulated clicks,
  about 1600 for rotation-16 blocks like signs, times 4 head yaws in vanilla mode.
- Placement search gets 2 ms per tick (`SOLVE_BUDGET_NANOS`). A search that runs out of time resumes next tick
  where it stopped (`Partial`: attempt + step), so long searches still finish.
- A spot found unplaceable is not searched again (`Unsolved` memo, up to 10 s) while the spot and its six
  neighbours, the player's eye position (within 0.25 blocks) and the head yaw stay the same.
- 1.3.0 → 1.3.1 on the big test build: worst tick while printing went from 25 ms to 2.5–5 ms. Standing at a finished
  build went from 6.5 ms p95 to 0.27 ms.

## Lag safety (1.3.2)
- The client shows its own clicks and breaks right away. With lag the server may still have the old block, and a
  click into a spot the server sees as taken puts the block next to it, which is a wrong block.
- The scan now skips every position in vanilla's `BlockStatePredictionHandler.serverVerifiedStates`, the client's
  own changes the server hasn't answered yet. This works however long the server lags; the old fixed wait
  (`confirmTicks`) stays for detecting reverts.
- The server helper (`PlacementAssist`) refuses a click whose placement moved off the aimed spot when the printer has
  an intent for the aimed spot.
- Breaking: the fastest tool, or a pickaxe when no tool beats a hand (glass), never a sword or a block in hand.

## 26.3 port (branch `mc26.3`, folder ~/Documents/Mods/26.3/cytra-printer)
- Toolchain: Loom 1.19.3 (`net.fabricmc.fabric-loom`, no remap, no mappings line), Loader 0.20.1, Fabric API
  0.165.0+26.3, Java 25 (`~/.gradle/jdks/jdk-25.0.4.1+1`), Litematica 0.29.1 / MaLiLib 0.30.2, Mod Menu 21.0.0.
- Left out on purpose (user's call): composter levels and extra reach.
- No Alloy UI / Alloy Hub for 26.3 yet, so the settings are MaLiLib's tabbed `GuiConfigs` again. When those exist,
  bring back `PrinterSettingsScreen`, `PrinterOptions` and the Hub page from `dev`.
- API notes: `mc.gui.screen()`/`setScreen`, `sendOverlayMessage`/`sendSystemMessage`, `ContainerInput.SWAP` +
  `handleContainerInput`, `SignTextSlot`, axes = `ItemTags.AXES` (stripping is a data-driven block transformer,
  so stripped/plain pairs come from block ids), MaLiLib `onExtractGuiOverlayPost` / new `onRenderWorldLast` /
  `registerInGameGuiRenderer`, `LayerRange` in `malilib.util.position`, Fabric `KeyMappingHelper`.

## Open ideas
- Auto-craft missing items through Alloy Mass Craft (optional hook, like Restock).
- A server GameTest for the helper.

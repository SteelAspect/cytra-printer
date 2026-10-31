# Alloy Printer

Own Litematica printer (places schematic blocks) plus an optional server helper for exact block states. Workspace rules: `../CLAUDE.md`. Design/decisions: `DESIGN.md`.

## What it is
- id `cytraprinter`, display name "Alloy Printer", jar `alloy-printer-<mod_version>.jar` (1.3.2 at last check). `environment: *`: the printer runs only on a physical client with Litematica; the server half is the helper (`BlockItem.place` wrap).
- Repo private `SteelAspect/cytra-printer`, work on `dev` -> `main`. `PROGRESS.txt` is current. A 26.3 port lives on branch `mc26.3` (`../26.3`).

## Stack
- MC 1.21.11, Loader 0.19.5, Fabric API 0.141.6+1.21.11, Loom 1.17.21, **Mojang mappings**, Java 21 (`~/.local/share/PrismLauncher/java/java-runtime-delta`).
- Litematica 0.26.16 + MaLiLib 0.27.20 from Modrinth maven (`modImplementation`; fmj only *recommends* them). Alloy UI `alloyui_version=1.3.0` jar-in-jar. Mod Menu 17.0.1 compile-only. Hub jar in `libs/` (`alloy-hub-0.6.0.jar`, compile-only; `-PwithHub` runs with it).

## Code map (`dev.steelaspect.cytraprinter`)
- Entrypoints: `CytraPrinter` (main), `client.CytraPrinterClient`, `client.ModMenuIntegration`, `client.compat.HubPlugin` (`cytra-hub`; page via `compat/HubPage`).
- `client/printer/`: `Printer` (the loop), `PlacementSolver`, `PlacePlan`, `Variants`, `Look`, `Hotbar`, `Restock`, `BedrockBreaker`, `Skipped`, `IdFilter`, `SignText`, `Stats`. `client/`: `ServerHelper`, `SkippedActions` + `SkippedRenderer` (outlines), `HudRenderer`, `InputHandler`, `PrinterBootstrap`, `config/Configs` (`config/cytraprinter.json`), `gui/` (`PrinterSettingsScreen`, `PrinterOptions`, `PrinterFrame`, `GuiConfigs` hotkeys only).
- Server/common: `net/{HelloPayload,IntentPayload}`, `server/{IntentStore,PlacementAssist,ServerConfig}` (`config/cytraprinter-server.json` `{"enabled":true}`), `state/StateRules`.
- Mixins: main `BlockItemPlaceMixin` (WrapOperation on `BlockItem.place`), `BlockItemInvoker`, `UseOnContextAccessor`. Client: `LocalPlayerRotationMixin` (`sendPosition` wrap, fake look), `LocalPlayerViewMixin` (`getViewYRot`), `LocalPlayerSignMixin` (`openTextEdit` cancel), `MultiPlayerGameModeUseMixin` (`useItem`), `ClientPacketListenerTeleportMixin` (`handleMovePlayer`), `ClientPacketListenerTimeMixin` (`handleSetTime`), accessors for axe/prediction/game mode.
- Keys in Controls (toggle printing, Next Skipped Block, Retry). Lang: `assets/cytraprinter/lang`.

## Build and test
```bash
export JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-delta
./gradlew build
./gradlew runClientGameTest                       # PrinterClientGameTest: ~70 blocks x every state, creative +/- helper, survival (long)
CYTRA_TEST_ONLY=settings ./gradlew runClientGameTest   # also: skipped | farm | extras (~30 s) | perf
CYTRA_TEST_BLOCKS=bell,observer ./gradlew runClientGameTest   # limit the big build
./gradlew runClientGameTest -PwithHub             # with Alloy Hub installed
```
`CYTRA_PRINTER_DEBUG=1` logs spots the printer can't solve. Only one game test class exists; `CYTRA_TEST_ONLY` picks the part. Run headless and muted; for changes only run the matching part (the full 440-state run was skipped for 1.3.0 at the user's request).

## Cross-mod links
- Restock via Alloy Container (`containerautofill`, Take-out repo) by reflection: `TakeItOutFeatures.onPrinterMissing`. Ask the **takeout** chat if that changes.
- Hub page here; Hub `mods.json` changelog gets a line from release.py. Litematica API changes: check the Modrinth version pinned above.

## Decisions and gotchas
- Wrong blocks are always fixed (creative breaks instantly, survival mines with the best tool, pickaxe for glass); containers never broken unless allowed. Clearing extra blocks is off by default. Never place liquids. Bedrock breaking off by default (sends START_DESTROY for the piston directly).
- Server is vanilla + Carpet, no anticheat: high speed defaults are fine. The helper copies only orientation properties; without it, skip blocks that can't be placed exactly.
- Lag safety (1.3.1/1.3.2): 2 ms search budget per tick with resumable searches; scan skips spots with unconfirmed client predictions; helper refuses clicks next to the aimed spot.
- Game-test gamerules were renamed for 1.21.11 (random ticks, copper ageing); keep them off in the test world.
- Client code never runs on a dedicated server; keep Litematica references in `src/client` only.

## Release
- Bump `mod_version`, `python ../cytra-releases/release.py build/libs/alloy-printer-<v>.jar "<changelog>"`. After 1.3.2 the user also put the jar into `~/Documents/server mods/{cmp,smp}`; that is a server change, ask first. Never release without asking.

## Gaps and improvement ideas
- Alloy UI 1.3.0 in use (1.4.0 exists); `gui/PrinterFrame.java` is its own frame helper and could be `AlloyScreen`.
- One giant game test class (`src/gametest/.../PrinterClientGameTest.java`) selected by env vars; split into classes so `-PgameTests=` style filtering (as in alloy-map) works.
- Unit tests absent for `PlacementSolver`/`StateRules` (pure logic); a plain JUnit run would be much faster than the 440-state game test.
- `libs/alloy-hub-0.6.0.jar` is old (Hub is 0.7.3), so `-PwithHub` tests an outdated Hub.
- PROGRESS 1.3.1 line says the manifest check was interrupted; the 1.3.2 line confirms live manifest, so nothing open.

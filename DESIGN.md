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

## Open ideas
- Settings screen in Alloy UI / Nocturne, plus an Alloy Hub plugin page built from the same config.
- Auto-craft missing items through Alloy Mass Craft (optional hook, like Restock).
- A server GameTest for the helper.

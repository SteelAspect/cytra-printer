# Cytra Printer

A fast Litematica printer for Fabric 1.21.11 that doesn't place blocks wrong.

## How it avoids mistakes

Before every click, the printer runs the block's own vanilla placement code against your world for every way
it could click (each face, each spot on the face, 16+ look directions, sneaking or not). It only clicks when that
simulation gives exactly the schematic's block and rotation. If no click can produce it, the block is skipped and
counted on the HUD instead of placed wrong.

On top of that:

- **Wrong blocks are fixed**: blocks that are wrong or face the wrong way are broken and placed again
  (instantly in creative, mined with the best tool in survival). Containers are never broken unless you allow it.
- **Waits for the server**: a spot the printer touched is left alone until the server answered; a spot that keeps
  failing is parked for 30 seconds instead of looping.
- **Safe redstone order**: observers, pistons, dispensers and droppers wait until the blocks around them are done.
  Observers always wait for the block they watch.
- **Click-fix**: repeater delays, comparator modes, note block pitch, daylight sensors, wooden doors, trapdoors and
  fence gates are clicked until they match (levers optional).
- **Sign text**: signs it places get the schematic's front text.
- **Never places liquids.** Gravity blocks wait for support. Potted plants: pot first, then the plant.
- Bottom layer first, then nearest; respects Litematica's layer range.

## Server helper (optional, same jar)

Put the same jar in the server's `mods` folder. The server then takes the exact rotation from the printer for every
block it places, so even blocks vanilla can't rotate with a click from where you stand print correctly. It only
ever sets rotation-type properties of the block the held item places normally; item counts, waterlogging and
anything that depends on neighbours stay vanilla, so it can't create anything a normal click couldn't.
`config/cytraprinter-server.json` has `"enabled": true` to turn it off server-wide.

Without the server part, the printer still works on any server (vanilla mode): it just skips the rare block it
can't place exactly from your position.

## Restocking

With Cytra Container installed (and its "printer pulls" setting on), missing blocks are pulled from shulker boxes
in your inventory or linked storage automatically.

## Settings

Open from Mod Menu / MaLiLib's config menu, or bind **Open Settings** in the Hotkeys tab. Bind **Printing** to a key
to toggle the printer. All hotkeys are unbound by default.

## Building

```
JAVA_HOME=<JDK 21> ./gradlew build
```

`./gradlew runClientGameTest` builds a test structure with every placement state of ~70 blocks, saves it as a
schematic, clears it and prints it back in creative (with and without the server helper) and survival, then checks
every block.

# Alloy Printer

> **Minecraft 26.3 port** (branch `mc26.3`). Differences from the 1.21.11 version:
> - No **Composter Levels** (26.3 reworked composting) and no **Extra Reach**: the printer stays within your normal reach.
> - Settings are MaLiLib's own tabbed screen (Mod Menu, MaLiLib's config menu, Open Settings hotkey); there is no
>   Alloy UI (Nocturne) screen and no Alloy Hub page yet, because Alloy UI and Alloy Hub have no 26.3 builds.
> - Needs Litematica 0.29.1+ and MaLiLib 0.30.2+ for 26.3, Fabric Loader 0.20.1+, Java 25.
> - Restocking works with the original **Take It Out** (rofumer, Modrinth `takeitout`) instead of Alloy Container: a
>   missing block is pulled out of a shulker box in your inventory (Take It Out must be on the server too) or from
>   Take It Out's linked containers. Optional; turn it off with *Restock With Take It Out*.

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

## Extras

- **Excluded blocks** (Blocks tab): ids or tags the printer never places or fixes. Bind **Exclude Looked-At Block**
  to add/remove the block under your crosshair. **Protected blocks** are never broken.
- **Strip logs**: no stripped logs in your inventory but plain ones and an axe? It places the plain log and strips it.
- **Composter levels**: fills composters to the schematic's level (best compost chance first, never items the build
  still needs); a full composter is emptied first.
- **Coral**: live coral stands in for dead coral (it dies by itself), and live coral waits until there is water so it
  doesn't die.
- **Bedrock breaking** (off by default, survival): the piston trick on listed blocks where the schematic wants
  something else. Needs pistons, redstone torches, slime blocks (only without a floor) and a pickaxe that mines a
  piston instantly (Efficiency V + Haste II). Everything it sets up is mined again afterwards.
- **Safe observers**: observers wait until a whole line of watched blocks is done, and don't go next to a
  wall/fence/wire that would change shape while something behind them would react; pistons wait for observers
  aimed at them.
- **Pause on lag** and **turn off on death**.
- **Skipped blocks**: spots the printer leaves alone stay outlined through walls, even after you walk away:
  red = wrong block it won't fix, orange = can't place it exactly from where you stood, yellow = gave up for now.
  **Next Skipped Block** points you to the nearest one; **Retry Skipped Blocks** makes the printer try them again.

## Restocking

With Alloy Container installed (and its "printer pulls" setting on), missing blocks are pulled from shulker boxes
in your inventory or linked storage automatically.

## Settings

Open from Mod Menu, MaLiLib's config menu, Alloy Hub, or bind **Open Settings** in the hotkeys. The settings screen has
Printer, Accuracy, Blocks and Extras tabs; block lists are typed as `stone, #minecraft:logs`. **Hotkeys** in its footer
opens the MaLiLib hotkey editor. Bind **Printing** (or **Toggle printing** in Controls) to a key to toggle the printer.
All hotkeys are unbound by default.

With Alloy Hub installed, Alloy Printer has a Hub page: printing on/off, status (server helper, progress, missing items),
the same settings, quick actions and the HUD position. Ops get a Server tab to turn the placement helper on or off.

## Building

```
JAVA_HOME=<JDK 21> ./gradlew build
```

`./gradlew runClientGameTest` builds a test structure with every placement state of ~70 blocks, saves it as a
schematic, clears it and prints it back in creative (with and without the server helper) and survival, then checks
every block.

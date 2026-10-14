package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.gui.GuiConfigs;
import dev.steelaspect.cytraprinter.client.printer.Printer;
import dev.steelaspect.cytraprinter.client.printer.SignText;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Everything that needs Litematica and MaLiLib; only loaded when both are installed. */
final class PrinterBootstrap {
    private PrinterBootstrap() {
    }

    static void init() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(CytraPrinter.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(CytraPrinter.MOD_ID, CytraPrinter.MOD_NAME, GuiConfigs::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.INSTANCE);
            RenderEventHandler.getInstance().registerGameOverlayRenderer(HudRenderer.INSTANCE);
        });
        ClientTickEvents.END_CLIENT_TICK.register(Printer::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Printer.reset());
        ClientHooks.signEditor = SignText::onEditor;
        ClientHooks.rotationLock = Printer::rotationLocked;
        ClientHooks.teleport = Printer::onTeleport;
    }
}

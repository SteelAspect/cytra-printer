package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.gui.SettingsRedirect;
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
    static final net.minecraft.client.KeyMapping TOGGLE = new net.minecraft.client.KeyMapping("key.cytraprinter.toggle",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(),
            net.minecraft.client.KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(CytraPrinter.MOD_ID, "main")));

    private PrinterBootstrap() {
    }

    static void init() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(CytraPrinter.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(CytraPrinter.MOD_ID, CytraPrinter.MOD_NAME, SettingsRedirect::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.INSTANCE);
            RenderEventHandler.getInstance().registerGameOverlayRenderer(HudRenderer.INSTANCE);
        });
        // A normal Minecraft key too, so the toggle shows in Controls and Alloy Hub's key list (unbound by default).
        net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(TOGGLE);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (TOGGLE.consumeClick()) {
                Configs.PRINTING.toggleBooleanValue();
                if (mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            Configs.PRINTING.getBooleanValue() ? "cytraprinter.msg.on" : "cytraprinter.msg.off"), true);
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(Printer::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Printer.reset());
        ClientHooks.signEditor = SignText::onEditor;
        ClientHooks.rotationLock = Printer::rotationLocked;
        ClientHooks.teleport = Printer::onTeleport;
        ClientHooks.useItem = Printer::onUseItem;
    }
}

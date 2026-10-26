package dev.steelaspect.cytraprinter.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;

/**
 * What MaLiLib's config menu opens for Alloy Printer: it hands straight over to {@link PrinterSettingsScreen}, which
 * returns to MaLiLib's screen on Esc. MaLiLib also builds these just to compare screen classes, so the constructor
 * does nothing.
 */
public final class SettingsRedirect extends GuiBase {
    @Override
    public void initGui() {
        super.initGui();
        Minecraft mc = Minecraft.getInstance();
        var back = getParent();
        // Swap on the next tick rather than from inside this screen's own init.
        mc.schedule(() -> {
            if (mc.gui.screen() == this) mc.gui.setScreen(new PrinterSettingsScreen(back));
        });
    }
}

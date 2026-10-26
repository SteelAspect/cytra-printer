package dev.steelaspect.cytraprinter.client.gui;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * MaLiLib's hotkey editor for the printer's hotkeys and toggles (combos, conditions). Every other setting is on
 * {@link PrinterSettingsScreen}, which opens this from its footer.
 */
public class GuiConfigs extends GuiConfigsBase {
    private GuiConfigs(@Nullable Screen parent) {
        super(10, 30, CytraPrinter.MOD_ID, parent, CytraPrinter.MOD_ID + ".gui.hotkeys_title");
    }

    public static GuiConfigs forHotkeys(@Nullable Screen parent) {
        return new GuiConfigs(parent);
    }

    @Override
    protected int getConfigWidth() {
        return 204;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(Configs.HOTKEY_TAB);
    }
}

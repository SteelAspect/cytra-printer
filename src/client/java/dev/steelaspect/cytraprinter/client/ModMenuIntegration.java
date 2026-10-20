package dev.steelaspect.cytraprinter.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.steelaspect.cytraprinter.client.gui.PrinterSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu's config button opens the printer settings (needs MaLiLib; without it there are none). */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> FabricLoader.getInstance().isModLoaded("malilib") ? new PrinterSettingsScreen(parent) : null;
    }
}

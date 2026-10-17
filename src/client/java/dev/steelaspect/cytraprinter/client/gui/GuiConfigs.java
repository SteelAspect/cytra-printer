package dev.steelaspect.cytraprinter.client.gui;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.config.Configs;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Settings screen: Printer / Accuracy / Blocks / Extras / HUD / Hotkeys tabs and the server helper status. */
public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.PRINTER;

    public GuiConfigs(@Nullable Screen parent) {
        super(10, 62, CytraPrinter.MOD_ID, parent, CytraPrinter.MOD_ID + ".gui.title");
    }

    public GuiConfigs() {
        this(null);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        for (Tab t : Tab.values()) {
            ButtonGeneric button = new ButtonGeneric(x, 26, -1, 20, StringUtils.translate(t.key));
            button.setEnabled(t != tab);
            this.addButton(button, new TabListener(t, this));
            x += button.getWidth() + 2;
        }
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        ctx.drawString(this.font, StringUtils.translate(CytraPrinter.MOD_ID + ".gui.helper." + ServerHelper.statusKey()), 12, 50, 0xFFC0C0C0);
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS || tab == Tab.BLOCKS || tab == Tab.EXTRAS ? 204 : 120;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case PRINTER -> Configs.PRINTER;
            case ACCURACY -> Configs.ACCURACY;
            case BLOCKS -> Configs.BLOCKS;
            case EXTRAS -> Configs.EXTRAS;
            case HUD -> Configs.HUD;
            case HOTKEYS -> Configs.HOTKEY_TAB;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        PRINTER, ACCURACY, BLOCKS, EXTRAS, HUD, HOTKEYS;

        final String key = CytraPrinter.MOD_ID + ".gui.tab." + name().toLowerCase(java.util.Locale.ROOT);
    }

    private record TabListener(Tab tab, GuiConfigs parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiConfigs.tab = this.tab;
            this.parent.reCreateListWidget();
            this.parent.getListWidget().resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}

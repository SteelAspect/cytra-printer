package dev.steelaspect.cytraprinter.client.gui;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Column;
import dev.steelaspect.alloyui.widget.StatusRow;
import dev.steelaspect.alloyui.widget.Tabs;
import dev.steelaspect.alloyui.widget.Widget;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.config.Configs;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Alloy Printer's settings in the Nocturne look: Printer, Accuracy, Blocks and Extras tabs built from
 * {@link PrinterOptions}. Hotkeys stay on the MaLiLib screen, one click away in the footer.
 */
public final class PrinterSettingsScreen extends AlloyScreen {
    private static PrinterOptions.Group group = PrinterOptions.Group.PRINTER;

    private final PrinterFrame frame;

    public PrinterSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable(CytraPrinter.MOD_ID + ".gui.title"), parent);
        this.frame = new PrinterFrame(this.title);
        this.frame.tabs(new Tabs(
                () -> Arrays.stream(PrinterOptions.Group.values()).map(PrinterOptions.Group::title).toList(),
                () -> group.ordinal(),
                i -> {
                    group = PrinterOptions.Group.values()[i];
                    rebuild();
                }));
        this.frame.button(new Button(Component.translatable(CytraPrinter.MOD_ID + ".gui.hotkeys"),
                () -> GuiBase.openGui(GuiConfigs.forHotkeys(this))));
        this.frame.button(new Button(Component.translatable("gui.done"), this::onClose).style(Button.Style.PRIMARY));
        rebuild();
        setRoot(this.frame);
    }

    /** Opens the screen on a tab: "printer", "accuracy", "blocks" or "extras". */
    public static PrinterSettingsScreen onTab(@Nullable Screen parent, String key) {
        for (PrinterOptions.Group g : PrinterOptions.Group.values()) if (g.key.equals(key)) group = g;
        return new PrinterSettingsScreen(parent);
    }

    private void rebuild() {
        Column content = this.frame.content();
        content.clear();
        if (group == PrinterOptions.Group.PRINTER) {
            content.add(new StatusRow(Component.translatable(CytraPrinter.MOD_ID + ".status.helper"),
                    () -> Component.translatable(CytraPrinter.MOD_ID + ".status.helper." + ServerHelper.statusKey()))
                    .dot(() -> switch (ServerHelper.statusKey()) {
                        case "on" -> "good";
                        case "missing" -> "info";
                        default -> "warn";
                    }));
        }
        for (IConfigBase option : group.options) {
            Widget row = PrinterOptions.rowFor(option);
            if (row != null) content.add(row);
        }
        this.frame.body().scrollToTop();
    }

    @Override
    public void removed() {
        super.removed();
        Configs.INSTANCE.save();
    }
}

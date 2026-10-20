package dev.steelaspect.cytraprinter.client.compat;

import dev.steelaspect.cytrahub.api.HubToast;
import dev.steelaspect.cytrahub.api.StatusLevel;
import dev.steelaspect.cytrahub.api.client.ClientHub;
import dev.steelaspect.cytrahub.api.client.SettingsBuilder;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.HudRenderer;
import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.gui.GuiConfigs;
import dev.steelaspect.cytraprinter.client.gui.PrinterOptions;
import dev.steelaspect.cytraprinter.client.gui.PrinterSettingsScreen;
import dev.steelaspect.cytraprinter.client.printer.Printer;
import dev.steelaspect.cytraprinter.client.printer.Stats;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import java.util.Map;

/** The client side of the Hub page: status, the same settings groups as {@link PrinterSettingsScreen}, actions, HUD. */
final class HubPage {
    private static final String P = HubPlugin.P;

    private HubPage() {
    }

    static void register(ClientHub hub) {
        hub.page(CytraPrinter.MOD_ID)
                .category("Building")
                .masterSwitch(Configs.PRINTING::getBooleanValue, v -> {
                    Configs.PRINTING.setBooleanValue(v);
                    PrinterOptions.changed();
                })
                .mainKey("key.cytraprinter.toggle")
                .status(s -> s
                        .row(Component.translatable(P + "printing"),
                                () -> Component.translatable(Configs.PRINTING.getBooleanValue() ? P + "on" : P + "off"),
                                () -> Configs.PRINTING.getBooleanValue() ? StatusLevel.GOOD : StatusLevel.NONE)
                        .row(Component.translatable(CytraPrinter.MOD_ID + ".status.helper"),
                                () -> Component.translatable(CytraPrinter.MOD_ID + ".status.helper." + ServerHelper.statusKey()),
                                () -> switch (ServerHelper.statusKey()) {
                                    case "on" -> StatusLevel.GOOD;
                                    case "missing" -> StatusLevel.INFO;
                                    default -> StatusLevel.WARN;
                                })
                        .row(Component.translatable(P + "progress"),
                                () -> Component.translatable(P + "progress.value", Stats.perSecond(), Stats.remaining, Stats.placedTotal))
                        .row(Component.translatable(P + "missing"), HubPage::missing,
                                () -> Stats.missing.isEmpty() ? StatusLevel.NONE : StatusLevel.WARN)
                        .row(Component.translatable(P + "problems"),
                                () -> Component.translatable(P + "problems.value", Stats.wrong, Stats.unsolvable, Stats.stuck),
                                () -> Stats.wrong + Stats.unsolvable + Stats.stuck > 0 ? StatusLevel.WARN : StatusLevel.NONE))
                .settings(s -> {
                    for (PrinterOptions.Group g : PrinterOptions.Group.values()) {
                        s.section(g.title());
                        for (IConfigBase option : g.options) add(s, option);
                    }
                    s.keybind("key.cytraprinter.toggle");
                })
                .actions(a -> {
                    a.action("settings", Component.translatable(P + "open_settings"),
                            () -> Minecraft.getInstance().setScreen(new PrinterSettingsScreen(Minecraft.getInstance().screen)));
                    a.action("hotkeys", Component.translatable(CytraPrinter.MOD_ID + ".gui.hotkeys"),
                            () -> GuiBase.openGui(GuiConfigs.forHotkeys(Minecraft.getInstance().screen)));
                    a.action("exclude", Component.translatable(CytraPrinter.MOD_ID + ".config.prettyName.excludeLookedAt"), () -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null) {
                            hub.toast(HubToast.ERROR, Component.translatable(P + "not_in_world"));
                            return;
                        }
                        mc.setScreen(null);
                        Printer.toggleExcludeLookedAt(mc);
                    });
                })
                .hudElement("status", Component.translatable(P + "hud"),
                        Configs.HUD_X::getIntegerValue, Configs.HUD_Y::getIntegerValue,
                        xy -> {
                            Configs.HUD_X.setIntegerValue(xy[0]);
                            Configs.HUD_Y.setIntegerValue(xy[1]);
                            PrinterOptions.changed();
                        },
                        () -> HudRenderer.lastWidth, () -> HudRenderer.lastHeight,
                        Configs.SHOW_HUD::getBooleanValue, v -> {
                            Configs.SHOW_HUD.setBooleanValue(v);
                            PrinterOptions.changed();
                        });
    }

    private static Component missing() {
        if (Stats.missing.isEmpty()) return Component.translatable(P + "none");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<Item, Integer> e : Stats.missing.entrySet()) {
            if (n++ == 3) {
                sb.append(", +").append(Stats.missing.size() - 3);
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey().getName().getString()).append(" x").append(e.getValue());
        }
        return Component.literal(sb.toString());
    }

    /** One Hub setting per MaLiLib option; same controls and ranges as {@link PrinterOptions#rowFor}. */
    private static void add(SettingsBuilder s, IConfigBase option) {
        String id = option.getName();
        Component label = PrinterOptions.label(option);
        Component hint = PrinterOptions.hint(option);
        if (option instanceof ConfigBoolean b) {
            var row = s.toggle(id, label, b::getBooleanValue, v -> {
                b.setBooleanValue(v);
                PrinterOptions.changed();
            }).defaultValue(b.getDefaultBooleanValue());
            if (hint != null) row.description(hint);
        } else if (option instanceof ConfigInteger i) {
            var row = s.integer(id, label, i.getMinIntegerValue(), i.getMaxIntegerValue(), i::getIntegerValue, v -> {
                i.setIntegerValue(v);
                PrinterOptions.changed();
            }).defaultValue(i.getDefaultIntegerValue());
            if (hint != null) row.description(hint);
        } else if (option instanceof ConfigDouble d) {
            var row = s.slider(id, label, d.getMinDoubleValue(), d.getMaxDoubleValue(), 0.05, d::getDoubleValue, v -> {
                d.setDoubleValue(Math.round(v * 100) / 100.0);
                PrinterOptions.changed();
            }).defaultValue(d.getDefaultDoubleValue());
            if (hint != null) row.description(hint);
        } else if (option instanceof ConfigString str) {
            var row = s.text(id, label, str::getStringValue, v -> {
                str.setValueFromString(v);
                PrinterOptions.changed();
            }).defaultValue(str.getDefaultStringValue());
            if (hint != null) row.description(hint);
        } else if (option instanceof ConfigStringList l) {
            var row = s.text(id, label, () -> PrinterOptions.joinList(l.getStrings()), v -> {
                l.setStrings(PrinterOptions.splitList(v));
                PrinterOptions.changed();
            }).defaultValue(PrinterOptions.joinList(l.getDefaultStrings()));
            if (hint != null) row.description(hint);
        }
    }
}

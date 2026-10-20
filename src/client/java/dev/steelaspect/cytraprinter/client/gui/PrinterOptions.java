package dev.steelaspect.cytraprinter.client.gui;

import dev.steelaspect.alloyui.widget.Rows;
import dev.steelaspect.alloyui.widget.SettingRow;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The one option list behind the Nocturne settings screen and the Alloy Hub page. Both read and write the MaLiLib
 * options in {@link Configs}, so they never disagree with each other or with the MaLiLib hotkey screen.
 */
public final class PrinterOptions {
    /** The settings groups, in screen tab order. HUD options sit on the Printer tab. */
    public enum Group {
        PRINTER("printer", concat(Configs.PRINTER, Configs.HUD)),
        ACCURACY("accuracy", Configs.ACCURACY),
        BLOCKS("blocks", Configs.BLOCKS),
        EXTRAS("extras", Configs.EXTRAS);

        public final String key;
        public final List<IConfigBase> options;

        Group(String key, List<? extends IConfigBase> options) {
            this.key = key;
            this.options = List.copyOf(options);
        }

        public Component title() {
            return Component.translatable(CytraPrinter.MOD_ID + ".gui.tab." + key);
        }
    }

    private PrinterOptions() {
    }

    private static List<IConfigBase> concat(List<? extends IConfigBase> a, List<? extends IConfigBase> b) {
        List<IConfigBase> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    public static Component label(IConfigBase option) {
        return Component.translatable(CytraPrinter.MOD_ID + ".config.prettyName." + option.getName());
    }

    public static @Nullable Component hint(IConfigBase option) {
        String key = CytraPrinter.MOD_ID + ".config.comment." + option.getName();
        return Language.getInstance().has(key) ? Component.translatable(key) : null;
    }

    /** A string list as one line of text: "a, b, #tag". */
    public static String joinList(List<String> values) {
        return String.join(", ", values);
    }

    public static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Applies a change and saves, so a closed game or a crash never loses it. */
    public static void changed() {
        Configs.INSTANCE.save();
    }

    /** A setting row for one option, or null for a type this screen doesn't show. */
    public static @Nullable SettingRow rowFor(IConfigBase option) {
        Component label = label(option);
        Component hint = hint(option);
        if (option instanceof ConfigBoolean b) {
            return Rows.toggle(label, hint, b::getBooleanValue, v -> {
                b.setBooleanValue(v);
                changed();
            }, b.getDefaultBooleanValue());
        }
        if (option instanceof ConfigInteger i) {
            return Rows.number(label, hint, i.getMinIntegerValue(), i.getMaxIntegerValue(), 1, true, i::getIntegerValue, v -> {
                i.setIntegerValue((int) Math.round(v));
                changed();
            }, i.getDefaultIntegerValue());
        }
        if (option instanceof ConfigDouble d) {
            return Rows.slider(label, hint, d.getMinDoubleValue(), d.getMaxDoubleValue(), 0.05, d::getDoubleValue, v -> {
                d.setDoubleValue(Math.round(v * 100) / 100.0);
                changed();
            }, d.getDefaultDoubleValue());
        }
        if (option instanceof ConfigString s) {
            return Rows.text(label, hint, s::getStringValue, v -> {
                s.setValueFromString(v);
                changed();
            }, s.getDefaultStringValue());
        }
        if (option instanceof ConfigStringList l) {
            return Rows.text(label, hint, () -> joinList(l.getStrings()), v -> {
                l.setStrings(splitList(v));
                changed();
            }, joinList(l.getDefaultStrings()));
        }
        return null;
    }
}

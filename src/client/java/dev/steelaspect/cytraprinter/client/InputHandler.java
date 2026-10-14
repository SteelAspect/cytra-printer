package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.gui.GuiConfigs;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;

import java.util.ArrayList;
import java.util.List;

public final class InputHandler implements IKeybindProvider, IHotkeyCallback {
    public static final InputHandler INSTANCE = new InputHandler();

    private InputHandler() {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) hotkey.getKeybind().setCallback(this);
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) toggle.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(toggle));
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) manager.addKeybindToMap(hotkey.getKeybind());
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) manager.addKeybindToMap(toggle.getKeybind());
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<IHotkey> all = new ArrayList<>(Configs.HOTKEYS);
        all.addAll(Configs.TOGGLES);
        manager.addHotkeysForCategory(CytraPrinter.MOD_NAME, CytraPrinter.MOD_ID + ".hotkeys.category", all);
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        if (key == Configs.OPEN_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs());
            return true;
        }
        return false;
    }
}

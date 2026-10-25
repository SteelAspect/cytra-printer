package dev.steelaspect.cytraprinter.client.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.steelaspect.cytraprinter.CytraPrinter;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** All client options, saved in config/cytraprinter.json. */
public final class Configs implements IConfigHandler {
    public static final Configs INSTANCE = new Configs();
    private static final String PREFIX = CytraPrinter.MOD_ID + ".config";

    // --- Printer ---
    public static final ConfigBooleanHotkeyed PRINTING = new ConfigBooleanHotkeyed("printing", false, "").apply(PREFIX);
    public static final ConfigInteger PLACEMENTS_PER_TICK = new ConfigInteger("placementsPerTick", 8, 1, 64).apply(PREFIX);
    public static final ConfigInteger TICK_DELAY = new ConfigInteger("tickDelay", 0, 0, 20).apply(PREFIX);
    public static final ConfigBoolean USE_SERVER_HELPER = new ConfigBoolean("useServerHelper", true).apply(PREFIX);
    public static final ConfigString HOTBAR_SLOTS = new ConfigString("hotbarSlots", "7,8,9").apply(PREFIX);
    public static final ConfigBoolean RESTOCK = new ConfigBoolean("restockFromContainerMod", true).apply(PREFIX);
    public static final ConfigBoolean PAUSE_ON_LAG = new ConfigBoolean("pauseOnLag", true).apply(PREFIX);
    public static final ConfigBoolean DISABLE_ON_DEATH = new ConfigBoolean("disableOnDeath", true).apply(PREFIX);

    public static final ImmutableList<IConfigBase> PRINTER = ImmutableList.of(
            PRINTING,
            PLACEMENTS_PER_TICK,
            TICK_DELAY,
            USE_SERVER_HELPER,
            HOTBAR_SLOTS,
            RESTOCK,
            PAUSE_ON_LAG,
            DISABLE_ON_DEATH);

    // --- Accuracy ---
    public static final ConfigBoolean FIX_WRONG_BLOCKS = new ConfigBoolean("fixWrongBlocks", true).apply(PREFIX);
    public static final ConfigBoolean CLEAR_EXTRA_BLOCKS = new ConfigBoolean("clearExtraBlocks", false).apply(PREFIX);
    public static final ConfigBoolean BREAK_CONTAINERS = new ConfigBoolean("breakContainers", false).apply(PREFIX);
    public static final ConfigBoolean CLICK_FIX_STATES = new ConfigBoolean("clickFixStates", true).apply(PREFIX);
    public static final ConfigBoolean CLICK_FIX_LEVERS = new ConfigBoolean("clickFixLevers", false).apply(PREFIX);
    public static final ConfigBoolean SIGN_TEXT = new ConfigBoolean("signText", true).apply(PREFIX);
    public static final ConfigBoolean SAFE_REDSTONE_ORDER = new ConfigBoolean("safeRedstoneOrder", true).apply(PREFIX);
    public static final ConfigBoolean SAFE_OBSERVERS = new ConfigBoolean("safeObservers", true).apply(PREFIX);
    public static final ConfigInteger MAX_RETRIES = new ConfigInteger("maxRetries", 3, 1, 20).apply(PREFIX);

    public static final ImmutableList<IConfigBase> ACCURACY = ImmutableList.of(
            FIX_WRONG_BLOCKS,
            CLEAR_EXTRA_BLOCKS,
            BREAK_CONTAINERS,
            CLICK_FIX_STATES,
            CLICK_FIX_LEVERS,
            SIGN_TEXT,
            SAFE_REDSTONE_ORDER,
            SAFE_OBSERVERS,
            MAX_RETRIES);

    // --- Blocks (filters) ---
    public static final ConfigStringList EXCLUDED_BLOCKS = new ConfigStringList("excludedBlocks", ImmutableList.of()).apply(PREFIX);
    public static final ConfigStringList PROTECTED_BLOCKS = new ConfigStringList("protectedBlocks", ImmutableList.of()).apply(PREFIX);

    public static final ImmutableList<IConfigBase> BLOCKS = ImmutableList.of(EXCLUDED_BLOCKS, PROTECTED_BLOCKS);

    // --- Extras ---
    public static final ConfigBoolean STRIP_LOGS = new ConfigBoolean("stripLogs", true).apply(PREFIX);
    public static final ConfigBoolean LIVE_CORAL_FOR_DEAD = new ConfigBoolean("liveCoralForDead", true).apply(PREFIX);
    public static final ConfigBoolean CORAL_WAIT_FOR_WATER = new ConfigBoolean("coralWaitForWater", true).apply(PREFIX);
    public static final ConfigBoolean BREAK_BEDROCK = new ConfigBoolean("breakBedrock", false).apply(PREFIX);
    public static final ConfigStringList BEDROCK_BLOCKS = new ConfigStringList("bedrockBlocks", ImmutableList.of("bedrock")).apply(PREFIX);

    public static final ImmutableList<IConfigBase> EXTRAS = ImmutableList.of(
            STRIP_LOGS,
            LIVE_CORAL_FOR_DEAD,
            CORAL_WAIT_FOR_WATER,
            BREAK_BEDROCK,
            BEDROCK_BLOCKS);

    // --- HUD ---
    public static final ConfigBooleanHotkeyed SHOW_HUD = new ConfigBooleanHotkeyed("showHud", true, "").apply(PREFIX);
    public static final ConfigInteger HUD_X = new ConfigInteger("hudX", 4, 0, 4000).apply(PREFIX);
    public static final ConfigInteger HUD_Y = new ConfigInteger("hudY", 4, 0, 4000).apply(PREFIX);
    public static final ConfigBoolean SHOW_SKIPPED = new ConfigBoolean("showSkipped", true).apply(PREFIX);
    public static final ConfigInteger SKIPPED_RANGE = new ConfigInteger("skippedRange", 64, 8, 256).apply(PREFIX);

    public static final ImmutableList<IConfigBase> HUD = ImmutableList.of(SHOW_HUD, HUD_X, HUD_Y, SHOW_SKIPPED, SKIPPED_RANGE);

    public static final ImmutableList<ConfigBooleanHotkeyed> TOGGLES = ImmutableList.of(PRINTING, SHOW_HUD);

    // --- Hotkeys (unbound by default) ---
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey("openGui", "").apply(PREFIX);
    public static final ConfigHotkey EXCLUDE_LOOKED_AT = new ConfigHotkey("excludeLookedAt", "").apply(PREFIX);
    public static final ConfigHotkey NEXT_SKIPPED = new ConfigHotkey("nextSkipped", "").apply(PREFIX);
    public static final ConfigHotkey RETRY_SKIPPED = new ConfigHotkey("retrySkipped", "").apply(PREFIX);
    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(OPEN_GUI, EXCLUDE_LOOKED_AT, NEXT_SKIPPED, RETRY_SKIPPED);
    public static final ImmutableList<IConfigBase> HOTKEY_TAB = ImmutableList.<IConfigBase>builder()
            .addAll(HOTKEYS).addAll(TOGGLES).build();

    private Configs() {
    }

    /** Hotbar slots (0-8) the printer may put items into, from "7,8,9" style text (1-9). Never empty. */
    public static int[] hotbarSlots() {
        List<Integer> out = new ArrayList<>();
        for (String part : HOTBAR_SLOTS.getStringValue().split("[,\\s]+")) {
            try {
                int n = Integer.parseInt(part.trim());
                if (n >= 1 && n <= 9 && !out.contains(n - 1)) out.add(n - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(8);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(CytraPrinter.MOD_ID + ".json");
    }

    @Override
    public void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        JsonElement element = JsonUtils.parseJsonFile(file);
        if (element == null || !element.isJsonObject()) return;
        JsonObject root = element.getAsJsonObject();
        ConfigUtils.readConfigBase(root, "Printer", PRINTER);
        ConfigUtils.readConfigBase(root, "Accuracy", ACCURACY);
        ConfigUtils.readConfigBase(root, "Blocks", BLOCKS);
        ConfigUtils.readConfigBase(root, "Extras", EXTRAS);
        ConfigUtils.readConfigBase(root, "Hud", HUD);
        ConfigUtils.readConfigBase(root, "Hotkeys", HOTKEYS);
        ConfigUtils.readConfigBase(root, "Toggles", TOGGLES);
    }

    @Override
    public void save() {
        try {
            Files.createDirectories(FabricLoader.getInstance().getConfigDir());
        } catch (IOException e) {
            CytraPrinter.LOGGER.error("Could not create the config directory", e);
            return;
        }
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Printer", PRINTER);
        ConfigUtils.writeConfigBase(root, "Accuracy", ACCURACY);
        ConfigUtils.writeConfigBase(root, "Blocks", BLOCKS);
        ConfigUtils.writeConfigBase(root, "Extras", EXTRAS);
        ConfigUtils.writeConfigBase(root, "Hud", HUD);
        ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
        ConfigUtils.writeConfigBase(root, "Toggles", TOGGLES);
        JsonUtils.writeJsonToFile(root, file());
    }

    @Override
    public void onConfigsChanged() {
        save();
        load();
    }
}

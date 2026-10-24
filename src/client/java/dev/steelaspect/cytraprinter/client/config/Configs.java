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
import fi.dy.masa.malilib.util.JsonUtils;
import fi.dy.masa.malilib.util.StringUtils;
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
    public static final ConfigBooleanHotkeyed PRINTING = new ConfigBooleanHotkeyed("printing", false, "", comment("printing"), prettyName("printing"));
    public static final ConfigInteger PLACEMENTS_PER_TICK = integer("placementsPerTick", 8, 1, 64);
    public static final ConfigInteger TICK_DELAY = integer("tickDelay", 0, 0, 20);
    public static final ConfigDouble EXTRA_REACH = decimal("extraReach", 0.8, 0.0, 0.95);
    public static final ConfigBoolean USE_SERVER_HELPER = new ConfigBoolean("useServerHelper", true, comment("useServerHelper"), prettyName("useServerHelper"));
    public static final ConfigString HOTBAR_SLOTS = string("hotbarSlots", "7,8,9");
    public static final ConfigBoolean RESTOCK = new ConfigBoolean("restockFromContainerMod", true, comment("restockFromContainerMod"), prettyName("restockFromContainerMod"));
    public static final ConfigBoolean PAUSE_ON_LAG = new ConfigBoolean("pauseOnLag", true, comment("pauseOnLag"), prettyName("pauseOnLag"));
    public static final ConfigBoolean DISABLE_ON_DEATH = new ConfigBoolean("disableOnDeath", true, comment("disableOnDeath"), prettyName("disableOnDeath"));

    public static final ImmutableList<IConfigBase> PRINTER = ImmutableList.of(
            PRINTING,
            PLACEMENTS_PER_TICK,
            TICK_DELAY,
            EXTRA_REACH,
            USE_SERVER_HELPER,
            HOTBAR_SLOTS,
            RESTOCK,
            PAUSE_ON_LAG,
            DISABLE_ON_DEATH);

    // --- Accuracy ---
    public static final ConfigBoolean FIX_WRONG_BLOCKS = new ConfigBoolean("fixWrongBlocks", true, comment("fixWrongBlocks"), prettyName("fixWrongBlocks"));
    public static final ConfigBoolean CLEAR_EXTRA_BLOCKS = new ConfigBoolean("clearExtraBlocks", false, comment("clearExtraBlocks"), prettyName("clearExtraBlocks"));
    public static final ConfigBoolean BREAK_CONTAINERS = new ConfigBoolean("breakContainers", false, comment("breakContainers"), prettyName("breakContainers"));
    public static final ConfigBoolean CLICK_FIX_STATES = new ConfigBoolean("clickFixStates", true, comment("clickFixStates"), prettyName("clickFixStates"));
    public static final ConfigBoolean CLICK_FIX_LEVERS = new ConfigBoolean("clickFixLevers", false, comment("clickFixLevers"), prettyName("clickFixLevers"));
    public static final ConfigBoolean SIGN_TEXT = new ConfigBoolean("signText", true, comment("signText"), prettyName("signText"));
    public static final ConfigBoolean SAFE_REDSTONE_ORDER = new ConfigBoolean("safeRedstoneOrder", true, comment("safeRedstoneOrder"), prettyName("safeRedstoneOrder"));
    public static final ConfigBoolean SAFE_OBSERVERS = new ConfigBoolean("safeObservers", true, comment("safeObservers"), prettyName("safeObservers"));
    public static final ConfigInteger MAX_RETRIES = integer("maxRetries", 3, 1, 20);

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
    public static final ConfigStringList EXCLUDED_BLOCKS = stringList("excludedBlocks", ImmutableList.of());
    public static final ConfigStringList PROTECTED_BLOCKS = stringList("protectedBlocks", ImmutableList.of());

    public static final ImmutableList<IConfigBase> BLOCKS = ImmutableList.of(EXCLUDED_BLOCKS, PROTECTED_BLOCKS);

    // --- Extras ---
    public static final ConfigBoolean STRIP_LOGS = new ConfigBoolean("stripLogs", true, comment("stripLogs"), prettyName("stripLogs"));
    public static final ConfigBoolean COMPOSTER_LEVELS = new ConfigBoolean("composterLevels", true, comment("composterLevels"), prettyName("composterLevels"));
    public static final ConfigStringList COMPOSTER_ITEMS = stringList("composterItems", ImmutableList.of());
    public static final ConfigBoolean LIVE_CORAL_FOR_DEAD = new ConfigBoolean("liveCoralForDead", true, comment("liveCoralForDead"), prettyName("liveCoralForDead"));
    public static final ConfigBoolean CORAL_WAIT_FOR_WATER = new ConfigBoolean("coralWaitForWater", true, comment("coralWaitForWater"), prettyName("coralWaitForWater"));
    public static final ConfigBoolean BREAK_BEDROCK = new ConfigBoolean("breakBedrock", false, comment("breakBedrock"), prettyName("breakBedrock"));
    public static final ConfigStringList BEDROCK_BLOCKS = stringList("bedrockBlocks", ImmutableList.of("bedrock"));

    public static final ImmutableList<IConfigBase> EXTRAS = ImmutableList.of(
            STRIP_LOGS,
            COMPOSTER_LEVELS,
            COMPOSTER_ITEMS,
            LIVE_CORAL_FOR_DEAD,
            CORAL_WAIT_FOR_WATER,
            BREAK_BEDROCK,
            BEDROCK_BLOCKS);

    // --- HUD ---
    public static final ConfigBooleanHotkeyed SHOW_HUD = new ConfigBooleanHotkeyed("showHud", true, "", comment("showHud"), prettyName("showHud"));
    public static final ConfigInteger HUD_X = integer("hudX", 4, 0, 4000);
    public static final ConfigInteger HUD_Y = integer("hudY", 4, 0, 4000);
    public static final ConfigBoolean SHOW_SKIPPED = new ConfigBoolean("showSkipped", true, comment("showSkipped"), prettyName("showSkipped"));
    public static final ConfigInteger SKIPPED_RANGE = integer("skippedRange", 64, 8, 256);

    public static final ImmutableList<IConfigBase> HUD = ImmutableList.of(SHOW_HUD, HUD_X, HUD_Y, SHOW_SKIPPED, SKIPPED_RANGE);

    public static final ImmutableList<ConfigBooleanHotkeyed> TOGGLES = ImmutableList.of(PRINTING, SHOW_HUD);

    // --- Hotkeys (unbound by default) ---
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey("openGui", "", comment("openGui"), prettyName("openGui"));
    public static final ConfigHotkey EXCLUDE_LOOKED_AT = new ConfigHotkey("excludeLookedAt", "", comment("excludeLookedAt"), prettyName("excludeLookedAt"));
    public static final ConfigHotkey NEXT_SKIPPED = new ConfigHotkey("nextSkipped", "", comment("nextSkipped"), prettyName("nextSkipped"));
    public static final ConfigHotkey RETRY_SKIPPED = new ConfigHotkey("retrySkipped", "", comment("retrySkipped"), prettyName("retrySkipped"));
    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(OPEN_GUI, EXCLUDE_LOOKED_AT, NEXT_SKIPPED, RETRY_SKIPPED);
    public static final ImmutableList<IConfigBase> HOTKEY_TAB = ImmutableList.<IConfigBase>builder()
            .addAll(HOTKEYS).addAll(TOGGLES).build();

    private Configs() {
    }

    // MaLiLib 0.18 has no apply(prefix): translation keys are passed in, and the types without a pretty-name
    // constructor translate it themselves.
    private static String comment(String name) {
        return PREFIX + ".comment." + name;
    }

    private static String prettyName(String name) {
        return PREFIX + ".prettyName." + name;
    }

    private static ConfigInteger integer(String name, int def, int min, int max) {
        return new ConfigInteger(name, def, min, max, comment(name)) {
            @Override
            public String getPrettyName() {
                return StringUtils.translate(prettyName(name));
            }
        };
    }

    private static ConfigDouble decimal(String name, double def, double min, double max) {
        return new ConfigDouble(name, def, min, max, comment(name)) {
            @Override
            public String getPrettyName() {
                return StringUtils.translate(prettyName(name));
            }
        };
    }

    private static ConfigString string(String name, String def) {
        return new ConfigString(name, def, comment(name)) {
            @Override
            public String getPrettyName() {
                return StringUtils.translate(prettyName(name));
            }
        };
    }

    private static ConfigStringList stringList(String name, ImmutableList<String> def) {
        return new ConfigStringList(name, def, comment(name)) {
            @Override
            public String getPrettyName() {
                return StringUtils.translate(prettyName(name));
            }
        };
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
        JsonElement element = JsonUtils.parseJsonFile(file.toFile());
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
        JsonUtils.writeJsonToFile(root, file().toFile());
    }

    @Override
    public void onConfigsChanged() {
        save();
        load();
    }
}

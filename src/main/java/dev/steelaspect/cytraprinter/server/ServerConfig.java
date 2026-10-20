package dev.steelaspect.cytraprinter.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.steelaspect.cytraprinter.CytraPrinter;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/cytraprinter-server.json: {"enabled": true}. Off = the helper ignores intents and tells clients so. */
public final class ServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean enabled = true;

    private ServerConfig() {
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Turns the helper on or off and saves it (Alloy Hub's Server tab). Online players are told by the caller. */
    public static void setEnabled(boolean value) {
        enabled = value;
        JsonObject root = new JsonObject();
        root.addProperty("enabled", value);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root));
        } catch (IOException e) {
            CytraPrinter.LOGGER.warn("Could not save {}", file(), e);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(CytraPrinter.MOD_ID + "-server.json");
    }

    public static void load() {
        Path file = file();
        try {
            if (Files.isRegularFile(file)) {
                JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
                if (root != null && root.has("enabled")) enabled = root.get("enabled").getAsBoolean();
            } else {
                JsonObject root = new JsonObject();
                root.addProperty("enabled", true);
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(root));
            }
        } catch (IOException | RuntimeException e) {
            CytraPrinter.LOGGER.warn("Could not read {}, using defaults", file, e);
        }
    }
}

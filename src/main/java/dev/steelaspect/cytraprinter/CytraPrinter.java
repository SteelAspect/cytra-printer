package dev.steelaspect.cytraprinter;

import dev.steelaspect.cytraprinter.net.HelloPayload;
import dev.steelaspect.cytraprinter.net.IntentPayload;
import dev.steelaspect.cytraprinter.server.IntentStore;
import dev.steelaspect.cytraprinter.server.ServerConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. On a server (dedicated or the singleplayer one) this is the placement helper: a client running
 * the printer tells it which exact block state it wants at a position, and the next vanilla placement there gets that
 * state (see {@link dev.steelaspect.cytraprinter.server.PlacementAssist}). Without this on the server the printer
 * still works; it then only places blocks whose state it can reproduce with vanilla clicks.
 */
public class CytraPrinter implements ModInitializer {
    public static final String MOD_ID = "cytraprinter";
    public static final String MOD_NAME = "Alloy Printer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(IntentPayload.TYPE, IntentPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelloPayload.TYPE, HelloPayload.CODEC);

        ServerConfig.load();
        ServerPlayNetworking.registerGlobalReceiver(IntentPayload.TYPE, (payload, context) -> {
            if (ServerConfig.enabled()) IntentStore.add(context.player(), payload);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> hello(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> IntentStore.forget(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> IntentStore.tick());
    }

    /** Tells one player's printer whether the helper is on (on join, and again when an op switches it). */
    public static void hello(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, HelloPayload.TYPE)) {
            ServerPlayNetworking.send(player, new HelloPayload(HelloPayload.PROTOCOL, ServerConfig.enabled()));
        }
    }

    /** Switches the helper and tells everyone online. */
    public static void setHelperEnabled(MinecraftServer server, boolean enabled) {
        ServerConfig.setEnabled(enabled);
        if (!enabled) IntentStore.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) hello(player);
    }
}

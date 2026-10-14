package dev.steelaspect.cytraprinter;

import dev.steelaspect.cytraprinter.net.HelloPayload;
import dev.steelaspect.cytraprinter.net.IntentPayload;
import dev.steelaspect.cytraprinter.server.IntentStore;
import dev.steelaspect.cytraprinter.server.ServerConfig;
import net.fabricmc.api.ModInitializer;
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
    public static final String MOD_NAME = "Cytra Printer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(IntentPayload.TYPE, IntentPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelloPayload.TYPE, HelloPayload.CODEC);

        ServerConfig.load();
        ServerPlayNetworking.registerGlobalReceiver(IntentPayload.TYPE, (payload, context) -> {
            if (ServerConfig.enabled()) IntentStore.add(context.player(), payload);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, HelloPayload.TYPE)) {
                sender.sendPacket(new HelloPayload(HelloPayload.PROTOCOL, ServerConfig.enabled()));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> IntentStore.forget(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> IntentStore.tick());
    }
}

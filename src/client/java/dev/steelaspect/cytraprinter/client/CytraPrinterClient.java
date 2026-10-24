package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.net.HelloPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

public class CytraPrinterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, player, sender) -> ServerHelper.onHello(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ServerHelper.reset());
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("litematica") && loader.isModLoaded("malilib")) {
            PrinterBootstrap.init();
        } else {
            CytraPrinter.LOGGER.warn("Litematica or MaLiLib is missing; only the server helper part of Alloy Printer is active");
        }
    }
}

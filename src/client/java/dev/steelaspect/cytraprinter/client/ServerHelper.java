package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.net.HelloPayload;
import dev.steelaspect.cytraprinter.net.IntentPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Whether the server runs Cytra Printer's placement helper (it says hello on join) and has it on. */
public final class ServerHelper {
    private static Boolean enabled; // null = no hello yet / not installed

    private ServerHelper() {
    }

    public static void onHello(HelloPayload hello) {
        enabled = hello.protocol() == HelloPayload.PROTOCOL && hello.enabled();
    }

    public static void reset() {
        enabled = null;
    }

    /** True when the printer should send intents: installed and on at the server, allowed in our settings. */
    public static boolean active() {
        return Boolean.TRUE.equals(enabled) && Configs.USE_SERVER_HELPER.getBooleanValue() && ClientPlayNetworking.canSend(IntentPayload.TYPE);
    }

    public static String statusKey() {
        if (enabled == null) return "missing";
        if (!enabled) return "off_server";
        return Configs.USE_SERVER_HELPER.getBooleanValue() ? "on" : "off_client";
    }
}

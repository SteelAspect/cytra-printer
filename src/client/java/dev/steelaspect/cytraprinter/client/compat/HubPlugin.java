package dev.steelaspect.cytraprinter.client.compat;

import dev.steelaspect.cytrahub.api.CytraHubPlugin;
import dev.steelaspect.cytrahub.api.StatusLevel;
import dev.steelaspect.cytrahub.api.client.ClientHub;
import dev.steelaspect.cytrahub.api.server.ServerHub;
import dev.steelaspect.cytrahub.api.server.StatusLine;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.server.ServerConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Alloy Hub page for Alloy Printer. Only loaded with the Hub installed. The Hub also creates this on a dedicated
 * server, so the class itself touches no client classes: the client page lives in {@link HubPage}.
 */
public final class HubPlugin implements CytraHubPlugin {
    static final String P = CytraPrinter.MOD_ID + ".hub.";

    @Override
    public void registerClient(ClientHub hub) {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("litematica") && loader.isModLoaded("malilib")) {
            HubPage.register(hub);
        } else {
            hub.page(CytraPrinter.MOD_ID)
                    .category("Building")
                    .status(s -> s.text(() -> Component.translatable(P + "needs_litematica")));
        }
    }

    @Override
    public void registerServer(ServerHub hub) {
        hub.config(CytraPrinter.MOD_ID)
                .toggle("helper", Component.translatable(P + "server.helper"), ServerConfig::enabled,
                        v -> CytraPrinter.setHelperEnabled(hub.server(), v))
                .description(Component.translatable(P + "server.helper.hint"))
                .defaultValue(true);
        hub.status(CytraPrinter.MOD_ID, (viewer, op) -> List.of(new StatusLine(
                Component.translatable(P + "server.helper"),
                Component.translatable(ServerConfig.enabled() ? P + "server.on" : P + "server.off"),
                ServerConfig.enabled() ? StatusLevel.GOOD : StatusLevel.WARN)));
    }
}

package dev.steelaspect.cytraprinter.client.mixin;

import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server moved us (teleport, pearl, lag-back): it now has a new rotation, which the printer must start from. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTeleportMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void cytraprinter$teleported(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ClientHooks.onTeleport();
    }
}

package dev.steelaspect.cytraprinter.client.mixin;

import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server sends the time every 20 of its ticks: when that stops for a while, the server is lagging. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTimeMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void cytraprinter$serverTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ClientHooks.lastServerTimeMillis = System.currentTimeMillis();
    }
}

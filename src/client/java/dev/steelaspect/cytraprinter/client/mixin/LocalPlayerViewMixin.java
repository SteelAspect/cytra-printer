package dev.steelaspect.cytraprinter.client.mixin;

import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client answers "which way does the player look" with the body yaw, the server with the head yaw. While the
 * printer simulates or predicts a placement it uses the server's head yaw here, so both sides agree.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerViewMixin {
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void cytraprinter$serverHeadYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        float head = ClientHooks.simulatedHeadYaw;
        if (!Float.isNaN(head)) cir.setReturnValue(head);
    }
}

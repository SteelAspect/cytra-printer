package dev.steelaspect.cytraprinter.client.mixin;

import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A sign the printer just placed gets its schematic text sent instead of opening the edit screen. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSignMixin {
    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void cytraprinter$signText(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        if (ClientHooks.onSignEditor(sign, front)) ci.cancel();
    }
}

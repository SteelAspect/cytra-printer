package dev.steelaspect.cytraprinter.client.mixin;

import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pearls, bows, tridents etc. aim with the server-side rotation: give the server the real one first. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeUseMixin {
    @Inject(method = "useItem", at = @At("HEAD"))
    private void cytraprinter$realRotation(Player player, InteractionHand hand, CallbackInfoReturnable<?> cir) {
        ClientHooks.onUseItem();
    }
}

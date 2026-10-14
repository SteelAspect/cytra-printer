package dev.steelaspect.cytraprinter.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla waits 5 ticks between mined blocks on the client only; the printer skips that wait. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    void cytraprinter$setDestroyDelay(int delay);
}

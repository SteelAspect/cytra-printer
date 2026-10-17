package dev.steelaspect.cytraprinter.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla waits 5 ticks between mined blocks on the client only; the printer skips that wait. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    void cytraprinter$setDestroyDelay(int delay);

    /** Tells the server about a hotbar change now; vanilla only does it on the next use or tick. */
    @Invoker("ensureHasSentCarriedItem")
    void cytraprinter$syncCarriedItem();
}

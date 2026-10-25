package dev.steelaspect.cytraprinter.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.class)
public interface ClientLevelPredictionAccessor {
    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler cytraprinter$predictions();
}

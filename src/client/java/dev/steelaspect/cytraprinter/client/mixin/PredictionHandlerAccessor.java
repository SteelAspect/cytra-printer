package dev.steelaspect.cytraprinter.client.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Block changes the client predicted (our own clicks and breaks) that the server hasn't confirmed yet. */
@Mixin(BlockStatePredictionHandler.class)
public interface PredictionHandlerAccessor {
    @Accessor("serverVerifiedStates")
    Long2ObjectOpenHashMap<?> cytraprinter$pending();
}

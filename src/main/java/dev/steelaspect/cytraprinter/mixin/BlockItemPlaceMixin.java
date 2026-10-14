package dev.steelaspect.cytraprinter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.steelaspect.cytraprinter.server.PlacementAssist;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Server side: a placement by a player with a printer intent for that spot gets the intended orientation. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {
    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;getPlacementState(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState cytraprinter$intendedState(BlockItem item, BlockPlaceContext ctx, Operation<BlockState> original) {
        BlockState vanilla = original.call(item, ctx);
        if (ctx.getPlayer() instanceof ServerPlayer player && !ctx.getLevel().isClientSide()) {
            return PlacementAssist.apply(player, item, ctx, vanilla);
        }
        return vanilla;
    }
}

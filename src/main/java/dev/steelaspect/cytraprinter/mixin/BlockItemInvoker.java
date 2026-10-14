package dev.steelaspect.cytraprinter.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BlockItem's protected placement steps, for the server helper and the client's placement simulation. */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState cytraprinter$getPlacementState(BlockPlaceContext ctx);

    @Invoker("canPlace")
    boolean cytraprinter$canPlace(BlockPlaceContext ctx, BlockState state);

    @Invoker("updatePlacementContext")
    BlockPlaceContext cytraprinter$updatePlacementContext(BlockPlaceContext ctx);
}

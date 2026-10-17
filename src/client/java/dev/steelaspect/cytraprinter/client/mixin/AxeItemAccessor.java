package dev.steelaspect.cytraprinter.client.mixin;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Which block an axe strips into which, for printing stripped logs from normal ones. */
@Mixin(AxeItem.class)
public interface AxeItemAccessor {
    @Accessor("STRIPPABLES")
    static Map<Block, Block> cytraprinter$strippables() {
        throw new AssertionError();
    }
}

package dev.steelaspect.cytraprinter.server;

import dev.steelaspect.cytraprinter.mixin.BlockItemInvoker;
import dev.steelaspect.cytraprinter.state.StateRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;

import java.util.Set;

/**
 * Turns the vanilla placement result into the state the printer asked for. Only orientation-type properties and
 * the click-to-change settings are taken from the request; counts, waterlogging, halves and everything driven by
 * neighbours stay as vanilla decided, and the result still has to survive and fit at that position.
 */
public final class PlacementAssist {
    /** Copied from the intent. Everything here can also be reached by vanilla clicks or rotation. */
    private static final Set<Property<?>> COPIED;

    static {
        java.util.HashSet<Property<?>> set = new java.util.HashSet<>(StateRules.ORIENTATION);
        set.add(BlockStateProperties.DELAY);
        set.add(BlockStateProperties.MODE_COMPARATOR);
        set.add(BlockStateProperties.NOTE);
        set.add(BlockStateProperties.INVERTED);
        COPIED = Set.copyOf(set);
    }

    private PlacementAssist() {
    }

    public static BlockState apply(ServerPlayer player, BlockItem item, BlockPlaceContext ctx, BlockState vanilla) {
        BlockPos pos = ctx.getClickedPos();
        BlockState want = IntentStore.take(player, pos);
        if (want == null || !ServerConfig.enabled()) return vanilla;
        Block wantBlock = want.getBlock();
        if (wantBlock.asItem() != item) return vanilla;

        boolean sameBlock = vanilla != null && vanilla.is(wantBlock);
        // A different block from the same item only for standing/wall pairs (torch/wall torch, sign/wall sign...);
        // never e.g. cauldron -> water cauldron.
        if (!sameBlock && wantBlock != item.getBlock() && !(item instanceof StandingAndWallBlockItem)) return vanilla;

        Level level = ctx.getLevel();
        BlockState result = sameBlock ? vanilla : wantBlock.defaultBlockState();
        if (!sameBlock && result.hasProperty(BlockStateProperties.WATERLOGGED)) {
            result = result.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
        }
        for (Property<?> p : want.getProperties()) {
            boolean copied = COPIED.contains(p) || (p == BlockStateProperties.ATTACHED && StateRules.controls(wantBlock, p));
            if (!copied || !result.hasProperty(p)) continue;
            result = copy(result, want, p);
        }
        if (result.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType v = result.getValue(BlockStateProperties.SLAB_TYPE);
            SlabType w = want.getValue(BlockStateProperties.SLAB_TYPE);
            if (v != SlabType.DOUBLE && w != SlabType.DOUBLE) result = result.setValue(BlockStateProperties.SLAB_TYPE, w);
        }
        // A bed's head goes where the facing points; that spot has to be free too.
        if (result.hasProperty(BlockStateProperties.BED_PART) && result.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            BlockPos head = pos.relative(result.getValue(BlockStateProperties.HORIZONTAL_FACING));
            if (!level.getBlockState(head).canBeReplaced(ctx) || !level.getWorldBorder().isWithinBounds(head)) return vanilla;
        }
        if (result.equals(vanilla)) return vanilla;
        if (!((BlockItemInvoker) item).cytraprinter$canPlace(ctx, result)) return vanilla;
        return Block.updateFromNeighbourShapes(result, level, pos);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }
}

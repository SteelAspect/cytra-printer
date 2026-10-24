package dev.steelaspect.cytraprinter.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Set;

/**
 * Which block state properties a printer controls and how. Everything not listed here (fence and wall connections,
 * stair shapes, redstone power, waterlogging, leaf distance, crop age...) follows from the neighbours or from the
 * world and is ignored when deciding whether a block is right.
 */
public final class StateRules {
    /** Decided at placement by the clicked face, the click position and the way the player faces. */
    public static final Set<Property<?>> ORIENTATION = Set.of(
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.VERTICAL_DIRECTION,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.HANGING,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.ORIENTATION);

    /** Grow by one each time the same item is used on the block again. */
    public static final Set<IntegerProperty> COUNTS = Set.of(
            BlockStateProperties.CANDLES,
            BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS,
            BlockStateProperties.FLOWER_AMOUNT);

    /** Changed by right-clicking the block (repeater delay, comparator mode, note, daylight sensor, doors...). */
    public static final Set<Property<?>> CLICK = Set.of(
            BlockStateProperties.DELAY,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.NOTE,
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN);

    private StateRules() {
    }

    /** Same block and every placement-controlled property equal (orientation, counts, slab type, which half). */
    public static boolean placedCorrectly(BlockState world, BlockState target) {
        if (world.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (controls(target.getBlock(), p) && !world.getValue(p).equals(target.getValue(p))) return false;
        }
        return true;
    }

    public static boolean isPlacementProperty(Property<?> p) {
        return ORIENTATION.contains(p) || COUNTS.contains(p) || p == BlockStateProperties.SLAB_TYPE
                || p == BlockStateProperties.DOUBLE_BLOCK_HALF || p == BlockStateProperties.BED_PART;
    }

    /** Placement property of this block: the general ones, plus a ceiling hanging sign's chain style (sneak-placed). */
    public static boolean controls(Block block, Property<?> p) {
        return isPlacementProperty(p) || (p == BlockStateProperties.ATTACHED && block instanceof CeilingHangingSignBlock);
    }

    /**
     * {@code world} already is the target block with the right orientation but fewer items in it (1 of 3 candles, a
     * bottom slab of a double slab): using the item on it again gets closer.
     */
    public static boolean canGrowInto(BlockState world, BlockState target) {
        if (world.getBlock() != target.getBlock()) return false;
        boolean smaller = false;
        for (Property<?> p : target.getProperties()) {
            if (p == BlockStateProperties.SLAB_TYPE) {
                SlabType w = world.getValue(BlockStateProperties.SLAB_TYPE);
                SlabType t = target.getValue(BlockStateProperties.SLAB_TYPE);
                if (w == t) continue;
                if (t != SlabType.DOUBLE) return false;
                smaller = true;
            } else if (p instanceof IntegerProperty ip && COUNTS.contains(ip)) {
                int w = world.getValue(ip), t = target.getValue(ip);
                if (w > t) return false;
                if (w < t) smaller = true;
            } else if (controls(target.getBlock(), p) && !world.getValue(p).equals(target.getValue(p))) {
                return false;
            }
        }
        return smaller;
    }

    /**
     * True if placing {@code result} at a position where the world currently has {@code current} gets correctly
     * towards {@code target}: right block and orientation, and counts no higher than wanted. A chest whose partner
     * isn't there yet may be placed single; the partner joins it later.
     */
    public static boolean placementMatches(BlockState result, BlockState current, BlockState target, boolean partnerPresent) {
        if (result == null || result.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (p == BlockStateProperties.SLAB_TYPE) {
                SlabType r = result.getValue(BlockStateProperties.SLAB_TYPE);
                SlabType t = target.getValue(BlockStateProperties.SLAB_TYPE);
                if (r != t && t != SlabType.DOUBLE) return false;
            } else if (p instanceof IntegerProperty ip && COUNTS.contains(ip)) {
                if (result.getValue(ip) > target.getValue(ip)) return false;
            } else if (p == BlockStateProperties.CHEST_TYPE) {
                ChestType r = result.getValue(BlockStateProperties.CHEST_TYPE);
                ChestType t = target.getValue(BlockStateProperties.CHEST_TYPE);
                if (r != t && !(r == ChestType.SINGLE && !partnerPresent)) return false;
            } else if (controls(target.getBlock(), p) && !result.getValue(p).equals(target.getValue(p))) {
                return false;
            }
        }
        // Must change something: no "placing" the same state again.
        return current.getBlock() != result.getBlock() || !current.equals(result);
    }

    /** Position of the other half of a double chest, or null for a single chest / non-chest. */
    public static net.minecraft.core.BlockPos chestPartner(net.minecraft.core.BlockPos pos, BlockState target) {
        if (!(target.getBlock() instanceof ChestBlock) || target.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) return null;
        return pos.relative(ChestBlock.getConnectedDirection(target));
    }
}

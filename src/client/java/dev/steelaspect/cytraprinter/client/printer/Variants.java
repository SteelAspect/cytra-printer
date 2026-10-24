package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.mixin.AxeItemAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.Map;

/** Blocks that can stand in for another one: logs that an axe strips, live coral that dies into dead coral. */
final class Variants {
    private static Map<Block, Block> unstripped;
    private static Map<Block, Block> deadToAlive;
    private static Map<Block, Block> aliveToDead;

    private Variants() {
    }

    /** The log/wood/stem an axe turns into {@code stripped}, or null. */
    static Block unstripped(Block stripped) {
        if (unstripped == null) {
            Map<Block, Block> m = new HashMap<>();
            AxeItemAccessor.cytraprinter$strippables().forEach((from, to) -> m.putIfAbsent(to, from));
            unstripped = m;
        }
        return unstripped.get(stripped);
    }

    /** Live coral for a dead coral block/fan/plant, or null. */
    static Block alive(Block dead) {
        buildCoral();
        return deadToAlive.get(dead);
    }

    /** Dead coral for a live one, or null. */
    static Block dead(Block alive) {
        buildCoral();
        return aliveToDead.get(alive);
    }

    private static void buildCoral() {
        if (deadToAlive != null) return;
        Map<Block, Block> d2a = new HashMap<>(), a2d = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getPath().startsWith("dead_")) continue;
            Block live = BuiltInRegistries.BLOCK.get(id.withPath(id.getPath().substring(5)));
            if (live == null || live == net.minecraft.world.level.block.Blocks.AIR) continue;
            BlockState s = live.defaultBlockState();
            if (!s.is(BlockTags.CORAL_BLOCKS) && !s.is(BlockTags.CORALS) && !s.is(BlockTags.WALL_CORALS) && !s.is(BlockTags.CORAL_PLANTS)) continue;
            d2a.put(block, live);
            a2d.put(live, block);
        }
        deadToAlive = d2a;
        aliveToDead = a2d;
    }

    /** True if live coral at pos stays alive: water in it (waterlogged) or next to it. */
    static boolean coralSurvives(BlockGetter level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
        for (Direction d : Direction.values()) {
            if (level.getFluidState(pos.relative(d)).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** {@code to}'s default state with every property it shares with {@code from} copied over (axis, facing...). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static BlockState copyProperties(BlockState from, Block to) {
        BlockState out = to.defaultBlockState();
        for (Property p : from.getProperties()) {
            if (out.hasProperty(p)) out = out.setValue(p, from.getValue(p));
        }
        return out;
    }
}

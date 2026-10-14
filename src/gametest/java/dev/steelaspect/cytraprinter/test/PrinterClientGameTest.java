package dev.steelaspect.cytraprinter.test;

import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Stats;
import dev.steelaspect.cytraprinter.state.StateRules;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds every placement-relevant state of a long list of blocks (one per 3x3 cell, with stone supports where a
 * block needs one), saves it as a schematic, clears it and lets the printer rebuild it:
 * <ol>
 *     <li>creative with the server helper,</li>
 *     <li>creative in vanilla mode (helper off: only exact vanilla clicks),</li>
 *     <li>survival in vanilla mode on a small build, with items in the main inventory, a missing item and a
 *     wrong block that has to be mined.</li>
 * </ol>
 * Every position is then compared with the schematic.
 */
public class PrinterClientGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("CytraPrinterTest");
    private static final int CELL = 3;
    private static final int Y = 120;
    private int failures;

    private record Cell(BlockPos pos, BlockState state) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamerule doDaylightCycle false");
            world.getServer().runCommand("gamerule doMobSpawning false");
            world.getServer().runCommand("gamerule doTileDrops false");
            world.getServer().runCommand("gamerule randomTickSpeed 0");
            world.getServer().runCommand("time set noon");
            BlockPos feet = world.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
            BlockPos origin = new BlockPos(feet.getX() + 4, Y, feet.getZ() + 4);

            context.runOnClient(client -> {
                Configs.PLACEMENTS_PER_TICK.setIntegerValue(24);
                Configs.FIX_WRONG_BLOCKS.setBooleanValue(true);
                Configs.CLEAR_EXTRA_BLOCKS.setBooleanValue(false);
                Configs.CLICK_FIX_STATES.setBooleanValue(true);
                Configs.SAFE_REDSTONE_ORDER.setBooleanValue(true);
                Configs.SIGN_TEXT.setBooleanValue(true);
                Configs.SHOW_HUD.setBooleanValue(true);
                Configs.HOTBAR_SLOTS.setValueFromString("7,8,9");
            });

            // --- big build: every state, creative ---
            int[] size = new int[2];
            List<Cell> cells = world.getServer().computeOnServer(server -> buildAll(server, origin, size));
            LOG.info("Test build: {} cells, {}x{} blocks", cells.size(), size[0], size[1]);
            BlockPos max = origin.offset(size[0] - 1, 3, size[1] - 1);
            LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, origin, max, "all-states"));

            world.getServer().runCommand("gamemode creative @a");
            for (boolean helper : new boolean[]{true, false}) {
                String mode = helper ? "creative+helper" : "creative+vanilla";
                world.getServer().runOnServer(server -> clear(server, origin, max));
                context.waitTicks(5);
                // Wrong blocks the printer has to fix, and an extra block it must leave alone (clearing is off).
                world.getServer().runOnServer(server -> {
                    ServerLevel level = server.overworld();
                    for (int i = 0; i < Math.min(6, cells.size()); i++) {
                        level.setBlock(cells.get(i * 7 % cells.size()).pos(), Blocks.DIRT.defaultBlockState(), 2);
                    }
                    level.setBlock(origin.offset(1, 3, 1), Blocks.GLASS.defaultBlockState(), 2);
                });
                context.runOnClient(client -> Configs.USE_SERVER_HELPER.setBooleanValue(helper));
                SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, "printer-test", true, true);
                context.runOnClient(client -> DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false));
                context.waitTicks(10);
                if (helper) check(mode + ": server helper detected", context.computeOnClient(c -> ServerHelper.active()), ServerHelper.statusKey());
                else check(mode + ": helper unused", !context.computeOnClient(c -> ServerHelper.active()), "still active");

                long start = System.nanoTime();
                long placedBefore = context.computeOnClient(c -> Stats.placedTotal);
                context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
                walk(context, world, origin, size);
                walk(context, world, origin, size);
                context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
                double secs = (System.nanoTime() - start) / 1e9;
                long placed = context.computeOnClient(c -> Stats.placedTotal) - placedBefore;
                LOG.info("{}: {} placements in {} s", mode, placed, String.format(Locale.ROOT, "%.1f", secs));

                int wrong = verify(world, cells, mode, !helper);
                check(mode + ": every cell matches the schematic", wrong == 0, wrong + " wrong");
                boolean extraKept = world.getServer().computeOnServer(s -> s.overworld().getBlockState(origin.offset(1, 3, 1)).is(Blocks.GLASS));
                check(mode + ": extra block left alone", extraKept, "glass was removed");
                checkSigns(world, cells, mode);
                world.getServer().runOnServer(server -> server.overworld().setBlock(origin.offset(1, 3, 1), Blocks.AIR.defaultBlockState(), 2));
                context.runOnClient(client -> DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic));
            }

            // --- small survival build, vanilla mode ---
            survival(context, world, origin.offset(0, 0, size[1] + 8));

            context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
        }
        if (this.failures > 0) throw new AssertionError(this.failures + " printer check(s) failed, see log");
    }

    // ---------------------------------------------------------------- big build

    /** Every distinct placement state of each test block, one per cell, plus supports. */
    private static List<Cell> buildAll(MinecraftServer server, BlockPos origin, int[] size) {
        ServerLevel level = server.overworld();
        List<BlockState> states = new ArrayList<>();
        // CYTRA_TEST_BLOCKS=bell,oak_wall_hanging_sign limits the big build to those blocks.
        String only = System.getenv("CYTRA_TEST_BLOCKS");
        for (Block block : testBlocks()) {
            if (only != null && !only.isBlank() && !List.of(only.split(",")).contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath())) continue;
            states.addAll(distinctStates(block));
        }
        int perRow = (int) Math.ceil(Math.sqrt(states.size()));
        size[0] = perRow * CELL;
        size[1] = (int) Math.ceil(states.size() / (double) perRow) * CELL;
        // Floor, then air above it.
        for (int x = 0; x < size[0]; x++) {
            for (int z = 0; z < size[1]; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 1; y <= 6; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            BlockPos pos = origin.offset((i % perRow) * CELL + 1, 1, (i / perRow) * CELL + 1);
            BlockState state = states.get(i);
            if (place(level, pos, state)) cells.add(new Cell(pos, state));
        }
        return cells;
    }

    /** Places one test state with whatever stone supports it needs; false (and nothing left) if it can't stand. */
    private static boolean place(ServerLevel level, BlockPos pos, BlockState state) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        List<BlockPos> supports = new ArrayList<>();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
            level.setBlock(partner, state.setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE).getOpposite()), flags);
        }
        // Blocks whose vanilla placement needs more than canSurvive checks.
        if (state.getBlock() instanceof net.minecraft.world.level.block.BellBlock
                && state.getValue(BlockStateProperties.BELL_ATTACHMENT) == net.minecraft.world.level.block.state.properties.BellAttachType.DOUBLE_WALL) {
            Direction f = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            level.setBlock(pos.relative(f), Blocks.STONE.defaultBlockState(), flags);
            level.setBlock(pos.relative(f.getOpposite()), Blocks.STONE.defaultBlockState(), flags);
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock) {
            level.setBlock(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise()), Blocks.STONE.defaultBlockState(), flags);
        }
        if (!state.canSurvive(level, pos)) {
            List<BlockPos> tries = new ArrayList<>();
            tries.add(pos.above());
            for (Direction d : Direction.Plane.HORIZONTAL) tries.add(pos.relative(d));
            for (BlockPos t : tries) {
                if (!level.getBlockState(t).isAir()) continue;
                level.setBlock(t, Blocks.STONE.defaultBlockState(), flags);
                if (state.canSurvive(level, pos)) {
                    supports.add(t);
                    break;
                }
                level.setBlock(t, Blocks.AIR.defaultBlockState(), flags);
            }
            if (!state.canSurvive(level, pos)) {
                // Two supports (double-wall bells).
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    level.setBlock(pos.relative(d), Blocks.STONE.defaultBlockState(), flags);
                    level.setBlock(pos.relative(d.getOpposite()), Blocks.STONE.defaultBlockState(), flags);
                    if (state.canSurvive(level, pos)) break;
                    level.setBlock(pos.relative(d), Blocks.AIR.defaultBlockState(), flags);
                    level.setBlock(pos.relative(d.getOpposite()), Blocks.AIR.defaultBlockState(), flags);
                }
            }
            if (!state.canSurvive(level, pos)) {
                LOG.info("Skipping {} (can't stand in the test layout)", state);
                return false;
            }
        }
        level.setBlock(pos, state, flags);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), flags);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            level.setBlock(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)), state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), flags);
        }
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            String id = String.format(Locale.ROOT, "%d %d", pos.getX(), pos.getZ());
            sign.updateText(t -> t.setMessage(0, Component.literal("Cytra")).setMessage(1, Component.literal(id)), true);
            sign.setChanged();
        }
        return true;
    }

    private static List<Block> testBlocks() {
        return List.of(
                Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_LOG, Blocks.CHEST, Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.RED_BED,
                Blocks.REPEATER, Blocks.COMPARATOR, Blocks.NOTE_BLOCK, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON,
                Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER, Blocks.STONE_BUTTON, Blocks.LEVER, Blocks.OAK_TRAPDOOR,
                Blocks.IRON_TRAPDOOR, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.OAK_HANGING_SIGN, Blocks.OAK_WALL_HANGING_SIGN,
                Blocks.WHITE_BANNER, Blocks.WHITE_WALL_BANNER, Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL, Blocks.LANTERN,
                Blocks.IRON_CHAIN, Blocks.END_ROD, Blocks.LIGHTNING_ROD, Blocks.AMETHYST_CLUSTER, Blocks.GRINDSTONE, Blocks.BELL,
                Blocks.LECTERN, Blocks.ANVIL, Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.FURNACE, Blocks.BARREL, Blocks.CRAFTER,
                Blocks.LADDER, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.CANDLE, Blocks.SEA_PICKLE,
                Blocks.SNOW, Blocks.CAMPFIRE, Blocks.OAK_FENCE_GATE, Blocks.POINTED_DRIPSTONE, Blocks.TRIPWIRE_HOOK,
                Blocks.DAYLIGHT_DETECTOR, Blocks.POTTED_POPPY, Blocks.OAK_FENCE, Blocks.GLASS_PANE, Blocks.COBBLESTONE_WALL,
                Blocks.REDSTONE_WIRE, Blocks.SAND, Blocks.QUARTZ_PILLAR, Blocks.BEEHIVE, Blocks.LOOM, Blocks.STONECUTTER,
                Blocks.OAK_SHELF, Blocks.COPPER_TORCH, Blocks.SCAFFOLDING, Blocks.RAIL);
    }

    /** One state per combination of the properties the printer controls (others default), minus derived halves. */
    private static List<BlockState> distinctStates(Block block) {
        Map<List<Object>, BlockState> out = new LinkedHashMap<>();
        for (BlockState s : block.getStateDefinition().getPossibleStates()) {
            BlockState norm = block.defaultBlockState();
            List<Object> key = new ArrayList<>();
            for (Property<?> p : s.getProperties()) {
                if (StateRules.controls(block, p) || StateRules.CLICK.contains(p)) {
                    norm = copy(norm, s, p);
                    key.add(s.getValue(p));
                }
            }
            if (norm.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && norm.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
            if (norm.hasProperty(BlockStateProperties.BED_PART) && norm.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) continue;
            if (norm.hasProperty(BlockStateProperties.NOTE) && norm.getValue(BlockStateProperties.NOTE) % 8 != 3) continue;
            if (norm.hasProperty(BlockStateProperties.SLAB_TYPE) && norm.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE && block != Blocks.OAK_SLAB) continue;
            if (block == Blocks.CHEST && norm.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue; // comes with LEFT
            out.putIfAbsent(key, norm);
        }
        return new ArrayList<>(out.values());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }

    private static LitematicaSchematic capture(MinecraftServer server, BlockPos min, BlockPos max, String name) {
        AreaSelection area = new AreaSelection();
        area.addSubRegionBox(new Box(min, max, name), false);
        area.setExplicitOrigin(min);
        return LitematicaSchematic.createFromWorld(server.overworld(), area, new LitematicaSchematic.SchematicSaveInfo(false, true), "steelaspect", msg -> LOG.warn("schematic: {}", msg));
    }

    private static void clear(MinecraftServer server, BlockPos min, BlockPos max) {
        ServerLevel level = server.overworld();
        for (BlockPos p : BlockPos.betweenClosed(min.above(), max.above(3))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    /** Flies over the area in a grid; at each stop waits until the printer stops making progress. */
    private void walk(ClientGameTestContext context, TestSingleplayerContext world, BlockPos origin, int[] size) {
        int step = 6;
        boolean flip = false;
        for (int z = 2; z < size[1] + step - 2; z += step) {
            List<Integer> xs = new ArrayList<>();
            for (int x = 2; x < size[0] + step - 2; x += step) xs.add(Math.min(x, size[0] - 1));
            if (flip) java.util.Collections.reverse(xs);
            flip = !flip;
            for (int x : xs) {
                double px = origin.getX() + x + 0.5, pz = origin.getZ() + Math.min(z, size[1] - 1) + 0.5;
                world.getServer().runOnServer(server -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.getAbilities().mayfly = true;
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    p.teleportTo(server.overworld(), px, origin.getY() + 3, pz, Set.of(), 0f, 60f, true);
                });
                settle(context);
            }
        }
    }

    /** Waits until the printer has had nothing new to do for a while (max 6 s per stop). */
    private static void settle(ClientGameTestContext context) {
        context.waitTicks(3);
        long last = -1;
        int quiet = 0;
        for (int i = 0; i < 120 && quiet < 12; i++) {
            context.waitTick();
            long placed = context.computeOnClient(c -> Stats.placedTotal);
            int remaining = context.computeOnClient(c -> Stats.remaining - Stats.held - Stats.unsolvable);
            if (placed == last && remaining == 0) quiet += 2;
            else if (placed == last) quiet++;
            else quiet = 0;
            last = placed;
        }
    }

    /** Counts cells (and their supports' targets) that differ from the schematic in anything the printer controls. */
    private int verify(TestSingleplayerContext world, List<Cell> cells, String mode, boolean vanilla) {
        List<String> report = world.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            List<String> bad = new ArrayList<>();
            for (Cell c : cells) {
                BlockState have = level.getBlockState(c.pos());
                BlockState want = c.state();
                // Chainless ceiling hanging signs only come in 4 rotations from a vanilla click.
                if (vanilla && want.getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                        && !want.getValue(BlockStateProperties.ATTACHED) && want.getValue(BlockStateProperties.ROTATION_16) % 4 != 0) continue;
                boolean ok = StateRules.placedCorrectly(have, want);
                if (ok) {
                    for (Property<?> p : want.getProperties()) {
                        if (StateRules.CLICK.contains(p) && !have.getValue(p).equals(want.getValue(p))) {
                            // Iron doors/trapdoors, levers and barrels aren't click-fixed; open fence gates/doors need no power.
                            if (want.is(Blocks.IRON_DOOR) || want.is(Blocks.IRON_TRAPDOOR) || want.is(Blocks.BARREL)) continue;
                            ok = false;
                        }
                    }
                }
                if (!ok) bad.add(want + " -> " + have + " at " + c.pos().toShortString());
            }
            return bad;
        });
        Set<String> blocks = new HashSet<>();
        for (String line : report) {
            LOG.warn("[{}] wrong: {}", mode, line);
            blocks.add(line.substring(0, line.indexOf('}') + 1));
        }
        if (!report.isEmpty()) LOG.warn("[{}] {} wrong cells, blocks: {}", mode, report.size(), blocks);
        return report.size();
    }

    private void checkSigns(TestSingleplayerContext world, List<Cell> cells, String mode) {
        int[] counts = world.getServer().computeOnServer(server -> {
            int total = 0, ok = 0;
            for (Cell c : cells) {
                if (!(server.overworld().getBlockEntity(c.pos()) instanceof SignBlockEntity sign)) continue;
                total++;
                String id = String.format(Locale.ROOT, "%d %d", c.pos().getX(), c.pos().getZ());
                if (sign.getFrontText().getMessage(0, false).getString().equals("Cytra")
                        && sign.getFrontText().getMessage(1, false).getString().equals(id)) ok++;
            }
            return new int[]{total, ok};
        });
        if (counts[0] == 0) return; // no signs in a filtered run
        check(mode + ": sign text written (" + counts[1] + "/" + counts[0] + ")", counts[0] == counts[1], counts[1] + "/" + counts[0]);
    }

    // ---------------------------------------------------------------- survival

    private void survival(ClientGameTestContext context, TestSingleplayerContext world, BlockPos o) {
        String mode = "survival+vanilla";
        BlockPos max = o.offset(6, 3, 6);
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            int f = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), f);
            level.setBlock(o.offset(1, 1, 1), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), f);
            level.setBlock(o.offset(3, 1, 1), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP), f);
            level.setBlock(o.offset(5, 1, 1), Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X), f);
            level.setBlock(o.offset(1, 1, 3), Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), f);
            level.setBlock(o.offset(3, 1, 3), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.DELAY, 3).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), f);
            level.setBlock(o.offset(5, 1, 3), Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP), f);
            level.setBlock(o.offset(1, 1, 5), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), f);
            level.setBlock(o.offset(3, 1, 5), Blocks.DIAMOND_BLOCK.defaultBlockState(), f); // never given: must be reported missing
            level.setBlock(o.offset(5, 1, 5), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3), f);
        });
        LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, o, max, "survival"));
        world.getServer().runOnServer(server -> {
            clear(server, o, max);
            // Wrong block where the first stair goes: has to be mined first.
            server.overworld().setBlock(o.offset(1, 1, 1), Blocks.DIRT.defaultBlockState(), 2);
        });
        world.getServer().runCommand("gamemode survival @a");
        world.getServer().runCommand("gamerule doTileDrops true");
        world.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            Inventory inv = p.getInventory();
            inv.clearContent();
            inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            // Blocks deliberately in the main inventory, not the hotbar.
            inv.setItem(10, new ItemStack(Items.OAK_STAIRS, 16));
            inv.setItem(11, new ItemStack(Items.OAK_LOG, 16));
            inv.setItem(12, new ItemStack(Items.FURNACE, 4));
            inv.setItem(13, new ItemStack(Items.REPEATER, 4));
            inv.setItem(14, new ItemStack(Items.OAK_SLAB, 4));
            inv.setItem(15, new ItemStack(Items.OBSERVER, 4));
            inv.setItem(16, new ItemStack(Items.CANDLE, 8));
            inv.setItem(20, new ItemStack(Items.IRON_SHOVEL));
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), o.getX() + 3.5, o.getY() + 3, o.getZ() + 3.5, Set.of(), 0f, 60f, true);
        });
        context.runOnClient(client -> {
            Configs.USE_SERVER_HELPER.setBooleanValue(false);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, o, "printer-survival", true, true), false);
        });
        context.waitTicks(10);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        context.waitTicks(10);
        boolean reportedMissing = context.computeOnClient(c -> Stats.missing.containsKey(Items.DIAMOND_BLOCK));
        for (int i = 0; i < 4; i++) settle(context);
        LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("survival-printed")));
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));

        int wrong = context.computeOnClient(client -> {
            int n = 0;
            var sw = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            for (BlockPos p : BlockPos.betweenClosed(o.above(), max)) {
                BlockState want = sw.getBlockState(p), have = client.level.getBlockState(p);
                if (want.isAir() || want.is(Blocks.DIAMOND_BLOCK)) continue;
                boolean ok = StateRules.placedCorrectly(have, want);
                if (ok && want.hasProperty(BlockStateProperties.DELAY)) ok = have.getValue(BlockStateProperties.DELAY).equals(want.getValue(BlockStateProperties.DELAY));
                if (!ok) {
                    LOG.warn("[{}] wrong: {} -> {} at {}", mode, want, have, p.toShortString());
                    n++;
                }
            }
            return n;
        });
        check(mode + ": build matches (except the missing diamond block)", wrong == 0, wrong + " wrong");
        check(mode + ": missing diamond block reported", reportedMissing, "not in Stats.missing");
        boolean diamondAir = world.getServer().computeOnServer(s -> s.overworld().getBlockState(o.offset(3, 1, 5)).isAir());
        check(mode + ": nothing placed where the item is missing", diamondAir, "something placed");
        int hotbarSlot = context.computeOnClient(c -> c.player.getInventory().getSelectedSlot());
        LOG.info("[{}] selected slot after printing: {}", mode, hotbarSlot);
    }

    private void check(String label, boolean ok, String detail) {
        if (ok) {
            LOG.info("PASS {}", label);
        } else {
            LOG.error("FAIL {}: {}", label, detail);
            this.failures++;
        }
    }
}

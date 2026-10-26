package dev.steelaspect.cytraprinter.test;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.gui.PrinterOptions;
import dev.steelaspect.cytraprinter.client.gui.PrinterSettingsScreen;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Skipped;
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
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("gamerule advance_time false");
            world.getServer().runCommand("gamerule spawn_mobs false");
            world.getServer().runCommand("gamerule block_drops false");
            world.getServer().runCommand("gamerule random_tick_speed 0");
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

            // Settings screen, Hub-style option rows and the server helper switch (CYTRA_TEST_ONLY=settings: only this).
            settings(context, world);
            // Skipped-block outlines, next and retry (CYTRA_TEST_ONLY=skipped: only this and the settings).
            skipped(context, world, origin.offset(-12, 0, 0));
            lagSafety(context, world, origin.offset(-24, 0, 0));
            // Restock through Take It Out (only with -PwithTakeItOut; CYTRA_TEST_ONLY=takeitout runs just this).
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("takeitout")) takeItOut(context, world, origin.offset(-36, 0, 0));
            if ("takeitout".equals(System.getenv("CYTRA_TEST_ONLY"))) {
                if (this.failures > 0) throw new AssertionError(this.failures + " printer check(s) failed, see log");
                return;
            }
            if ("settings".equals(System.getenv("CYTRA_TEST_ONLY")) || "skipped".equals(System.getenv("CYTRA_TEST_ONLY"))) {
                if (this.failures > 0) throw new AssertionError(this.failures + " printer check(s) failed, see log");
                return;
            }

            // CYTRA_TEST_ONLY=extras runs just the extras (exclude, strip, coral, bedrock, observers).
            if ("extras".equals(System.getenv("CYTRA_TEST_ONLY"))) {
                extras(context, world, origin);
                context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
                if (this.failures > 0) throw new AssertionError(this.failures + " printer check(s) failed, see log");
                return;
            }

            // --- big build: every state, creative ---
            int[] size = new int[2];
            List<Cell> cells = world.getServer().computeOnServer(server -> buildAll(server, origin, size));
            LOG.info("Test build: {} cells, {}x{} blocks", cells.size(), size[0], size[1]);
            BlockPos max = origin.offset(size[0] - 1, 3, size[1] - 1);
            LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, origin, max, "all-states"));

            world.getServer().runCommand("gamemode creative @a");
            // CYTRA_TEST_ONLY=perf: printer tick cost while printing, idle on a finished build, and out of blocks.
            if ("perf".equals(System.getenv("CYTRA_TEST_ONLY"))) {
                perf(context, world, origin, size, schematic);
                if (this.failures > 0) throw new AssertionError(this.failures + " printer check(s) failed, see log");
                return;
            }
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
                context.runOnClient(client -> {
                    Stats.resetTiming();
                    Configs.PRINTING.setBooleanValue(true);
                });
                walk(context, world, origin, size);
                walk(context, world, origin, size);
                context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
                LOG.info("{}: printer cost {}", mode, timing(context));
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

            // --- extras, survival ---
            extras(context, world, origin.offset(size[0] + 8, 0, 0));

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
        if (state.getBlock() instanceof net.minecraft.world.level.block.WallSkullBlock) {
            level.setBlock(pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()), Blocks.STONE.defaultBlockState(), flags);
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
            sign.updateText(t -> t.asMutable().setLine(0, Component.literal("Cytra")).setLine(1, Component.literal(id)).asImmutable(), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            sign.setChanged();
        }
        return true;
    }

    private static List<Block> testBlocks() {
        return List.of(
                Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_LOG, Blocks.CHEST, Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.BED.red(),
                Blocks.REPEATER, Blocks.COMPARATOR, Blocks.NOTE_BLOCK, Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON,
                Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER, Blocks.STONE_BUTTON, Blocks.LEVER, Blocks.OAK_TRAPDOOR,
                Blocks.IRON_TRAPDOOR, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.OAK_HANGING_SIGN, Blocks.OAK_WALL_HANGING_SIGN,
                Blocks.BANNER.white(), Blocks.WALL_BANNER.white(), Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL, Blocks.LANTERN,
                Blocks.IRON_CHAIN, Blocks.END_ROD, Blocks.LIGHTNING_ROD.waxed().unaffected(), Blocks.AMETHYST_CLUSTER, Blocks.GRINDSTONE, Blocks.BELL,
                Blocks.LECTERN, Blocks.ANVIL, Blocks.GLAZED_TERRACOTTA.white(), Blocks.FURNACE, Blocks.BARREL, Blocks.CRAFTER,
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
                if (sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString().equals("Cytra")
                        && sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(1).getString().equals(id)) ok++;
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
        world.getServer().runCommand("gamerule block_drops true");
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
            inv.setItem(22, new ItemStack(Items.STONE_PICKAXE));
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
        // No tool is faster on glass than a hand: a pickaxe, not the sword in hand.
        boolean pickaxe = context.computeOnClient(client -> {
            client.player.getInventory().setSelectedSlot(0);
            dev.steelaspect.cytraprinter.client.printer.Hotbar.selectTool(client, Blocks.GLASS.defaultBlockState());
            return client.player.getMainHandItem().is(Items.STONE_PICKAXE);
        });
        check(mode + ": pickaxe for glass", pickaxe, "holding something else");
        boolean shovel = context.computeOnClient(client -> {
            dev.steelaspect.cytraprinter.client.printer.Hotbar.selectTool(client, Blocks.DIRT.defaultBlockState());
            return client.player.getMainHandItem().is(Items.IRON_SHOVEL);
        });
        check(mode + ": shovel for dirt", shovel, "holding something else");
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

    // ---------------------------------------------------------------- extras

    /**
     * Survival, vanilla mode: a stripped log from a plain log and an axe, composters placed (no fill levels), dead coral
     * from live coral, live coral held back without water, an excluded block, a protected wrong block, bedrock
     * broken with the piston trick (on a floor and with a slime block) and an observer that would fire a piston.
     */
    private void extras(ClientGameTestContext context, TestSingleplayerContext world, BlockPos o) {
        String mode = "extras";
        BlockPos max = o.offset(8, 4, 8);
        BlockPos log = o.offset(1, 1, 1), comp5 = o.offset(3, 1, 1), comp8 = o.offset(5, 1, 1), deadCoral = o.offset(7, 1, 1);
        BlockPos liveCoral = o.offset(1, 1, 7), excluded = o.offset(3, 1, 7), prot = o.offset(5, 1, 7);
        BlockPos bedrockFloor = o.offset(7, 0, 7), bedrockHigh = o.offset(1, 1, 4);
        BlockPos wall = o.offset(7, 1, 4), observer = o.offset(6, 1, 4), piston = o.offset(5, 1, 4);
        int f = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), f);
            level.setBlock(log, Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X), f);
            level.setBlock(comp5, Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 5), f);
            level.setBlock(comp8, Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 8), f);
            level.setBlock(deadCoral, Blocks.DEAD_BRAIN_CORAL_BLOCK.defaultBlockState(), f);
            level.setBlock(liveCoral, Blocks.TUBE_CORAL_BLOCK.defaultBlockState(), f);
            level.setBlock(excluded, Blocks.GOLD_BLOCK.defaultBlockState(), f);
            level.setBlock(prot, Blocks.STONE.defaultBlockState(), f);
            level.setBlock(bedrockHigh, Blocks.STONE.defaultBlockState(), f);
            level.setBlock(wall, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
            level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), f);
            level.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), f);
        });
        LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, o, max, "extras"));
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            clear(server, o, max);
            // Already there: the watched wall and the piston behind the observer, a protected wrong block, bedrock.
            level.setBlock(wall, Blocks.COBBLESTONE_WALL.defaultBlockState(), 3);
            level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
            level.setBlock(prot, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
            level.setBlock(bedrockFloor, Blocks.BEDROCK.defaultBlockState(), 3);
            level.setBlock(bedrockHigh, Blocks.BEDROCK.defaultBlockState(), 3);
        });
        world.getServer().runCommand("gamemode survival @a");
        world.getServer().runCommand("gamerule block_drops true");
        world.getServer().runCommand("effect give @a haste infinite 1 true");
        world.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            Inventory inv = p.getInventory();
            inv.clearContent();
            ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
            pick.enchant(server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.EFFICIENCY), 5);
            inv.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            inv.setItem(9, new ItemStack(Items.OAK_LOG, 4));
            inv.setItem(10, new ItemStack(Items.IRON_AXE));
            inv.setItem(11, new ItemStack(Items.COMPOSTER, 2));
            inv.setItem(13, new ItemStack(Items.BRAIN_CORAL_BLOCK, 2));
            inv.setItem(14, new ItemStack(Items.TUBE_CORAL_BLOCK, 2));
            inv.setItem(15, new ItemStack(Items.GOLD_BLOCK, 4));
            inv.setItem(16, new ItemStack(Items.STONE, 16));
            inv.setItem(17, new ItemStack(Items.PISTON, 8));
            inv.setItem(18, new ItemStack(Items.REDSTONE_TORCH, 8));
            inv.setItem(19, new ItemStack(Items.SLIME_BLOCK, 4));
            inv.setItem(20, pick);
            inv.setItem(21, new ItemStack(Items.OBSERVER, 2));
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), o.getX() + 4.5, o.getY() + 1, o.getZ() + 4.5, Set.of(), 0f, 60f, true);
        });
        context.runOnClient(client -> {
            Configs.USE_SERVER_HELPER.setBooleanValue(false);
            Configs.EXCLUDED_BLOCKS.setStrings(List.of("gold_block"));
            Configs.PROTECTED_BLOCKS.setStrings(List.of("emerald_block"));
            Configs.FIX_WRONG_BLOCKS.setBooleanValue(true);
            Configs.STRIP_LOGS.setBooleanValue(true);
            Configs.LIVE_CORAL_FOR_DEAD.setBooleanValue(true);
            Configs.CORAL_WAIT_FOR_WATER.setBooleanValue(true);
            Configs.BREAK_BEDROCK.setBooleanValue(true);
            Configs.SAFE_OBSERVERS.setBooleanValue(true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, o, "printer-extras", true, true), false);
        });
        context.waitTicks(10);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        for (int i = 0; i < 6; i++) settle(context);
        context.waitTicks(120); // coral dies
        for (int i = 0; i < 2; i++) settle(context);
        LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("extras-printed")));
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));

        Map<BlockPos, BlockState> got = world.getServer().computeOnServer(server -> {
            Map<BlockPos, BlockState> m = new LinkedHashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) m.put(p.immutable(), server.overworld().getBlockState(p));
            return m;
        });
        BlockState l = got.get(log);
        check(mode + ": stripped log from plain log + axe", l.is(Blocks.STRIPPED_OAK_LOG) && l.getValue(BlockStateProperties.AXIS) == Direction.Axis.X, l.toString());
        BlockState c5 = got.get(comp5), c8 = got.get(comp8);
        // 26.3 port: no composter levels, the composters are just placed (the fill level isn't a placement property).
        check(mode + ": composters placed", c5.is(Blocks.COMPOSTER) && c8.is(Blocks.COMPOSTER), c5 + " / " + c8);
        check(mode + ": dead coral from live coral", got.get(deadCoral).is(Blocks.DEAD_BRAIN_CORAL_BLOCK), got.get(deadCoral).toString());
        check(mode + ": live coral waits for water", got.get(liveCoral).isAir(), got.get(liveCoral).toString());
        check(mode + ": excluded block not placed", got.get(excluded).isAir(), got.get(excluded).toString());
        check(mode + ": protected block not broken", got.get(prot).is(Blocks.EMERALD_BLOCK), got.get(prot).toString());
        check(mode + ": bedrock on a floor broken and printed", got.get(bedrockFloor).is(Blocks.STONE), got.get(bedrockFloor).toString());
        check(mode + ": bedrock without a floor broken and printed", got.get(bedrockHigh).is(Blocks.STONE), got.get(bedrockHigh).toString());
        check(mode + ": observer held back (would fire the piston)", got.get(observer).isAir(), got.get(observer).toString());
        check(mode + ": piston never fired", got.get(piston).is(Blocks.PISTON) && !got.get(piston).getValue(BlockStateProperties.EXTENDED), got.get(piston).toString());
        List<String> leftovers = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : got.entrySet()) {
            BlockState s = e.getValue();
            if (e.getKey().equals(piston)) continue;
            if (s.is(Blocks.PISTON) || s.is(Blocks.PISTON_HEAD) || s.is(Blocks.MOVING_PISTON) || s.is(Blocks.SLIME_BLOCK)
                    || s.is(Blocks.REDSTONE_TORCH) || s.is(Blocks.REDSTONE_WALL_TORCH)) leftovers.add(s + " at " + e.getKey().toShortString());
        }
        check(mode + ": bedrock setup cleaned up", leftovers.isEmpty(), leftovers.toString());

        context.runOnClient(client -> {
            Configs.EXCLUDED_BLOCKS.setStrings(List.of());
            Configs.PROTECTED_BLOCKS.setStrings(List.of());
            Configs.BREAK_BEDROCK.setBooleanValue(false);
            DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic);
        });
    }

    private void settings(ClientGameTestContext context, TestSingleplayerContext world) {
        for (String tab : List.of("printer", "accuracy", "blocks", "extras")) {
            context.runOnClient(client -> client.gui.setScreen(PrinterSettingsScreen.onTab(null, tab)));
            context.waitTicks(5);
            boolean open = context.computeOnClient(client -> client.gui.screen() instanceof PrinterSettingsScreen);
            check("settings screen " + tab, open, "screen not open");
            LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("settings-" + tab)));
        }
        context.runOnClient(client -> client.gui.setScreen(PrinterSettingsScreen.onTab(null, "printer")));
        context.runOnClient(client -> client.gui.setScreen(null));

        // Every option has a row, and list rows round-trip through the comma text.
        int missing = context.computeOnClient(client -> {
            int n = 0;
            for (PrinterOptions.Group g : PrinterOptions.Group.values()) {
                for (var option : g.options) if (PrinterOptions.rowFor(option) == null) n++;
            }
            return n;
        });
        check("settings rows for every option", missing == 0, missing + " options without a row");
        List<String> split = PrinterOptions.splitList(" stone, #minecraft:logs ,, stone,glass ");
        check("list text split", split.equals(List.of("stone", "#minecraft:logs", "glass")), split.toString());
        check("list text join", PrinterOptions.joinList(split).equals("stone, #minecraft:logs, glass"), PrinterOptions.joinList(split));

        // The server helper switch: off reaches the client right away, on brings it back.
        world.getServer().runOnServer(server -> CytraPrinter.setHelperEnabled(server, false));
        context.waitTicks(10);
        String off = context.computeOnClient(client -> ServerHelper.statusKey());
        check("helper switched off", "off_server".equals(off), off);
        world.getServer().runOnServer(server -> CytraPrinter.setHelperEnabled(server, true));
        context.waitTicks(10);
        String on = context.computeOnClient(client -> ServerHelper.statusKey());
        check("helper switched on", "on".equals(on), on);
    }

    private void skipped(ClientGameTestContext context, TestSingleplayerContext world, BlockPos o) {
        BlockPos max = o.offset(6, 2, 6);
        BlockPos wrong = o.offset(2, 1, 2), place = o.offset(4, 1, 4);
        int f = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), f);
            level.setBlock(wrong, Blocks.DIRT.defaultBlockState(), f);
            level.setBlock(place, Blocks.DIRT.defaultBlockState(), f);
        });
        LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, o, max, "skipped"));
        world.getServer().runOnServer(server -> {
            clear(server, o, max);
            // A protected wrong block: the printer must leave it and outline it.
            server.overworld().setBlock(wrong, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), o.getX() + 3.5, o.getY() + 3, o.getZ() + 3.5, Set.of(), 0f, 60f, true);
        });
        world.getServer().runCommand("gamemode creative @a");
        context.runOnClient(client -> {
            Configs.PROTECTED_BLOCKS.setStrings(List.of("emerald_block"));
            Configs.SHOW_SKIPPED.setBooleanValue(true);
            Skipped.clear();
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, o, "printer-skipped", true, true), false);
        });
        context.waitTicks(10);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        context.waitTicks(40);

        String kind = context.computeOnClient(client -> {
            Skipped.Spot s = Skipped.near(client.player.getEyePosition(), 64).stream().filter(x -> x.pos().equals(wrong)).findFirst().orElse(null);
            return s == null ? "none" : s.kind().name();
        });
        check("skipped: protected wrong block outlined", "WRONG".equals(kind), kind);
        int size = context.computeOnClient(client -> Skipped.size());
        check("skipped: placed block not listed", size == 1, size + " spots");
        BlockPos next = context.computeOnClient(client -> {
            Skipped.Spot s = Skipped.next(client.player.getEyePosition());
            return s == null ? null : s.pos();
        });
        check("skipped: next finds it", wrong.equals(next), String.valueOf(next));
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0)
                .teleportTo(server.overworld(), o.getX() + 2.5, o.getY() + 3, o.getZ() + 6.5, Set.of(), 180f, 35f, true));
        context.waitTicks(10);
        LOG.info("Screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("skipped-outline")));

        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        context.runOnClient(client -> dev.steelaspect.cytraprinter.client.SkippedActions.retry(client));
        int afterRetry = context.computeOnClient(client -> Skipped.size());
        check("skipped: retry clears", afterRetry == 0, afterRetry + " spots");
        context.waitTicks(10);
        int again = context.computeOnClient(client -> Skipped.size());
        check("skipped: still wrong after retry, outlined again", again == 1, again + " spots");

        world.getServer().runOnServer(server -> server.overworld().setBlock(wrong, Blocks.DIRT.defaultBlockState(), 3));
        context.waitTicks(15);
        int fixed = context.computeOnClient(client -> Skipped.size());
        check("skipped: fixed by hand, forgotten", fixed == 0, fixed + " spots");

        context.runOnClient(client -> {
            Configs.PRINTING.setBooleanValue(false);
            Configs.PROTECTED_BLOCKS.setStrings(List.of());
            DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic);
        });
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, Blocks.AIR.defaultBlockState(), f);
        });
    }

    private void perf(ClientGameTestContext context, TestSingleplayerContext world, BlockPos origin, int[] size, LitematicaSchematic schematic) {
        BlockPos max = origin.offset(size[0] - 1, 3, size[1] - 1);
        double cx = origin.getX() + size[0] / 2.0, cz = origin.getZ() + size[1] / 2.0;
        context.runOnClient(client -> {
            Configs.USE_SERVER_HELPER.setBooleanValue(false);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, origin, "printer-perf", true, true), false);
        });
        world.getServer().runOnServer(server -> clear(server, origin, max));
        context.waitTicks(10);

        // 1. Printing the big build (creative, vanilla mode).
        context.runOnClient(client -> {
            Stats.resetTiming();
            Configs.PRINTING.setBooleanValue(true);
        });
        walk(context, world, origin, size);
        LOG.info("PERF printing: {}", timing(context));

        // 2. Idle: standing in the finished build.
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0)
                .teleportTo(server.overworld(), cx, origin.getY() + 3, cz, Set.of(), 0f, 60f, true));
        context.waitTicks(40);
        context.runOnClient(client -> Stats.resetTiming());
        context.waitTicks(200);
        LOG.info("PERF idle (build done): {}", timing(context));

        // 3. Out of blocks: survival, empty inventory, in the middle of an empty build.
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
        world.getServer().runOnServer(server -> clear(server, origin, max));
        world.getServer().runCommand("gamemode survival @a");
        world.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.getInventory().clearContent();
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), cx, origin.getY() + 3, cz, Set.of(), 0f, 60f, true);
        });
        context.waitTicks(20);
        context.runOnClient(client -> {
            Stats.resetTiming();
            Configs.PRINTING.setBooleanValue(true);
        });
        context.waitTicks(200);
        LOG.info("PERF no blocks: {}", timing(context));

        // 4. Survival with blocks: every item the build needs, a stack each.
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
        world.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            Inventory inv = p.getInventory();
            java.util.Set<net.minecraft.world.item.Item> items = new java.util.LinkedHashSet<>();
            for (BlockPos pos : BlockPos.betweenClosed(origin, max)) {
                var it = schematicState(server, pos).getBlock().asItem();
                if (it != Items.AIR) items.add(it);
            }
            int slot = 9;
            for (var it : items) if (slot < 36) inv.setItem(slot++, new ItemStack(it, 64));
        });
        context.waitTicks(5);
        context.runOnClient(client -> {
            Stats.resetTiming();
            Configs.PRINTING.setBooleanValue(true);
        });
        context.waitTicks(200);
        LOG.info("PERF survival printing: {}", timing(context));

        context.runOnClient(client -> {
            Configs.PRINTING.setBooleanValue(false);
            DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic);
        });
        world.getServer().runCommand("gamemode creative @a");
    }

    private static String timing(ClientGameTestContext context) {
        String s = context.computeOnClient(c -> Stats.timingSummary());
        return s;
    }

    private static BlockState schematicState(MinecraftServer server, BlockPos pos) {
        var w = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        return w == null ? Blocks.AIR.defaultBlockState() : w.getBlockState(pos);
    }

    /**
     * Server lag: a spot whose own change the server hasn't confirmed yet is left alone, and the server helper refuses
     * a printer click that would land next to the aimed spot (the server still has a block where the client shows air).
     */
    private void lagSafety(ClientGameTestContext context, TestSingleplayerContext world, BlockPos o) {
        BlockPos max = o.offset(4, 2, 4);
        BlockPos spot = o.offset(2, 1, 2);
        int f = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), f);
            level.setBlock(spot, Blocks.DIRT.defaultBlockState(), f);
        });
        LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, o, max, "lag"));
        world.getServer().runOnServer(server -> {
            clear(server, o, max);
            server.getPlayerList().getPlayers().get(0).teleportTo(server.overworld(), o.getX() + 2.5, o.getY() + 3, o.getZ() + 0.5, Set.of(), 0f, 60f, true);
        });
        world.getServer().runCommand("gamemode creative @a");
        context.waitTicks(5);

        // 1. A change at the spot the server hasn't answered yet: the printer waits.
        context.runOnClient(client -> {
            var handler = ((dev.steelaspect.cytraprinter.client.mixin.ClientLevelPredictionAccessor) client.level).cytraprinter$predictions();
            handler.retainKnownServerState(spot, Blocks.AIR.defaultBlockState(), client.player);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, o, "printer-lag", true, true), false);
        });
        context.waitTicks(10);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        context.waitTicks(20);
        boolean waited = world.getServer().computeOnServer(server -> server.overworld().getBlockState(spot).isAir());
        boolean pending = context.computeOnClient(client -> dev.steelaspect.cytraprinter.client.printer.Printer.pendingAt(client.level, spot));
        check("lag: unconfirmed spot left alone", waited && pending, "placed=" + !waited + " pending=" + pending);
        context.runOnClient(client -> {
            var handler = ((dev.steelaspect.cytraprinter.client.mixin.ClientLevelPredictionAccessor) client.level).cytraprinter$predictions();
            handler.endPredictionsUpTo(handler.currentSequence() + 1, client.level);
        });
        context.waitTicks(20);
        boolean placed = world.getServer().computeOnServer(server -> server.overworld().getBlockState(spot).is(Blocks.DIRT));
        check("lag: placed once confirmed", placed, "still not placed");
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));

        // 2. Server helper: the aimed spot is taken on the server, so vanilla would place on top of it. Refused.
        BlockPos above = spot.above();
        boolean refused = world.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
            dev.steelaspect.cytraprinter.server.IntentStore.add(p, new dev.steelaspect.cytraprinter.net.IntentPayload(spot, Blocks.DIRT.defaultBlockState()));
            p.gameMode.useItemOn(p, level, new ItemStack(Items.OAK_PLANKS), net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(spot).add(0, 0.5, 0), Direction.UP, spot, false));
            return level.getBlockState(above).isAir();
        });
        check("lag: helper refuses a click that would land next door", refused, "planks placed above");
        boolean normal = world.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.gameMode.useItemOn(p, level, new ItemStack(Items.OAK_PLANKS), net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(spot).add(0, 0.5, 0), Direction.UP, spot, false));
            return level.getBlockState(above).is(Blocks.OAK_PLANKS);
        });
        check("lag: a normal click on top still works", normal, "not placed");

        context.runOnClient(client -> DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic));
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, Blocks.AIR.defaultBlockState(), f);
        });
    }

    /** Survival, no stone in the inventory, only a shulker box full of it: Take It Out pulls stacks and the build finishes. */
    private void takeItOut(ClientGameTestContext context, TestSingleplayerContext world, BlockPos o) {
        BlockPos max = o.offset(6, 2, 6);
        int f = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        List<BlockPos> wanted = List.of(o.offset(1, 1, 1), o.offset(3, 1, 1), o.offset(5, 1, 1), o.offset(1, 1, 3), o.offset(3, 1, 3));
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, p.getY() == o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), f);
            for (BlockPos p : wanted) level.setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), f);
        });
        LitematicaSchematic schematic = world.getServer().computeOnServer(server -> capture(server, o, max, "takeitout"));
        world.getServer().runOnServer(server -> clear(server, o, max));
        world.getServer().runCommand("gamemode survival @a");
        world.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            Inventory inv = p.getInventory();
            inv.clearContent();
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 64), new ItemStack(Items.GOLD_BLOCK, 64))));
            inv.setItem(20, box);
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), o.getX() + 3.5, o.getY() + 3, o.getZ() + 2.5, Set.of(), 0f, 60f, true);
        });
        context.runOnClient(client -> {
            Configs.USE_SERVER_HELPER.setBooleanValue(false);
            Configs.RESTOCK.setBooleanValue(true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, o, "printer-takeitout", true, true), false);
        });
        context.waitTicks(10);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(true));
        for (int i = 0; i < 4; i++) settle(context);
        context.runOnClient(client -> Configs.PRINTING.setBooleanValue(false));
        int placed = world.getServer().computeOnServer(server -> {
            int n = 0;
            for (BlockPos p : wanted) if (server.overworld().getBlockState(p).is(Blocks.GOLD_BLOCK)) n++;
            return n;
        });
        check("take it out: gold pulled from the shulker box and placed", placed == wanted.size(), placed + "/" + wanted.size());
        context.runOnClient(client -> DataManager.getSchematicPlacementManager().removeAllPlacementsOfSchematic(schematic));
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : BlockPos.betweenClosed(o, max)) level.setBlock(p, Blocks.AIR.defaultBlockState(), f);
        });
        world.getServer().runCommand("gamemode creative @a");
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

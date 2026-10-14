package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.ServerHelper;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.mixin.MultiPlayerGameModeAccessor;
import dev.steelaspect.cytraprinter.net.IntentPayload;
import dev.steelaspect.cytraprinter.state.StateRules;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The printer loop, run at the end of every client tick. It looks at every position in reach, compares the
 * schematic with the world and then, in this order: places missing blocks (bottom layer first, held item first,
 * nearest first), clicks blocks whose setting is off (repeater delay etc.), and mines wrong blocks.
 *
 * <p>Nothing is clicked unless {@link PlacementSolver} has proven vanilla will place the exact target state, or
 * the server helper is there to set it. A position the printer touched is left alone until the server has
 * answered, and one that keeps failing is parked for a while instead of being retried forever.
 */
public final class Printer {
    private enum Kind { PLACE, CLICK, POT, BREAK }

    private record Job(Kind kind, BlockPos pos, BlockState target, BlockState world, Item item, double dist) {
    }

    private static final int MAX_CLICKS_PER_TICK = 48;
    private static final int STUCK_TICKS = 600;
    private static final long SOLVE_BUDGET_NANOS = 4_000_000L;
    private static final int MINING_TIMEOUT_TICKS = 200;

    /** -Dcytraprinter.debug=true: log every spot the solver can't solve, with its neighbours. */
    private static final boolean DEBUG = Boolean.getBoolean("cytraprinter.debug");

    private static long ticks;
    private static int delay;
    /** Position -> tick before which the printer doesn't touch it again (waiting for the server). */
    private static final Long2LongOpenHashMap waitUntil = new Long2LongOpenHashMap();
    /** Position -> attempts * 2^32 + tick of the last attempt. */
    private static final Long2LongOpenHashMap attempts = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap stuckUntil = new Long2LongOpenHashMap();
    /** State id the printer left at a position after acting on it, to tell progress from a server revert. */
    private static final Long2IntOpenHashMap lastAfter = new Long2IntOpenHashMap();
    /** Signs placed by the printer -> tick, for filling in their text. */
    private static final Long2LongOpenHashMap placedSigns = new Long2LongOpenHashMap();

    private static BlockPos mining;
    private static long miningStart;

    private Printer() {
    }

    public static void reset() {
        Look.reset();
        waitUntil.clear();
        attempts.clear();
        stuckUntil.clear();
        lastAfter.clear();
        placedSigns.clear();
        mining = null;
        Stats.reset();
    }

    public static boolean wasPlacedByPrinter(BlockPos pos) {
        long t = placedSigns.getOrDefault(pos.asLong(), Long.MIN_VALUE);
        return t != Long.MIN_VALUE && ticks - t < 100;
    }

    public static void forgetSign(BlockPos pos) {
        placedSigns.remove(pos.asLong());
    }

    public static void tick(Minecraft mc) {
        ticks++;
        LocalPlayer player = mc.player;
        if (!Configs.PRINTING.getBooleanValue() || player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            stopMining(mc);
            Look.unlock(mc);
            return;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || player.isSpectator()) {
            Look.unlock(mc);
            return;
        }
        Look.lock(player);
        Look.tick();
        if (mc.screen != null || player.isUsingItem()) return;
        if (ticks % 200 == 0) prune();
        if (delay > 0) {
            delay--;
            return;
        }
        delay = Configs.TICK_DELAY.getIntegerValue();

        // Survival mining in progress: keep at it, nothing else changes the held item meanwhile.
        if (mining != null && continueMining(mc)) return;

        List<Job> jobs = scan(mc, schematic);
        int placed = 0;
        headVotes.clear();
        keepHead = false;
        try {
            placed = place(mc, schematic, jobs);
            click(mc, jobs, placed);
        } finally {
            Look.finish(mc, nextHead(player));
        }
        if (placed == 0) breakBlocks(mc, jobs);
    }

    public static void onTeleport() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) Look.onTeleport(mc.player);
    }

    public static boolean rotationLocked() {
        return Look.locked();
    }

    /** Head yaws wanted by blocks that can't be placed with the current one, and whether a block still needs the current one. */
    private static final java.util.Map<Float, Integer> headVotes = new java.util.HashMap<>();
    private static boolean keepHead;
    private static final int HEAD_LOOKUPS_PER_TICK = 6;

    /**
     * Stay on the current head yaw while something still needs it, otherwise turn to the most wanted one. With
     * nothing waiting the server follows the player's real yaw, so blocks placed by hand meanwhile face right.
     */
    private static float nextHead(LocalPlayer player) {
        if (keepHead) return Look.headYaw;
        if (headVotes.isEmpty()) return player.getYRot();
        float best = Look.headYaw;
        int bestVotes = 0;
        for (java.util.Map.Entry<Float, Integer> e : headVotes.entrySet()) {
            if (e.getValue() > bestVotes) {
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- scanning

    private static List<Job> scan(Minecraft mc, WorldSchematic schematic) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        LayerRange range = DataManager.getRenderLayerRange();
        double extra = Configs.EXTRA_REACH.getDoubleValue();
        int r = (int) Math.ceil(player.blockInteractionRange() + extra) + 1;
        Vec3 eye = player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        Stats.beginScan(ticks);
        List<Job> jobs = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isInsideBuildHeight(m.getY()) || !player.isWithinBlockInteractionRange(m, extra)) continue;
                    if (!range.isPositionWithinRange(m)) continue;
                    Job job = classify(mc, schematic, m.immutable(), eye);
                    if (job == null) continue;
                    long key = job.pos().asLong();
                    if (stuckUntil.getOrDefault(key, 0L) > ticks) {
                        Stats.stuck++;
                        continue;
                    }
                    if (waitUntil.getOrDefault(key, 0L) > ticks) continue;
                    jobs.add(job);
                }
            }
        }
        return jobs;
    }

    private static Job classify(Minecraft mc, WorldSchematic schematic, BlockPos pos, Vec3 eye) {
        ClientLevel level = mc.level;
        BlockState target = schematic.getBlockState(pos);
        BlockState world = level.getBlockState(pos);
        if (target == world) {
            attempts.remove(pos.asLong());
            return null;
        }
        double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));

        if (target.isAir()) {
            if (!Configs.CLEAR_EXTRA_BLOCKS.getBooleanValue() || world.isAir() || world.getBlock() instanceof LiquidBlock) return null;
            if (!insidePlacement(pos)) return null;
            Stats.wrong++;
            return new Job(Kind.BREAK, pos, target, world, null, dist);
        }
        // Never liquids; the upper halves of doors/tall plants and bed heads come with the other half.
        if (target.getBlock() instanceof LiquidBlock || target.is(Blocks.BUBBLE_COLUMN)) return null;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return null;
        if (target.hasProperty(BlockStateProperties.BED_PART) && target.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return null;

        // Potted plants: place the pot, then put the plant in.
        if (target.getBlock() instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            if (world.is(target.getBlock())) return null;
            if (world.is(Blocks.FLOWER_POT)) {
                Stats.remaining++;
                return new Job(Kind.POT, pos, target, world, pot.getPotted().asItem(), dist);
            }
            target = Blocks.FLOWER_POT.defaultBlockState();
        }

        if (StateRules.placedCorrectly(world, target)) {
            if (Configs.CLICK_FIX_STATES.getBooleanValue() && clicksNeeded(world, target) > 0) {
                Stats.remaining++;
                return new Job(Kind.CLICK, pos, target, world, null, dist);
            }
            attempts.remove(pos.asLong());
            return null;
        }
        Item item = target.getBlock().asItem();
        if (!(item instanceof BlockItem)) return null;
        if (StateRules.canGrowInto(world, target) || world.canBeReplaced()) {
            Stats.remaining++;
            return new Job(Kind.PLACE, pos, target, world, item, dist);
        }
        Stats.wrong++;
        return Configs.FIX_WRONG_BLOCKS.getBooleanValue() ? new Job(Kind.BREAK, pos, target, world, null, dist) : null;
    }

    private static boolean insidePlacement(BlockPos pos) {
        for (SchematicPlacementManager.PlacementPart part : DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos)) {
            if (part.getPlacement().isEnabled() && part.getBox().containsPos(pos)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- placing

    private static int place(Minecraft mc, WorldSchematic schematic, List<Job> jobs) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        Item held = player.getMainHandItem().getItem();
        List<Job> places = new ArrayList<>();
        for (Job j : jobs) if (j.kind() == Kind.PLACE) places.add(j);
        places.sort(Comparator.<Job>comparingInt(j -> j.pos().getY())
                .thenComparingInt(j -> j.item() == held ? 0 : 1)
                .thenComparing(j -> j.item().getDescriptionId())
                .thenComparingDouble(Job::dist));

        boolean helper = ServerHelper.active();
        int budget = Configs.PLACEMENTS_PER_TICK.getIntegerValue();
        int placed = 0;
        long deadline = System.nanoTime() + SOLVE_BUDGET_NANOS;
        List<Item> missing = new ArrayList<>();
        float head = Look.headYaw;
        boolean settled = Look.headSettled();
        int headLookups = 0;
        for (Job job : places) {
            if (placed >= budget || System.nanoTime() > deadline) break;
            BlockPos pos = job.pos();
            BlockState target = job.target();
            // Earlier clicks this tick may have changed the spot (door tops, bed heads, a grown slab).
            BlockState world = level.getBlockState(pos);
            if (!world.equals(job.world())) continue;
            if (isHeld(level, schematic, pos, target)) {
                Stats.held++;
                continue;
            }
            if (target.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
                Stats.held++;
                continue;
            }
            // Its support (wall, ceiling, floor) isn't built yet: wait instead of placing some other variant.
            if (!target.canSurvive(level, pos)) {
                Stats.held++;
                continue;
            }
            if (!Hotbar.has(player, job.item())) {
                Stats.missing.merge(job.item(), 1, Integer::sum);
                if (!missing.contains(job.item())) missing.add(job.item());
                continue;
            }
            BlockPos partner = StateRules.chestPartner(pos, target);
            boolean partnerPresent = partner != null && level.getBlockState(partner).is(target.getBlock());
            BlockItem item = (BlockItem) job.item();
            PlacePlan plan = PlacementSolver.solve(level, player, pos, world, target, item, partnerPresent, true, head);
            if (plan != null && !settled && !PlacementSolver.headIndependent(level, player, plan, world, target, item, partnerPresent)) {
                // Right only with the head on this yaw, and the server's head may not be there yet.
                keepHead = true;
                Stats.held++;
                continue;
            }
            if (plan != null && settled && !helper) keepHead |= !PlacementSolver.headIndependent(level, player, plan, world, target, item, partnerPresent);
            if (plan == null && !helper && headLookups < HEAD_LOOKUPS_PER_TICK) {
                headLookups++;
                float want = PlacementSolver.headYawFor(level, player, pos, world, target, item, partnerPresent, head);
                if (!Float.isNaN(want)) {
                    headVotes.merge(want, 1, Integer::sum);
                    Stats.held++;
                    continue;
                }
            }
            if (plan == null && helper) plan = PlacementSolver.solve(level, player, pos, world, target, item, partnerPresent, false, head);
            if (plan == null) {
                if (DEBUG) debugUnsolvable(level, pos, world, target);
                Stats.unsolvable++;
                waitUntil.put(pos.asLong(), ticks + 20);
                continue;
            }
            if (!Hotbar.select(mc, item)) continue;
            if (helper) ClientPlayNetworking.send(new IntentPayload(pos, target));
            use(mc, plan);
            touched(mc, pos, world, target);
            if (target.getBlock() instanceof SignBlock) placedSigns.put(pos.asLong(), ticks);
            Stats.placed(ticks);
            placed++;
        }
        if (!missing.isEmpty() && Configs.RESTOCK.getBooleanValue() && !player.hasInfiniteMaterials()) Restock.request(missing);
        return placed;
    }

    /**
     * Safe redstone order: observers, pistons, dispensers and droppers wait until the blocks around them are done,
     * so they don't fire or push while the build is half finished. An observer always waits for the block it
     * watches; other neighbours that are themselves such components don't hold it back.
     */
    private static boolean isHeld(ClientLevel level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (!Configs.SAFE_REDSTONE_ORDER.getBooleanValue() || !isSensitive(target)) return false;
        LayerRange range = DataManager.getRenderLayerRange();
        boolean observer = target.getBlock() instanceof ObserverBlock;
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState want = schematic.getBlockState(n);
            if (want.isAir() || want.getBlock() instanceof LiquidBlock || !range.isPositionWithinRange(n)) continue;
            boolean watched = observer && dir == target.getValue(BlockStateProperties.FACING);
            if (!watched && isSensitive(want)) continue;
            BlockState have = level.getBlockState(n);
            if (!StateRules.placedCorrectly(have, want)) {
                // Upper halves are placed with their lower half.
                if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
                return true;
            }
            if (Configs.CLICK_FIX_STATES.getBooleanValue() && clicksNeeded(have, want) > 0) return true;
        }
        return false;
    }

    private static boolean isSensitive(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ObserverBlock || b instanceof PistonBaseBlock || b instanceof DispenserBlock;
    }

    /** Clicks the block with the current main hand item, with the plan's look and sneak, predicting it locally. */
    private static void use(Minecraft mc, PlacePlan plan) {
        LocalPlayer player = mc.player;
        Look.apply(mc, plan.yaw(), plan.pitch(), plan.sneak());
        float yaw = player.getYRot(), pitch = player.getXRot(), yawO = player.yRotO, pitchO = player.xRotO;
        float head = player.yHeadRot, headO = player.yHeadRotO;
        Input input = player.input.keyPresses;
        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());
        player.yRotO = plan.yaw();
        player.xRotO = plan.pitch();
        player.yHeadRot = plan.headYaw();
        player.yHeadRotO = plan.headYaw();
        player.input.keyPresses = new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), plan.sneak(), input.sprint());
        try {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hitResult());
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
            player.yHeadRot = head;
            player.yHeadRotO = headO;
            player.input.keyPresses = input;
        }
    }

    // ---------------------------------------------------------------- clicking settings and pots

    private static void click(Minecraft mc, List<Job> jobs, int placedThisTick) {
        LocalPlayer player = mc.player;
        int clicks = 0;
        int potBudget = Math.max(0, Configs.PLACEMENTS_PER_TICK.getIntegerValue() - placedThisTick);
        for (Job job : jobs) {
            if (clicks >= MAX_CLICKS_PER_TICK) break;
            if (job.kind() == Kind.POT) {
                if (potBudget <= 0 || !Hotbar.has(player, job.item())) continue;
                if (!Hotbar.select(mc, job.item())) continue;
                BlockPos pos = job.pos();
                use(mc, new PlacePlan(pos, Direction.UP, Vec3.atCenterOf(pos).add(0, 0.3, 0), Look.headYaw, player.getXRot(), Look.headYaw, false));
                touched(mc, pos, job.world(), job.target());
                potBudget--;
                clicks++;
            } else if (job.kind() == Kind.CLICK) {
                BlockState world = mc.level.getBlockState(job.pos());
                int n = clicksNeeded(world, job.target());
                if (n <= 0 || clicks + n > MAX_CLICKS_PER_TICK) continue;
                if (isHeldClick(mc, job)) continue;
                PlacePlan plan = clickPlan(player, job.pos(), world);
                for (int i = 0; i < n; i++) use(mc, plan);
                touched(mc, job.pos(), world, job.target());
                clicks += n;
            }
        }
    }

    /** Levers wait until nothing else in reach is left to place (they switch things on). */
    private static boolean isHeldClick(Minecraft mc, Job job) {
        return job.world().getBlock() instanceof LeverBlock && Stats.remaining > 1;
    }

    /** Click on the side facing the player; a fence gate is clicked while facing its own direction so it opens the same way. */
    private static PlacePlan clickPlan(LocalPlayer player, BlockPos pos, BlockState world) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 toEye = player.getEyePosition().subtract(center);
        Direction face = Direction.getApproximateNearest(toEye.x, toEye.y, toEye.z);
        if (face == Direction.UP && world.getBlock() instanceof NoteBlock) face = player.getDirection().getOpposite();
        Vec3 hit = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        float yaw = Look.headYaw;
        if (world.getBlock() instanceof FenceGateBlock) yaw = world.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        return new PlacePlan(pos, face, hit, yaw, player.getXRot(), Look.headYaw, false);
    }

    /** Right-clicks needed to get the click-settable property from {@code world} to {@code target}; 0 = none / not clickable. */
    static int clicksNeeded(BlockState world, BlockState target) {
        Block b = world.getBlock();
        if (b != target.getBlock()) return 0;
        Property<?> p;
        if (b instanceof RepeaterBlock) p = BlockStateProperties.DELAY;
        else if (b instanceof ComparatorBlock) p = BlockStateProperties.MODE_COMPARATOR;
        else if (b instanceof NoteBlock) p = BlockStateProperties.NOTE;
        else if (b instanceof DaylightDetectorBlock) p = BlockStateProperties.INVERTED;
        else if (b instanceof LeverBlock && Configs.CLICK_FIX_LEVERS.getBooleanValue()) p = BlockStateProperties.POWERED;
        else if (DoorBlock.isWoodenDoor(world) || (b instanceof TrapDoorBlock && !world.is(Blocks.IRON_TRAPDOOR)) || b instanceof FenceGateBlock) {
            // A powered door/gate/trapdoor would just switch back.
            if (world.hasProperty(BlockStateProperties.POWERED) && world.getValue(BlockStateProperties.POWERED)) return 0;
            p = BlockStateProperties.OPEN;
        } else return 0;
        if (!world.hasProperty(p) || !target.hasProperty(p)) return 0;
        return cycles(p, world, target);
    }

    private static <T extends Comparable<T>> int cycles(Property<T> p, BlockState from, BlockState to) {
        List<T> values = new ArrayList<>(p.getPossibleValues());
        int a = values.indexOf(from.getValue(p)), b = values.indexOf(to.getValue(p));
        if (a < 0 || b < 0) return 0;
        return Math.floorMod(b - a, values.size());
    }

    // ---------------------------------------------------------------- breaking

    private static void breakBlocks(Minecraft mc, List<Job> jobs) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        List<Job> breaks = new ArrayList<>();
        for (Job j : jobs) if (j.kind() == Kind.BREAK && canBreak(level, player, j.pos(), j.world())) breaks.add(j);
        if (breaks.isEmpty()) return;
        // Top first, so nothing above loses its support, then nearest.
        breaks.sort(Comparator.<Job>comparingInt(j -> -j.pos().getY()).thenComparingDouble(Job::dist));
        if (player.getAbilities().instabuild) {
            int budget = Configs.PLACEMENTS_PER_TICK.getIntegerValue();
            for (Job job : breaks) {
                if (budget-- <= 0) break;
                mc.gameMode.startDestroyBlock(job.pos(), face(player, job.pos()));
                touched(mc, job.pos(), job.world(), null);
            }
            return;
        }
        Job job = breaks.get(0);
        Hotbar.selectTool(mc, job.world());
        mining = job.pos();
        miningStart = ticks;
        ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$setDestroyDelay(0);
        mc.gameMode.startDestroyBlock(mining, face(player, mining));
        if (level.getBlockState(mining).isAir() || !level.getBlockState(mining).equals(job.world())) finishMining(mc);
    }

    private static boolean canBreak(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
        if (state.getDestroySpeed(level, pos) < 0 || state.getBlock() instanceof LiquidBlock) return false;
        if (!Configs.BREAK_CONTAINERS.getBooleanValue() && level.getBlockEntity(pos) instanceof Container) return false;
        return !player.blockActionRestricted(level, pos, player.gameMode());
    }

    /** True while the current block is still being mined (this tick is spent on it). */
    private static boolean continueMining(Minecraft mc) {
        LocalPlayer player = mc.player;
        BlockState state = mc.level.getBlockState(mining);
        if (state.isAir() || !player.isWithinBlockInteractionRange(mining, Configs.EXTRA_REACH.getDoubleValue())
                || ticks - miningStart > MINING_TIMEOUT_TICKS || !Configs.FIX_WRONG_BLOCKS.getBooleanValue() && !Configs.CLEAR_EXTRA_BLOCKS.getBooleanValue()) {
            if (!state.isAir()) mc.gameMode.stopDestroyBlock();
            finishMining(mc);
            return false;
        }
        ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$setDestroyDelay(0);
        mc.gameMode.continueDestroyBlock(mining, face(player, mining));
        if (mc.level.getBlockState(mining).isAir()) finishMining(mc);
        return true;
    }

    private static void finishMining(Minecraft mc) {
        if (mining != null) touched(mc, mining, null, null);
        mining = null;
    }

    private static void stopMining(Minecraft mc) {
        if (mining != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        mining = null;
    }

    private static Direction face(LocalPlayer player, BlockPos pos) {
        Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    private static void debugUnsolvable(ClientLevel level, BlockPos pos, BlockState world, BlockState target) {
        StringBuilder sb = new StringBuilder();
        for (Direction d : Direction.values()) sb.append(' ').append(d.getName()).append('=').append(level.getBlockState(pos.relative(d)));
        dev.steelaspect.cytraprinter.CytraPrinter.LOGGER.info("[debug] can't solve {} at {} (world {}):{}", target, pos.toShortString(), world, sb);
    }

    // ---------------------------------------------------------------- bookkeeping

    /**
     * The printer acted on pos: wait for the server's answer, and park the spot if it keeps coming back. Adding to
     * a block (another snow layer, candle, slab half) whose previous step stuck counts as progress, not a retry.
     */
    private static void touched(Minecraft mc, BlockPos pos, BlockState before, BlockState target) {
        long key = pos.asLong();
        waitUntil.put(key, ticks + confirmTicks(mc));
        long packed = attempts.getOrDefault(key, 0L);
        if (before != null && target != null && before.getBlock() == target.getBlock()
                && lastAfter.getOrDefault(key, -1) == Block.getId(before)) packed = 0;
        lastAfter.put(key, Block.getId(mc.level.getBlockState(pos)));
        int count = (int) (packed >>> 32) + 1;
        if (count > Configs.MAX_RETRIES.getIntegerValue() * 2) {
            stuckUntil.put(key, ticks + STUCK_TICKS);
            attempts.remove(key);
        } else {
            attempts.put(key, ((long) count << 32) | (ticks & 0xFFFFFFFFL));
        }
    }

    /** Ticks to wait for the server: about two pings plus a little, 3 to 40 ticks. */
    private static int confirmTicks(Minecraft mc) {
        int ping = 0;
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = info.getLatency();
        }
        return Math.max(3, Math.min(40, (int) Math.ceil(ping * 2.0 / 50.0) + 3));
    }

    private static void prune() {
        waitUntil.long2LongEntrySet().removeIf(e -> e.getLongValue() < ticks);
        stuckUntil.long2LongEntrySet().removeIf(e -> e.getLongValue() < ticks);
        placedSigns.long2LongEntrySet().removeIf(e -> ticks - e.getLongValue() > 200);
        attempts.long2LongEntrySet().removeIf(e -> ticks - (e.getLongValue() & 0xFFFFFFFFL) > 1200);
        lastAfter.keySet().removeIf(k -> !attempts.containsKey(k));
    }
}

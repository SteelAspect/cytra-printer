package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.ClientHooks;
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
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.TntBlock;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private enum Kind { PLACE, CLICK, POT, BREAK, STRIP, COMPOST, BEDROCK }

    private record Job(Kind kind, BlockPos pos, BlockState target, BlockState world, Item item, double dist) {
    }

    private static final int MAX_CLICKS_PER_TICK = 48;
    private static final int STUCK_TICKS = 600;
    /** Placement search time per tick; the rest waits for the next tick so frames stay smooth. */
    private static final long SOLVE_BUDGET_NANOS = 2_000_000L;
    /** How long a spot found unplaceable is believed without searching again, if nothing around it changed. */
    private static final int UNSOLVABLE_MEMO_TICKS = 200;
    private static final int MINING_TIMEOUT_TICKS = 200;
    /** The server sends the time every second; this long without it means it is lagging. */
    private static final long LAG_MILLIS = 2000;

    private static final IdFilter<Block> EXCLUDED = IdFilter.blocks();
    private static final IdFilter<Block> PROTECTED = IdFilter.blocks();
    private static final IdFilter<Block> BEDROCK = IdFilter.blocks();
    private static final IdFilter<Item> COMPOST_ITEMS = IdFilter.items();

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
    /**
     * Spots the full search found unplaceable, with what it depended on: the spot and its neighbours, where the
     * player stood and the head yaw. Searching again is skipped until one of those changes (or the memo runs out),
     * because a failed search is the most expensive thing the printer does.
     */
    private record Unsolved(int around, double x, double y, double z, float head, long until) {
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Unsolved> unsolved = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /** A search that ran out of time this tick: which attempt and step to resume at, if nothing changed. */
    private record Partial(int around, double x, double y, double z, float head, int attempt, int unit) {
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Partial> partial = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

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
        unsolved.clear();
        partial.clear();
        mining = null;
        BedrockBreaker.reset();
        Stats.reset();
        Skipped.clear();
    }

    /** Retry: forgets parked and failed spots, so the printer tries everything in reach again right away. */
    public static void retrySkipped() {
        waitUntil.clear();
        attempts.clear();
        stuckUntil.clear();
        lastAfter.clear();
        unsolved.clear();
        partial.clear();
        Skipped.clear();
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
        if (Configs.PRINTING.getBooleanValue() && player != null && Configs.DISABLE_ON_DEATH.getBooleanValue() && player.isDeadOrDying()) {
            Configs.PRINTING.setBooleanValue(false);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("cytraprinter.msg.off_death"), false);
        }
        if (!Configs.PRINTING.getBooleanValue() || player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            stopMining(mc);
            BedrockBreaker.reset();
            Look.unlock(mc);
            return;
        }
        Skipped.checkLevel(mc.level);
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null || player.isSpectator()) {
            Look.unlock(mc);
            return;
        }
        Look.lock(player);
        Look.tick();
        if (mc.screen != null || player.isUsingItem()) return;
        Stats.lagging = Configs.PAUSE_ON_LAG.getBooleanValue() && System.currentTimeMillis() - ClientHooks.lastServerTimeMillis > LAG_MILLIS;
        if (Stats.lagging) return;
        if (ticks % 200 == 0) {
            prune();
            Skipped.prune(mc.level, schematic);
        }
        if (delay > 0) {
            delay--;
            return;
        }
        delay = Configs.TICK_DELAY.getIntegerValue();

        // Survival mining in progress: keep at it, nothing else changes the held item meanwhile.
        if (mining != null && continueMining(mc)) return;
        // Bedrock breaking in progress: it owns the tick until its setup is cleared away again.
        if (BedrockBreaker.active()) {
            try {
                BedrockBreaker.tick(mc, ticks);
            } finally {
                Look.finish(mc, Look.headYaw);
            }
            return;
        }

        long t0 = System.nanoTime();
        List<Job> jobs = scan(mc, schematic);
        long t1 = System.nanoTime();
        int placed = 0;
        headVotes.clear();
        keepHead = false;
        try {
            placed = place(mc, schematic, jobs);
            click(mc, jobs, placed);
        } finally {
            Look.finish(mc, nextHead(player));
        }
        long t2 = System.nanoTime();
        if (placed == 0) {
            breakBlocks(mc, jobs);
            if (mining == null) startBedrock(mc, jobs);
        }
        Stats.timing(t1 - t0, t2 - t1, System.nanoTime() - t0);
    }

    public static void onUseItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.getConnection() != null) Look.syncReal(mc);
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

    /**
     * Adds the block under the crosshair to the excluded list, or takes it off again. Schematic blocks count
     * (the ghost block you look at), otherwise the real block.
     */
    public static void toggleExcludeLookedAt(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double reach = player.blockInteractionRange() + Configs.EXTRA_REACH.getDoubleValue();
        Block found = null;
        for (double d = 0; d <= reach && found == null; d += 0.05) {
            BlockPos p = BlockPos.containing(eye.add(look.scale(d)));
            BlockState s = schematic != null ? schematic.getBlockState(p) : Blocks.AIR.defaultBlockState();
            if (!s.isAir() && !(s.getBlock() instanceof LiquidBlock)) found = s.getBlock();
            else {
                BlockState w = mc.level.getBlockState(p);
                if (!w.isAir() && !(w.getBlock() instanceof LiquidBlock)) found = w.getBlock();
            }
        }
        if (found == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("cytraprinter.msg.exclude_none"), true);
            return;
        }
        String id = IdFilter.idOf(found);
        String full = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(found).toString();
        List<String> list = new ArrayList<>(Configs.EXCLUDED_BLOCKS.getStrings());
        boolean removed = list.removeIf(e -> e.trim().equalsIgnoreCase(id) || e.trim().equalsIgnoreCase(full));
        if (!removed) list.add(id);
        Configs.EXCLUDED_BLOCKS.setStrings(list);
        Configs.INSTANCE.save();
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                removed ? "cytraprinter.msg.included" : "cytraprinter.msg.excluded", found.getName()), true);
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
        var pending = pendingPredictions(level);
        List<Job> jobs = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isInsideBuildHeight(m.getY()) || !player.isWithinBlockInteractionRange(m, extra)) continue;
                    if (!range.isPositionWithinRange(m)) continue;
                    // Our own break or placement here isn't confirmed by the server yet: what the client shows may
                    // not be what the server has, and a click into a spot the server still has a block in would put
                    // the block next to it instead. Wait for the server's answer, however long it lags.
                    if (pending != null && pending.containsKey(m.asLong())) continue;
                    BlockPos pos = m.immutable();
                    int wrongBefore = Stats.wrong;
                    Job job = classify(mc, schematic, pos, eye);
                    boolean wrong = Stats.wrong > wrongBefore;
                    if (job == null) {
                        // Wrong and not turned into a job: the printer won't fix it.
                        if (wrong) Skipped.mark(pos, Skipped.Kind.WRONG);
                        else Skipped.forget(pos);
                        continue;
                    }
                    long key = job.pos().asLong();
                    if (stuckUntil.getOrDefault(key, 0L) > ticks) {
                        Stats.stuck++;
                        Skipped.mark(pos, Skipped.Kind.STUCK);
                        continue;
                    }
                    if (job.kind() == Kind.BREAK && !canBreak(level, player, pos, job.world())) {
                        Skipped.mark(pos, Skipped.Kind.WRONG);
                    } else if (waitUntil.getOrDefault(key, 0L) <= ticks) {
                        // About to be tried again; place() marks it again if it still can't be placed.
                        Skipped.forget(pos);
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
        // Excluded blocks: the printer leaves their spots alone, missing or wrong.
        if (!target.isAir() && EXCLUDED.matches(Configs.EXCLUDED_BLOCKS.getStrings(), target.getBlock())) return null;

        if (target.isAir()) {
            if (!Configs.CLEAR_EXTRA_BLOCKS.getBooleanValue() || world.isAir() || world.getBlock() instanceof LiquidBlock) return null;
            if (!insidePlacement(pos)) return null;
            Stats.wrong++;
            if (isBedrock(world)) return bedrockJob(mc, pos, target, world, dist);
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

        // Composter fill level: fill it up to the level (it turns from 7 to 8 by itself), empty it if it's ready.
        if (Configs.COMPOSTER_LEVELS.getBooleanValue() && target.is(Blocks.COMPOSTER) && world.is(Blocks.COMPOSTER)) {
            int want = target.getValue(ComposterBlock.LEVEL), have = world.getValue(ComposterBlock.LEVEL);
            if (want == have) {
                attempts.remove(pos.asLong());
                return null;
            }
            if (have == ComposterBlock.READY || have < Math.min(want, ComposterBlock.MAX_LEVEL)) {
                Stats.remaining++;
                return new Job(Kind.COMPOST, pos, target, world, null, dist);
            }
            if (have == ComposterBlock.MAX_LEVEL && want == ComposterBlock.READY) {
                Stats.held++;
                return null;
            }
            Stats.wrong++;
            return Configs.FIX_WRONG_BLOCKS.getBooleanValue() && !isProtected(world) ? new Job(Kind.BREAK, pos, target, world, null, dist) : null;
        }

        // Coral: live coral without water dies in a few seconds, so it waits for water (or is left to die when the
        // schematic wants the dead one).
        Block deadForm = Variants.dead(target.getBlock());
        if (deadForm != null && Configs.CORAL_WAIT_FOR_WATER.getBooleanValue() && !Variants.coralSurvives(level, pos)
                && (world.canBeReplaced() || world.is(deadForm))) {
            Stats.held++;
            return null;
        }
        Block aliveForm = Variants.alive(target.getBlock());
        if (aliveForm != null && world.is(aliveForm) && !Variants.coralSurvives(level, pos)
                && StateRules.placedCorrectly(world, Variants.copyProperties(target, aliveForm))) {
            Stats.held++;
            return null;
        }

        // Stripped logs: the plain log is there the right way round, so strip it with an axe.
        Block log = Variants.unstripped(target.getBlock());
        if (log != null && Configs.STRIP_LOGS.getBooleanValue() && world.is(log)
                && StateRules.placedCorrectly(world, Variants.copyProperties(target, log))) {
            Stats.remaining++;
            return new Job(Kind.STRIP, pos, target, world, null, dist);
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
            BlockState place = world.canBeReplaced() ? substitute(mc, pos, target) : target;
            return new Job(Kind.PLACE, pos, place, world, place.getBlock().asItem(), dist);
        }
        Stats.wrong++;
        if (isBedrock(world)) return bedrockJob(mc, pos, target, world, dist);
        return Configs.FIX_WRONG_BLOCKS.getBooleanValue() && !isProtected(world) ? new Job(Kind.BREAK, pos, target, world, null, dist) : null;
    }

    /**
     * What to place for the target when its own item isn't in the inventory: the plain log (stripped afterwards)
     * or live coral (dies into the dead one). Creative always places the target itself.
     */
    private static BlockState substitute(Minecraft mc, BlockPos pos, BlockState target) {
        LocalPlayer player = mc.player;
        Block b = target.getBlock();
        if (player.hasInfiniteMaterials() || Hotbar.carries(player, b.asItem())) return target;
        Block log = Variants.unstripped(b);
        if (log != null && Configs.STRIP_LOGS.getBooleanValue() && Hotbar.carries(player, log.asItem()) && Hotbar.any(player, Printer::isAxe)) {
            return Variants.copyProperties(target, log);
        }
        Block alive = Variants.alive(b);
        if (alive != null && Configs.LIVE_CORAL_FOR_DEAD.getBooleanValue() && Hotbar.carries(player, alive.asItem()) && !Variants.coralSurvives(mc.level, pos)) {
            return Variants.copyProperties(target, alive);
        }
        return target;
    }

    private static boolean isAxe(ItemStack s) {
        return s.getItem() instanceof AxeItem && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2);
    }

    /** A block on the bedrock list while bedrock breaking is on. */
    static boolean isBedrock(BlockState state) {
        return Configs.BREAK_BEDROCK.getBooleanValue() && !state.isAir() && BEDROCK.matches(Configs.BEDROCK_BLOCKS.getStrings(), state.getBlock());
    }

    private static boolean isProtected(BlockState state) {
        return PROTECTED.matches(Configs.PROTECTED_BLOCKS.getStrings(), state.getBlock());
    }

    /** Creative breaks bedrock like anything else; survival needs the piston trick. */
    private static Job bedrockJob(Minecraft mc, BlockPos pos, BlockState target, BlockState world, double dist) {
        return new Job(mc.player.getAbilities().instabuild ? Kind.BREAK : Kind.BEDROCK, pos, target, world, null, dist);
    }

    /** HUD key for bedrock breaking: "active", or what it is waiting for; null when there is none in reach. */
    public static String bedrockStatus() {
        if (BedrockBreaker.active()) return "active";
        return BedrockBreaker.blockedBy;
    }

    /** Parks a spot for a while (bedrock that couldn't be broken). */
    static void giveUp(BlockPos pos) {
        stuckUntil.put(pos.asLong(), ticks + STUCK_TICKS);
    }

    private static void startBedrock(Minecraft mc, List<Job> jobs) {
        List<BlockPos> targets = new ArrayList<>();
        for (Job j : jobs) if (j.kind() == Kind.BEDROCK) targets.add(j.pos());
        if (targets.isEmpty()) {
            BedrockBreaker.blockedBy = null;
            return;
        }
        BedrockBreaker.start(mc, targets, ticks);
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
        long deadline = System.nanoTime() + SOLVE_BUDGET_NANOS;
        PlacementSolver.deadline = deadline;
        try {
            return placeJobs(mc, schematic, places, held, helper, budget, deadline);
        } finally {
            PlacementSolver.deadline = Long.MAX_VALUE;
        }
    }

    private static int placeJobs(Minecraft mc, WorldSchematic schematic, List<Job> places, Item held, boolean helper, int budget, long deadline) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        int placed = 0;
        List<Item> missing = new ArrayList<>();
        float head = Look.headYaw;
        boolean settled = Look.headSettled();
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
            int around = around(level, pos);
            Vec3 eye = player.getEyePosition();
            Unsolved memo = unsolved.get(pos.asLong());
            if (memo != null && memo.until() > ticks && memo.around() == around && Math.abs(memo.head() - head) < 1f
                    && eye.distanceToSqr(memo.x(), memo.y(), memo.z()) < 0.0625) {
                Stats.unsolvable++;
                Skipped.mark(pos, Skipped.Kind.UNSOLVABLE);
                continue;
            }
            // The searches for this spot, in order: the exact block at the current head yaw; then (vanilla) the other
            // cardinal head yaws, which become a vote for turning the head; or (helper) the right block in any
            // orientation. A search that runs out of time resumes here next tick where it stopped.
            float[] heads = helper ? new float[]{head, head} : otherHeads(head);
            Partial part = partial.get(pos.asLong());
            if (part != null && !(part.around() == around && Math.abs(part.head() - head) < 1f
                    && eye.distanceToSqr(part.x(), part.y(), part.z()) < 0.0625)) part = null;
            int attempt = part == null ? 0 : part.attempt();
            int skip = part == null ? 0 : part.unit();
            PlacePlan plan = null;
            boolean timedOut = false;
            for (; attempt < heads.length; attempt++, skip = 0) {
                boolean exact = !(helper && attempt == 1);
                PlacementSolver.timedOut = false;
                PlacePlan p = PlacementSolver.solve(level, player, pos, world, target, item, partnerPresent, exact, heads[attempt], skip);
                if (PlacementSolver.timedOut) {
                    partial.put(pos.asLong(), new Partial(around, eye.x, eye.y, eye.z, head, attempt, PlacementSolver.resumeUnit));
                    timedOut = true;
                    break;
                }
                if (p != null) {
                    plan = p;
                    break;
                }
            }
            // Out of search time: the rest waits for the next tick.
            if (timedOut) break;
            partial.remove(pos.asLong());
            if (plan != null && attempt > 0 && !helper) {
                // Placeable, but only with the head turned: vote for that yaw.
                headVotes.merge(heads[attempt], 1, Integer::sum);
                Stats.held++;
                continue;
            }
            if (plan != null && attempt == 0 && !settled && !PlacementSolver.headIndependent(level, player, plan, world, target, item, partnerPresent)) {
                // Right only with the head on this yaw, and the server's head may not be there yet.
                keepHead = true;
                Stats.held++;
                continue;
            }
            if (plan != null && attempt == 0 && settled && !helper) keepHead |= !PlacementSolver.headIndependent(level, player, plan, world, target, item, partnerPresent);
            if (plan == null) {
                unsolved.put(pos.asLong(), new Unsolved(around, eye.x, eye.y, eye.z, head, ticks + UNSOLVABLE_MEMO_TICKS));
                if (DEBUG) debugUnsolvable(level, pos, world, target);
                Stats.unsolvable++;
                Skipped.mark(pos, Skipped.Kind.UNSOLVABLE);
                waitUntil.put(pos.asLong(), ticks + 20);
                continue;
            }
            unsolved.remove(pos.asLong());
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
     * watches; other neighbours that are themselves such components don't hold it back. Safe observers add the
     * checks in {@link #observerUnsafe} and {@link #observerAimedHere}.
     */
    private static boolean isHeld(ClientLevel level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        if (!isSensitive(target)) return false;
        boolean observer = target.getBlock() instanceof ObserverBlock;
        if (Configs.SAFE_REDSTONE_ORDER.getBooleanValue()) {
            LayerRange range = DataManager.getRenderLayerRange();
            for (Direction dir : Direction.values()) {
                BlockPos n = pos.relative(dir);
                BlockState want = schematic.getBlockState(n);
                if (want.isAir() || want.getBlock() instanceof LiquidBlock || !range.isPositionWithinRange(n)) continue;
                boolean watched = observer && dir == target.getValue(BlockStateProperties.FACING);
                if (!watched && isSensitive(want)) continue;
                if (notDone(level, n, want)) return true;
            }
        }
        if (Configs.SAFE_OBSERVERS.getBooleanValue()) {
            return observer ? observerUnsafe(level, schematic, pos, target) : observerAimedHere(level, schematic, pos);
        }
        return false;
    }

    /** The schematic block at pos isn't there yet, or not set right. Upper halves don't count (placed with the lower). */
    private static boolean notDone(ClientLevel level, BlockPos pos, BlockState want) {
        BlockState have = level.getBlockState(pos);
        if (!StateRules.placedCorrectly(have, want)) {
            return !(want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER);
        }
        return Configs.CLICK_FIX_STATES.getBooleanValue() && clicksNeeded(have, want) > 0;
    }

    /**
     * An observer would fire on placement or soon after: the block it watches (and, for a line of observers watching
     * each other, every block down the line) isn't finished, or the watched block changes shape when the observer
     * appears next to it (walls, fences, panes, wire) while something behind the observer would react to the pulse.
     */
    private static boolean observerUnsafe(ClientLevel level, WorldSchematic schematic, BlockPos pos, BlockState target) {
        Direction facing = target.getValue(BlockStateProperties.FACING);
        BlockPos watched = pos.relative(facing);
        BlockPos cur = pos;
        BlockState curWant = target;
        for (int i = 0; i < 16; i++) {
            BlockPos next = cur.relative(curWant.getValue(BlockStateProperties.FACING));
            BlockState want = schematic.getBlockState(next);
            if (!want.isAir() && !(want.getBlock() instanceof LiquidBlock) && notDone(level, next, want)) return true;
            if (!(want.getBlock() instanceof ObserverBlock) || next.equals(pos)) break;
            cur = next;
            curWant = want;
        }
        BlockState have = level.getBlockState(watched);
        BlockState after = have.updateShape(level, level, watched, facing.getOpposite(), pos, target, level.getRandom());
        if (after.equals(have)) return false;
        BlockPos out = pos.relative(facing.getOpposite());
        BlockState outState = level.getBlockState(out);
        if (reactsToPower(outState)) return true;
        if (outState.isRedstoneConductor(level, out)) {
            for (Direction d : Direction.values()) {
                BlockPos n = out.relative(d);
                if (!n.equals(pos) && reactsToPower(level.getBlockState(n))) return true;
            }
        }
        return false;
    }

    /** A placed observer points its output at pos and still has an unfinished block in front: it will fire into pos. */
    private static boolean observerAimedHere(ClientLevel level, WorldSchematic schematic, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos o = pos.relative(dir);
            BlockState obs = level.getBlockState(o);
            if (!(obs.getBlock() instanceof ObserverBlock) || obs.getValue(BlockStateProperties.FACING) != dir) continue;
            BlockPos w = o.relative(dir);
            BlockState want = schematic.getBlockState(w);
            if (!want.isAir() && !(want.getBlock() instanceof LiquidBlock) && notDone(level, w, want)) return true;
        }
        return false;
    }

    private static boolean isSensitive(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ObserverBlock || b instanceof PistonBaseBlock || b instanceof DispenserBlock;
    }

    /** Blocks that do something when an observer pulses into them (or into the block they sit on). */
    private static boolean reactsToPower(BlockState state) {
        Block b = state.getBlock();
        return b instanceof PistonBaseBlock || b instanceof DispenserBlock || b instanceof RedStoneWireBlock || b instanceof DiodeBlock
                || b instanceof NoteBlock || b instanceof TntBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock || b instanceof RedstoneLampBlock || b instanceof PoweredRailBlock || b instanceof BellBlock
                || b instanceof HopperBlock || b instanceof CrafterBlock || b instanceof CopperBulbBlock;
    }

    /** Clicks the block with the current main hand item, with the plan's look and sneak, predicting it locally. */
    static void use(Minecraft mc, PlacePlan plan) {
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
        dev.steelaspect.cytraprinter.client.ClientHooks.simulatedHeadYaw = plan.headYaw();
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
            dev.steelaspect.cytraprinter.client.ClientHooks.simulatedHeadYaw = Float.NaN;
            player.input.keyPresses = input;
        }
    }

    // ---------------------------------------------------------------- clicking settings and pots

    private static void click(Minecraft mc, List<Job> jobs, int placedThisTick) {
        LocalPlayer player = mc.player;
        int clicks = 0;
        Set<Item> needed = new HashSet<>();
        for (Job j : jobs) if (j.kind() == Kind.PLACE) needed.add(j.item());
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
            } else if (job.kind() == Kind.STRIP) {
                boolean axe = player.hasInfiniteMaterials() && !Hotbar.any(player, Printer::isAxe)
                        ? Hotbar.select(mc, Items.DIAMOND_AXE)
                        : Hotbar.selectBest(mc, s -> isAxe(s) ? 1 : 0);
                if (!axe) {
                    Stats.missing.merge(Items.IRON_AXE, 1, Integer::sum);
                    continue;
                }
                BlockState world = mc.level.getBlockState(job.pos());
                use(mc, clickPlan(player, job.pos(), world));
                touched(mc, job.pos(), world, job.target());
                clicks++;
            } else if (job.kind() == Kind.COMPOST) {
                clicks += compost(mc, job, needed);
            }
        }
    }

    /**
     * Fills a composter towards its level with compostable items (best chance first, never items the build still
     * needs), or empties a full one. Several items go in at once when that can't overshoot: each raises it by at
     * most one, and at 7 vanilla takes no more.
     */
    private static int compost(Minecraft mc, Job job, Set<Item> needed) {
        LocalPlayer player = mc.player;
        BlockPos pos = job.pos();
        BlockState world = mc.level.getBlockState(pos);
        if (!world.is(Blocks.COMPOSTER)) return 0;
        int have = world.getValue(ComposterBlock.LEVEL);
        PlacePlan plan = clickPlan(player, pos, world);
        if (have == ComposterBlock.READY) {
            use(mc, plan);
            touched(mc, pos, world, job.target());
            return 1;
        }
        int n = Math.min(job.target().getValue(ComposterBlock.LEVEL), ComposterBlock.MAX_LEVEL) - have;
        if (n <= 0) return 0;
        boolean ok = player.hasInfiniteMaterials()
                ? Hotbar.select(mc, Items.PUMPKIN_PIE)
                : Hotbar.selectBest(mc, s -> compostChance(s, needed));
        if (!ok) {
            Stats.missing.merge(Items.WHEAT_SEEDS, 1, Integer::sum);
            return 0;
        }
        if (!player.hasInfiniteMaterials()) n = Math.min(n, player.getMainHandItem().getCount());
        for (int i = 0; i < n; i++) use(mc, plan);
        touched(mc, pos, world, job.target());
        return n;
    }

    private static double compostChance(ItemStack s, Set<Item> needed) {
        float chance = ComposterBlock.COMPOSTABLES.getFloat(s.getItem());
        if (chance <= 0 || needed.contains(s.getItem())) return 0;
        List<String> allowed = Configs.COMPOSTER_ITEMS.getStrings();
        if (!allowed.isEmpty() && !COMPOST_ITEMS.matches(allowed, s.getItem())) return 0;
        return chance;
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
        ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$syncCarriedItem();
        mining = job.pos();
        miningStart = ticks;
        ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$setDestroyDelay(0);
        mc.gameMode.startDestroyBlock(mining, face(player, mining));
        if (level.getBlockState(mining).isAir() || !level.getBlockState(mining).equals(job.world())) finishMining(mc);
    }

    private static boolean canBreak(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof LiquidBlock || isProtected(state)) return false;
        if (state.getDestroySpeed(level, pos) < 0 && !(player.getAbilities().instabuild && isBedrock(state))) return false;
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

    /** Positions with client predictions the server hasn't answered yet, or null when that can't be read. */
    private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?> pendingPredictions(ClientLevel level) {
        try {
            var handler = ((dev.steelaspect.cytraprinter.client.mixin.ClientLevelPredictionAccessor) level).cytraprinter$predictions();
            var map = ((dev.steelaspect.cytraprinter.client.mixin.PredictionHandlerAccessor) handler).cytraprinter$pending();
            return map.isEmpty() ? null : map;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True while the server hasn't confirmed our own change at pos (tests). */
    public static boolean pendingAt(ClientLevel level, BlockPos pos) {
        var map = pendingPredictions(level);
        return map != null && map.containsKey(pos.asLong());
    }

    /** The current head yaw first, then the other three cardinal yaws (vanilla mode turns the head for those). */
    private static float[] otherHeads(float head) {
        float[] out = new float[4];
        out[0] = head;
        int n = 1;
        for (int i = 0; i < 4 && n < 4; i++) {
            float h = PlacementSolver.wrap(i * 90f);
            if (Math.abs(PlacementSolver.wrap(h - head)) >= 1f) out[n++] = h;
        }
        return n == 4 ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Fingerprint of a spot and its six neighbours, which is what a placement there can depend on. */
    private static int around(ClientLevel level, BlockPos pos) {
        int h = Block.getId(level.getBlockState(pos));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) h = h * 31 + Block.getId(level.getBlockState(m.setWithOffset(pos, d)));
        return h;
    }

    private static void prune() {
        unsolved.long2ObjectEntrySet().removeIf(e -> e.getValue().until() < ticks);
        partial.clear();
        waitUntil.long2LongEntrySet().removeIf(e -> e.getLongValue() < ticks);
        stuckUntil.long2LongEntrySet().removeIf(e -> e.getLongValue() < ticks);
        placedSigns.long2LongEntrySet().removeIf(e -> ticks - e.getLongValue() > 200);
        attempts.long2LongEntrySet().removeIf(e -> ticks - (e.getLongValue() & 0xFFFFFFFFL) > 1200);
        lastAfter.keySet().removeIf(k -> !attempts.containsKey(k));
    }
}

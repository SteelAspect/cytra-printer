package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Survival bedrock breaking with the piston trick. A piston is placed on top of the block facing up and powered by
 * a redstone torch beside it (standing on a slime block if there is no floor). Once it has extended, in one tick:
 * the torch is broken (the piston queues its retraction), the piston is mined instantly and a new piston facing
 * <em>down</em> is placed in its spot. The queued retraction then runs on the new piston and removes the block
 * under it. Afterwards the piston and slime block are mined again.
 *
 * <p>Needs pistons, redstone torches, slime blocks (only without a floor) and a pickaxe that mines a piston
 * instantly: Efficiency V with Haste II. One block at a time.
 */
final class BedrockBreaker {
    private enum Step { SLIME, PISTON, TORCH, EXTEND, CLEANUP }

    private static final int STEP_TIMEOUT = 40;
    private static final int TRIES = 3;

    private static BlockPos target, piston, torch, slime;
    private static Step step;
    private static long stepStart;
    private static int pickHeld;
    private static int tries;

    /** Why bedrock is waiting, for the HUD (null = nothing missing). */
    static String blockedBy;

    private BedrockBreaker() {
    }

    static boolean active() {
        return target != null;
    }

    static void reset() {
        target = null;
        blockedBy = null;
    }

    /** Picks the nearest block that has room for the setup and starts on it. */
    static boolean start(Minecraft mc, List<BlockPos> targets, long ticks) {
        LocalPlayer player = mc.player;
        blockedBy = null;
        if (targets.isEmpty()) return false;
        if (!Hotbar.carries(player, Items.PISTON) || !Hotbar.carries(player, Items.REDSTONE_TORCH)) {
            blockedBy = "materials";
            return false;
        }
        if (!Hotbar.any(player, s -> instaMines(mc, s))) {
            blockedBy = "pickaxe";
            if (DEBUG) dev.steelaspect.cytraprinter.CytraPrinter.LOGGER.info("[debug] bedrock: no insta-mining pickaxe");
            return false;
        }
        Vec3 eye = player.getEyePosition();
        targets.sort(Comparator.comparingDouble(p -> eye.distanceToSqr(Vec3.atCenterOf(p))));
        for (BlockPos t : targets) {
            if (plan(mc, t)) {
                step = slime != null ? Step.SLIME : Step.PISTON;
                stepStart = ticks;
                tries = 0;
                pickHeld = 0;
                return true;
            }
        }
        blockedBy = "room";
        if (DEBUG) dev.steelaspect.cytraprinter.CytraPrinter.LOGGER.info("[debug] bedrock: no room for {}", targets);
        return false;
    }

    /** Finds the piston and torch spots for {@code t}; false if there is no room. */
    private static boolean plan(Minecraft mc, BlockPos t) {
        ClientLevel level = mc.level;
        BlockPos p = t.above();
        if (!free(level, p) || !free(level, p.above()) || !inReach(mc, p)) return false;
        BlockPos fallback = null, fallbackSlime = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (!free(level, n) || !inReach(mc, n)) continue;
            BlockPos below = n.below();
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                set(t, p, n, null);
                return true;
            }
            if (fallback == null && free(level, below) && inReach(mc, below) && Hotbar.carries(mc.player, Items.SLIME_BLOCK)) {
                fallback = n;
                fallbackSlime = below;
            }
        }
        if (fallback == null) return false;
        set(t, p, fallback, fallbackSlime);
        return true;
    }

    private static void set(BlockPos t, BlockPos p, BlockPos n, BlockPos s) {
        target = t;
        piston = p;
        torch = n;
        slime = s;
    }

    private static boolean free(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    private static boolean inReach(Minecraft mc, BlockPos pos) {
        return mc.player.isWithinBlockInteractionRange(pos, 0.0);
    }

    /** One tick of work on the current block. */
    static void tick(Minecraft mc, long ticks) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!inReach(mc, piston) || !inReach(mc, torch)) {
            step = Step.CLEANUP;
        }
        if (step != Step.CLEANUP && ticks - stepStart > STEP_TIMEOUT) next(Step.CLEANUP, ticks);
        switch (step) {
            case SLIME -> {
                if (level.getBlockState(slime).is(Blocks.SLIME_BLOCK)) next(Step.PISTON, ticks);
                else if (free(level, slime)) place(mc, slime, Blocks.SLIME_BLOCK.defaultBlockState());
            }
            case PISTON -> {
                BlockState s = level.getBlockState(piston);
                if (s.is(Blocks.PISTON) && s.getValue(PistonBaseBlock.FACING) == Direction.UP) next(Step.TORCH, ticks);
                else if (free(level, piston)) place(mc, piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
                else next(Step.CLEANUP, ticks);
            }
            case TORCH -> {
                if (level.getBlockState(torch).is(Blocks.REDSTONE_TORCH)) {
                    next(Step.EXTEND, ticks);
                    pickHeld = 0;
                } else if (free(level, torch)) {
                    place(mc, torch, Blocks.REDSTONE_TORCH.defaultBlockState());
                }
            }
            case EXTEND -> {
                // Hold the pickaxe a couple of ticks first: its Efficiency only counts once both sides see it held.
                if (!Hotbar.selectBest(mc, s -> instaMines(mc, s) ? 1 + s.getDestroySpeed(Blocks.PISTON.defaultBlockState()) : 0)) {
                    next(Step.CLEANUP, ticks);
                    return;
                }
                ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$syncCarriedItem();
                pickHeld++;
                BlockState s = level.getBlockState(piston);
                if (pickHeld >= 3 && player.onGround() && s.is(Blocks.PISTON) && s.getValue(PistonBaseBlock.EXTENDED)) {
                    flip(mc);
                    next(Step.CLEANUP, ticks);
                }
            }
            case CLEANUP -> cleanup(mc, ticks);
        }
    }

    /**
     * The trick itself, all in this tick: torch off, piston out, piston in facing down. The piston break is sent
     * directly: the server mines it instantly (it applies Efficiency), but the client doesn't always know the
     * Efficiency bonus and wouldn't remove it locally, so the spot is cleared here for the new piston.
     */
    private static void flip(Minecraft mc) {
        ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$setDestroyDelay(0);
        mc.gameMode.startDestroyBlock(torch, Direction.UP);
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, piston, Direction.UP));
        mc.level.setBlock(piston, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        mc.level.setBlock(piston.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockState down = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN);
        PlacePlan plan = PlacementSolver.solve(mc.level, mc.player, piston, Blocks.AIR.defaultBlockState(), down, (BlockItem) Items.PISTON, false, true, Look.headYaw);
        if (DEBUG) dev.steelaspect.cytraprinter.CytraPrinter.LOGGER.info("[debug] bedrock flip at {}: plan {}", piston, plan);
        if (plan != null && Hotbar.select(mc, Items.PISTON)) Printer.use(mc, plan);
    }

    /** Waits for the server, then mines what is left of the setup. Done when it is all gone. */
    private static void cleanup(Minecraft mc, long ticks) {
        ClientLevel level = mc.level;
        if (ticks - stepStart < 4) return;
        for (BlockPos pos : new BlockPos[]{torch, piston, slime}) {
            if (pos == null) continue;
            BlockState s = level.getBlockState(pos);
            if (s.is(Blocks.MOVING_PISTON)) return;
            if (!s.is(Blocks.PISTON) && !s.is(Blocks.PISTON_HEAD) && !s.is(Blocks.REDSTONE_TORCH) && !s.is(Blocks.REDSTONE_WALL_TORCH) && !s.is(Blocks.SLIME_BLOCK)) continue;
            if (!inReach(mc, pos)) continue;
            Hotbar.selectTool(mc, s);
            ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$syncCarriedItem();
            ((MultiPlayerGameModeAccessor) mc.gameMode).cytraprinter$setDestroyDelay(0);
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            if (!level.getBlockState(pos).isAir()) mc.gameMode.stopDestroyBlock();
            stepStart = ticks;
            return;
        }
        BlockState t = level.getBlockState(target);
        boolean broken = !Printer.isBedrock(t);
        if (broken || ++tries >= TRIES || !plan(mc, target)) {
            if (!broken) Printer.giveUp(target);
            target = null;
            return;
        }
        next(slime != null ? Step.SLIME : Step.PISTON, ticks);
    }

    private static final boolean DEBUG = Boolean.getBoolean("cytraprinter.debug");

    private static void next(Step s, long ticks) {
        if (DEBUG) dev.steelaspect.cytraprinter.CytraPrinter.LOGGER.info("[debug] bedrock {} -> {} (target {}, piston {}, torch {}, slime {})", step, s, target, piston, torch, slime);
        step = s;
        stepStart = ticks;
    }

    private static void place(Minecraft mc, BlockPos pos, BlockState state) {
        BlockItem item = (BlockItem) state.getBlock().asItem();
        BlockState current = mc.level.getBlockState(pos);
        PlacePlan plan = PlacementSolver.solve(mc.level, mc.player, pos, current, state, item, false, true, Look.headYaw);
        if (plan == null || !Hotbar.select(mc, item)) return;
        Printer.use(mc, plan);
    }

    /** True if this stack mines a piston in one tick for this player (Efficiency, Haste, on the ground, dry). */
    static boolean instaMines(Minecraft mc, ItemStack stack) {
        LocalPlayer player = mc.player;
        BlockState state = Blocks.PISTON.defaultBlockState();
        float speed = stack.getDestroySpeed(state);
        if (speed > 1) {
            Holder<Enchantment> eff = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
            int lvl = EnchantmentHelper.getItemEnchantmentLevel(eff, stack);
            if (lvl > 0) speed += lvl * lvl + 1;
        }
        if (MobEffectUtil.hasDigSpeed(player)) speed *= 1 + (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2f;
        if (player.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE)) return false;
        if (player.isEyeInFluid(FluidTags.WATER)) return false;
        speed *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        return speed / state.getBlock().defaultDestroyTime() / 30f >= 1f;
    }
}

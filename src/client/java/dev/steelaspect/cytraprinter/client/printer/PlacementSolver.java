package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.mixin.BlockItemInvoker;
import dev.steelaspect.cytraprinter.state.StateRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a click that makes vanilla place exactly the target block. Every candidate (clicked face, spot on the face,
 * which way the player looks, sneaking) is run through the item's own placement code against the real client world,
 * so whatever a block's placement rules are, the printer only clicks when the result is right.
 *
 * <p>Clicks always target the position itself ("air place"): vanilla accepts a click on a replaceable block and
 * places into it, so any face and any look direction can be used, independent of neighbouring blocks.
 */
public final class PlacementSolver {
    private static final float[] PITCHES = {30f, -30f, 90f, -90f};
    private static final double[][] IN_FACE = {{-0.25, -0.25}, {0.25, -0.25}, {-0.25, 0.25}, {0.25, 0.25}};

    private PlacementSolver() {
    }

    private record Look(float yaw, float pitch) {
    }

    /**
     * @param exact   true: the result must match the target's orientation (vanilla server). false: only the right
     *                block (the server helper sets the orientation), still with every vanilla placement check.
     * @param headYaw the server's head yaw: blocks that place by the "nearest looking direction" (pistons,
     *                observers, wall torches, buttons...) read the head, which can't be turned within a tick
     */
    public static PlacePlan solve(Level level, LocalPlayer player, BlockPos pos, BlockState current, BlockState target,
                                  BlockItem item, boolean partnerPresent, boolean exact, float headYaw) {
        ItemStack stack = new ItemStack(item);
        List<Look> looks = looks(target, player);
        float yaw = player.getYRot(), pitch = player.getXRot();
        float yawO = player.yRotO, pitchO = player.xRotO;
        float head = player.yHeadRot, headO = player.yHeadRotO;
        player.yHeadRot = headYaw;
        player.yHeadRotO = headYaw;
        try {
            for (int s = 0; s < 2; s++) {
                boolean sneak = s == 1;
                for (Look look : looks) {
                    player.setYRot(look.yaw());
                    player.setXRot(look.pitch());
                    player.yRotO = look.yaw();
                    player.xRotO = look.pitch();
                    for (Direction face : Direction.values()) {
                        for (Vec3 hit : hits(pos, face)) {
                            BlockState result = simulate(level, player, item, stack, new BlockHitResult(hit, face, pos, false), sneak, pos);
                            if (result == null) continue;
                            boolean ok = exact
                                    ? StateRules.placementMatches(result, current, target, partnerPresent)
                                    : result.getBlock() == target.getBlock() || result.getBlock().asItem() == target.getBlock().asItem();
                            if (ok) return new PlacePlan(pos, face, hit, look.yaw(), look.pitch(), headYaw, sneak);
                        }
                    }
                }
                if (!exact) break; // the helper decides; sneaking never needed
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
            player.yHeadRot = head;
            player.yHeadRotO = headO;
        }
        return null;
    }

    /** True if the plan gives a matching block whatever the server's head yaw is (safe to use before the head settled). */
    public static boolean headIndependent(Level level, LocalPlayer player, PlacePlan plan, BlockState current, BlockState target,
                                          BlockItem item, boolean partnerPresent) {
        ItemStack stack = new ItemStack(item);
        float yaw = player.getYRot(), pitch = player.getXRot(), yawO = player.yRotO, pitchO = player.xRotO;
        float head = player.yHeadRot, headO = player.yHeadRotO;
        try {
            player.setYRot(plan.yaw());
            player.setXRot(plan.pitch());
            player.yRotO = plan.yaw();
            player.xRotO = plan.pitch();
            for (int i = 1; i < 4; i++) {
                float h = wrap(plan.headYaw() + i * 90f);
                player.yHeadRot = h;
                player.yHeadRotO = h;
                BlockState result = simulate(level, player, item, stack, plan.hitResult(), plan.sneak(), plan.pos());
                if (!StateRules.placementMatches(result, current, target, partnerPresent)) return false;
            }
            return true;
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
            player.yHeadRot = head;
            player.yHeadRotO = headO;
        }
    }

    /** A cardinal head yaw (other than {@code currentHead}) that makes the target placeable, or NaN. */
    public static float headYawFor(Level level, LocalPlayer player, BlockPos pos, BlockState current, BlockState target,
                                   BlockItem item, boolean partnerPresent, float currentHead) {
        for (int i = 0; i < 4; i++) {
            float h = wrap(i * 90f);
            if (Math.abs(wrap(h - currentHead)) < 1f) continue;
            if (solve(level, player, pos, current, target, item, partnerPresent, true, h) != null) return h;
        }
        return Float.NaN;
    }

    /** What vanilla would place for this click, or null if it would place nothing / somewhere else. */
    private static BlockState simulate(Level level, LocalPlayer player, BlockItem item, ItemStack stack, BlockHitResult hit, boolean sneak, BlockPos pos) {
        BlockPlaceContext ctx = new SneakContext(level, player, stack, hit, sneak);
        if (!ctx.canPlace() || !ctx.getClickedPos().equals(pos)) return null;
        BlockItemInvoker invoker = (BlockItemInvoker) item;
        BlockPlaceContext updated = invoker.cytraprinter$updatePlacementContext(ctx);
        if (updated == null || !updated.getClickedPos().equals(pos)) return null;
        try {
            return invoker.cytraprinter$getPlacementState(updated);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Look> looks(BlockState target, LocalPlayer player) {
        List<Look> out = new ArrayList<>();
        if (target.hasProperty(BlockStateProperties.ROTATION_16)) {
            // Signs, banners, skulls: every 1/16 yaw (they don't all count from the same side), looking down or ahead.
            for (int i = 0; i < 16; i++) {
                out.add(new Look(wrap(i * 22.5f), 90f));
                out.add(new Look(wrap(i * 22.5f), 30f));
            }
        }
        for (int i = 0; i < 4; i++) {
            float yaw = i * 90f;
            for (float pitch : PITCHES) out.add(new Look(wrap(yaw), pitch));
        }
        out.add(new Look(player.getYRot(), player.getXRot()));
        return out;
    }

    static float wrap(float yaw) {
        yaw %= 360f;
        if (yaw >= 180f) yaw -= 360f;
        if (yaw < -180f) yaw += 360f;
        return yaw;
    }

    /** Four spots on the given face of the block at pos (upper/lower and left/right halves). */
    private static Vec3[] hits(BlockPos pos, Direction face) {
        Vec3 center = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Vec3[] out = new Vec3[IN_FACE.length];
        for (int i = 0; i < IN_FACE.length; i++) {
            double a = IN_FACE[i][0], b = IN_FACE[i][1];
            out[i] = switch (face.getAxis()) {
                case X -> center.add(0, a, b);
                case Y -> center.add(a, 0, b);
                case Z -> center.add(a, b, 0);
            };
        }
        return out;
    }

    /** Placement context with a chosen sneak state (everything else reads the player). */
    private static final class SneakContext extends BlockPlaceContext {
        private final boolean sneak;

        SneakContext(Level level, LocalPlayer player, ItemStack stack, BlockHitResult hit, boolean sneak) {
            super(level, player, InteractionHand.MAIN_HAND, stack, hit);
            this.sneak = sneak;
        }

        @Override
        public boolean isSecondaryUseActive() {
            return this.sneak;
        }
    }
}

package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Reach on 1.20.4, which has no {@code Player.isWithinBlockInteractionRange}: the 1.21.11 rule (eye to the block's
 * box within the player's reach plus {@code extra}) and the 1.20.4 server's own limit for using and mining a block
 * (eye to the block's centre at most 6 blocks).
 */
public final class Reach {
    /** 1.20.4 ServerGamePacketListenerImpl.MAX_INTERACTION_DISTANCE, a little short so rounding never crosses it. */
    private static final double SERVER_MAX_SQR = 6.0 * 6.0 - 0.01;

    private Reach() {
    }

    /** The player's block reach: 4.5 in survival, 5 in creative. */
    public static double range() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode == null ? 4.5 : mc.gameMode.getPickRange();
    }

    public static boolean within(LocalPlayer player, BlockPos pos, double extra) {
        Vec3 eye = player.getEyePosition();
        if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > SERVER_MAX_SQR) return false;
        double r = range() + extra;
        return new AABB(pos).distanceToSqr(eye) < r * r;
    }
}

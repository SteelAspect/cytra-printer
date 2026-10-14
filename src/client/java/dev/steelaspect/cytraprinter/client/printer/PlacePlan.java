package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** One click: where to click, facing which way (body yaw, pitch, the server's head yaw), sneaking or not. */
public record PlacePlan(BlockPos pos, Direction face, Vec3 hit, float yaw, float pitch, float headYaw, boolean sneak) {
    public BlockHitResult hitResult() {
        return new BlockHitResult(hit, face, pos, false);
    }
}

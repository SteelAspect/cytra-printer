package dev.steelaspect.cytraprinter.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.steelaspect.cytraprinter.client.ClientHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While the printer owns the server-side rotation, vanilla's movement packets carry position only. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRotationMixin {
    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void cytraprinter$keepPrinterRotation(ClientPacketListener connection, Packet<?> packet, Operation<Void> original) {
        if (ClientHooks.rotationLocked()) {
            if (packet instanceof ServerboundMovePlayerPacket.PosRot p) {
                packet = new ServerboundMovePlayerPacket.Pos(p.getX(0), p.getY(0), p.getZ(0), p.isOnGround(), p.horizontalCollision());
            } else if (packet instanceof ServerboundMovePlayerPacket.Rot p) {
                packet = new ServerboundMovePlayerPacket.StatusOnly(p.isOnGround(), p.horizontalCollision());
            }
        }
        original.call(connection, packet);
    }
}

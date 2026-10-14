package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * The rotation and sneak state the server has for us while printing. The printer owns the server-side rotation
 * then (vanilla's own rotation packets are held back, see LocalPlayerMixin), because many blocks are placed by
 * the server's <em>head</em> rotation, which it only copies from the body rotation once per server tick. So the
 * printer keeps the server's yaw on {@link #headYaw} at the end of every tick and knows the head has followed
 * after two ticks; within a tick it rotates freely for blocks that only need the body yaw or the pitch.
 */
final class Look {
    private static boolean locked;
    private static float yaw, pitch;
    private static boolean sneak;
    /** Yaw the server holds between ticks, i.e. its head yaw once {@link #headTicks} >= 2. */
    static float headYaw;
    static int headTicks;

    private Look() {
    }

    static boolean locked() {
        return locked;
    }

    /** Takes over the server rotation (starting from the real one). */
    static void lock(LocalPlayer player) {
        if (locked) return;
        locked = true;
        yaw = headYaw = player.getYRot();
        pitch = player.getXRot();
        sneak = player.input.keyPresses.shift();
        headTicks = 0;
    }

    /** Hands the rotation back: the server gets the real rotation and sneak state again. */
    static void unlock(Minecraft mc) {
        if (!locked) return;
        locked = false;
        LocalPlayer player = mc.player;
        if (player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
        if (sneak != player.input.keyPresses.shift()) mc.getConnection().send(new ServerboundPlayerInputPacket(player.input.keyPresses));
    }

    /** The server set our rotation itself (teleport): start over from it and wait for the head again. */
    static void onTeleport(LocalPlayer player) {
        if (!locked) return;
        yaw = headYaw = player.getYRot();
        pitch = player.getXRot();
        headTicks = 0;
    }

    static void reset() {
        locked = false;
    }

    /** Called at the start of every printer tick while locked: the server had one more tick to follow. */
    static void tick() {
        if (locked) headTicks++;
    }

    static boolean headSettled() {
        return locked && headTicks >= 2;
    }

    static void apply(Minecraft mc, float newYaw, float newPitch, boolean newSneak) {
        LocalPlayer player = mc.player;
        if (newYaw != yaw || newPitch != pitch) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(newYaw, newPitch, player.onGround(), player.horizontalCollision));
            yaw = newYaw;
            pitch = newPitch;
        }
        if (newSneak != sneak) {
            Input in = player.input.keyPresses;
            mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(in.forward(), in.backward(), in.left(), in.right(), in.jump(), newSneak, in.sprint())));
            sneak = newSneak;
        }
    }

    /** End of a printer tick: leave the server on {@code nextHead} (resetting the settle count if it changed), real sneak. */
    static void finish(Minecraft mc, float nextHead) {
        LocalPlayer player = mc.player;
        if (nextHead != headYaw) {
            headYaw = nextHead;
            headTicks = 0;
        }
        apply(mc, headYaw, player.getXRot(), player.input.keyPresses.shift());
    }
}

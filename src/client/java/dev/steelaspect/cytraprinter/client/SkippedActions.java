package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Printer;
import dev.steelaspect.cytraprinter.client.printer.Skipped;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** The "next skipped block" and "retry" actions, shared by the hotkeys and the Alloy Hub page. */
public final class SkippedActions {
    private SkippedActions() {
    }

    /** Picks the nearest skipped spot (the next one on each press), says where it is and, when not printing, looks at it. */
    public static void next(Minecraft mc) {
        if (mc.player == null) return;
        Skipped.checkLevel(mc.level);
        Vec3 eye = mc.player.getEyePosition();
        Skipped.Spot spot = Skipped.next(eye);
        if (spot == null) {
            mc.player.sendOverlayMessage(Component.translatable("cytraprinter.msg.skipped_none"));
            return;
        }
        Vec3 center = Vec3.atCenterOf(spot.pos());
        int dist = (int) Math.round(center.distanceTo(eye));
        mc.player.sendOverlayMessage(Component.translatable("cytraprinter.msg.skipped_next",
                Component.translatable("cytraprinter.skipped." + spot.kind().key()),
                spot.pos().getX(), spot.pos().getY(), spot.pos().getZ(), dist, Skipped.size()));
        // While printing, the printer owns the view; it's outlined either way.
        if (!Configs.PRINTING.getBooleanValue()) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, center);
    }

    public static void retry(Minecraft mc) {
        int n = Skipped.size();
        Printer.retrySkipped();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("cytraprinter.msg.skipped_retry", n));
    }
}

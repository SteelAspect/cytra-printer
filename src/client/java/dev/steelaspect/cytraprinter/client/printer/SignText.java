package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * The server opens the sign editor after a sign is placed. For signs the printer placed, the schematic's text is
 * sent straight back and the editor never opens. Only the front can be written this way (vanilla only lets the
 * player who placed it edit once); the back stays blank.
 */
public final class SignText {
    private SignText() {
    }

    public static boolean onEditor(SignBlockEntity sign, boolean front) {
        if (!Printer.wasPlacedByPrinter(sign.getBlockPos())) return false;
        Printer.forgetSign(sign.getBlockPos());
        Minecraft mc = Minecraft.getInstance();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (!Configs.SIGN_TEXT.getBooleanValue() || schematic == null || mc.getConnection() == null) return true;
        if (!(schematic.getBlockEntity(sign.getBlockPos()) instanceof SignBlockEntity wanted)) return true;
        var slot = front ? net.minecraft.world.level.block.entity.SignTextSlot.FRONT : net.minecraft.world.level.block.entity.SignTextSlot.BACK;
        var text = wanted.getText(slot);
        String[] lines = new String[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            lines[i] = text.getMessages(false).get(i).getString();
            any |= !lines[i].isEmpty();
        }
        if (any) mc.getConnection().send(new ServerboundSignUpdatePacket(sign.getBlockPos(), java.util.List.of(lines), slot));
        return true;
    }
}

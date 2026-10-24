package dev.steelaspect.cytraprinter.net;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Server to client on join: the placement helper is installed, and whether the server owner has it turned on. */
public record HelloPayload(int protocol, boolean enabled) implements FabricPacket {
    public static final int PROTOCOL = 1;
    public static final PacketType<HelloPayload> TYPE = PacketType.create(new ResourceLocation(CytraPrinter.MOD_ID, "hello"),
            buf -> new HelloPayload(buf.readVarInt(), buf.readBoolean()));

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(protocol);
        buf.writeBoolean(enabled);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}

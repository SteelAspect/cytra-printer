package dev.steelaspect.cytraprinter.net;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client on join: the placement helper is installed, and whether the server owner has it turned on. */
public record HelloPayload(int protocol, boolean enabled) implements CustomPacketPayload {
    public static final int PROTOCOL = 1;
    public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraPrinter.MOD_ID, "hello"));
    public static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HelloPayload::protocol,
            ByteBufCodecs.BOOL, HelloPayload::enabled,
            HelloPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

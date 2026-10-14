package dev.steelaspect.cytraprinter.net;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client to server, sent right before the vanilla use-item-on packet: "the block I'm placing at {@code pos} should be
 * {@code state}". The server only uses it for the orientation-type properties and only if the held item makes that
 * block (see PlacementAssist), so it can't create anything a normal click couldn't.
 */
public record IntentPayload(BlockPos pos, BlockState state) implements CustomPacketPayload {
    public static final Type<IntentPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraPrinter.MOD_ID, "intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf, IntentPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, IntentPayload::pos,
            ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), IntentPayload::state,
            IntentPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

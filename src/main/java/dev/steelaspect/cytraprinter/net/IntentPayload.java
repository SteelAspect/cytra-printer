package dev.steelaspect.cytraprinter.net;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client to server, sent right before the vanilla use-item-on packet: "the block I'm placing at {@code pos} should be
 * {@code state}". The server only uses it for the orientation-type properties and only if the held item makes that
 * block (see PlacementAssist), so it can't create anything a normal click couldn't.
 */
public record IntentPayload(BlockPos pos, BlockState state) implements FabricPacket {
    public static final PacketType<IntentPayload> TYPE = PacketType.create(new ResourceLocation(CytraPrinter.MOD_ID, "intent"),
            buf -> new IntentPayload(buf.readBlockPos(), buf.readById(Block.BLOCK_STATE_REGISTRY)));

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeId(Block.BLOCK_STATE_REGISTRY, state);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}

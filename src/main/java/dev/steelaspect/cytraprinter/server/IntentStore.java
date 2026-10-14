package dev.steelaspect.cytraprinter.server;

import dev.steelaspect.cytraprinter.net.IntentPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Intents per player and position, used once by the next placement there or dropped after a few seconds. */
public final class IntentStore {
    private static final int LIFETIME_TICKS = 100;
    private static final int MAX_PER_PLAYER = 4096;

    private record Intent(BlockState state, long expires) {
    }

    private static final Map<UUID, LinkedHashMap<BlockPos, Intent>> INTENTS = new HashMap<>();
    private static long ticks;

    private IntentStore() {
    }

    public static void add(ServerPlayer player, IntentPayload payload) {
        LinkedHashMap<BlockPos, Intent> map = INTENTS.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
        map.remove(payload.pos());
        map.put(payload.pos().immutable(), new Intent(payload.state(), ticks + LIFETIME_TICKS));
        if (map.size() > MAX_PER_PLAYER) {
            Iterator<BlockPos> it = map.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** The intended state at {@code pos}, removing it, or null. */
    public static BlockState take(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, Intent> map = INTENTS.get(player.getUUID());
        if (map == null) return null;
        Intent intent = map.remove(pos);
        return intent == null || intent.expires() < ticks ? null : intent.state();
    }

    public static void forget(ServerPlayer player) {
        INTENTS.remove(player.getUUID());
    }

    public static void tick() {
        ticks++;
        if (ticks % 20 != 0) return;
        for (Iterator<LinkedHashMap<BlockPos, Intent>> it = INTENTS.values().iterator(); it.hasNext(); ) {
            LinkedHashMap<BlockPos, Intent> map = it.next();
            map.values().removeIf(i -> i.expires() < ticks);
            if (map.isEmpty()) it.remove();
        }
    }
}

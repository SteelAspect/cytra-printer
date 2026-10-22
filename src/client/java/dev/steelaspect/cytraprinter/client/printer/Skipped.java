package dev.steelaspect.cytraprinter.client.printer;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Spots the printer left alone, remembered after the player walks away so they can be outlined and visited: wrong
 * blocks it won't fix, blocks it can't place exactly from where the player stood, and spots it gave up on for now.
 * A spot is forgotten once it's right again (checked when back in reach and every few seconds), on Retry, and when
 * the player changes world.
 */
public final class Skipped {
    public enum Kind {
        /** Wrong block the printer won't break (fixing off, protected, container, unbreakable). */
        WRONG,
        /** No click from where the player stood gives exactly the schematic's block. */
        UNSOLVABLE,
        /** Kept failing; parked until Retry or the park runs out. */
        STUCK;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Spot(BlockPos pos, Kind kind) {
    }

    private static final int MAX = 4096;
    private static final Long2ByteOpenHashMap SPOTS = new Long2ByteOpenHashMap();
    private static @Nullable Level level;
    /** The spot the "next skipped block" hotkey picked last, drawn brighter. */
    private static @Nullable BlockPos target;

    private Skipped() {
    }

    static void mark(BlockPos pos, Kind kind) {
        if (SPOTS.size() >= MAX && !SPOTS.containsKey(pos.asLong())) return;
        SPOTS.put(pos.asLong(), (byte) kind.ordinal());
    }

    static void forget(BlockPos pos) {
        long key = pos.asLong();
        SPOTS.remove(key);
        if (target != null && target.asLong() == key) target = null;
    }

    public static void clear() {
        SPOTS.clear();
        target = null;
    }

    /** Clears everything when the player is in another level than last time. */
    public static void checkLevel(@Nullable Level current) {
        if (current != level) {
            clear();
            level = current;
        }
    }

    public static int count(Kind kind) {
        int n = 0;
        for (byte b : SPOTS.values()) if (b == kind.ordinal()) n++;
        return n;
    }

    public static int size() {
        return SPOTS.size();
    }

    public static @Nullable BlockPos target() {
        return target;
    }

    /** Spots within {@code range} blocks of {@code from}, for drawing. */
    public static List<Spot> near(Vec3 from, double range) {
        List<Spot> out = new ArrayList<>();
        double max = range * range;
        Kind[] kinds = Kind.values();
        for (Long2ByteMap.Entry e : SPOTS.long2ByteEntrySet()) {
            BlockPos pos = BlockPos.of(e.getLongKey());
            if (Vec3.atCenterOf(pos).distanceToSqr(from) <= max) out.add(new Spot(pos, kinds[e.getByteValue()]));
        }
        return out;
    }

    /**
     * The nearest spot to {@code from} other than the current target, which becomes the new target; null when there
     * are none. Pressing again moves on to the next nearest, so every spot gets visited.
     */
    public static @Nullable Spot next(Vec3 from) {
        Spot best = null;
        double bestDist = Double.MAX_VALUE;
        Kind[] kinds = Kind.values();
        for (Long2ByteMap.Entry e : SPOTS.long2ByteEntrySet()) {
            if (target != null && target.asLong() == e.getLongKey() && SPOTS.size() > 1) continue;
            BlockPos pos = BlockPos.of(e.getLongKey());
            double d = Vec3.atCenterOf(pos).distanceToSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = new Spot(pos, kinds[e.getByteValue()]);
            }
        }
        target = best == null ? null : best.pos();
        return best;
    }

    /** Forgets spots that are right now, for spots out of reach that got fixed by hand. */
    static void prune(ClientLevel world, fi.dy.masa.litematica.world.WorldSchematic schematic) {
        SPOTS.long2ByteEntrySet().removeIf(e -> {
            BlockPos pos = BlockPos.of(e.getLongKey());
            if (!world.isLoaded(pos)) return false;
            BlockState want = schematic.getBlockState(pos);
            BlockState have = world.getBlockState(pos);
            return want == have || (want.isAir() && have.isAir());
        });
        if (target != null && !SPOTS.containsKey(target.asLong())) target = null;
    }
}

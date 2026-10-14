package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Numbers for the HUD, refreshed every printer tick. */
public final class Stats {
    public static long placedTotal;
    public static int remaining, held, wrong, stuck, unsolvable;
    public static final Map<Item, Integer> missing = new LinkedHashMap<>();
    private static final ArrayDeque<Long> recent = new ArrayDeque<>();

    private Stats() {
    }

    static void placed(long tick) {
        placedTotal++;
        recent.addLast(tick);
    }

    static void beginScan(long tick) {
        remaining = held = wrong = stuck = unsolvable = 0;
        missing.clear();
        while (!recent.isEmpty() && recent.peekFirst() < tick - 20) recent.removeFirst();
    }

    /** Placements in the last second. */
    public static int perSecond() {
        return recent.size();
    }

    public static void reset() {
        placedTotal = 0;
        recent.clear();
        missing.clear();
        remaining = held = wrong = stuck = unsolvable = 0;
    }
}

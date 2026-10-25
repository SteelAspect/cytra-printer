package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Numbers for the HUD, refreshed every printer tick. */
public final class Stats {
    public static long placedTotal;
    public static int remaining, held, wrong, stuck, unsolvable;
    public static boolean lagging;
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

    // Printer tick cost (scan, place + click, whole tick) for the last 1200 work ticks, in nanoseconds.
    private static final int SAMPLES = 1200;
    private static final long[][] times = new long[3][SAMPLES];
    private static int sampleCount, sampleNext;

    static void timing(long scan, long place, long total) {
        times[0][sampleNext] = scan;
        times[1][sampleNext] = place;
        times[2][sampleNext] = total;
        sampleNext = (sampleNext + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;
    }

    public static void resetTiming() {
        sampleCount = sampleNext = 0;
        PlacementSolver.fails = 0;
        PlacementSolver.slowestFailNanos = 0;
    }

    /** "scan avg/p95/max, place ..., tick ..." in milliseconds, for tests and debugging. */
    public static String timingSummary() {
        if (sampleCount == 0) return "no samples";
        StringBuilder sb = new StringBuilder(sampleCount + " ticks:");
        String[] names = {"scan", "place", "tick"};
        for (int k = 0; k < 3; k++) {
            long[] v = java.util.Arrays.copyOf(times[k], sampleCount);
            java.util.Arrays.sort(v);
            long sum = 0;
            for (long x : v) sum += x;
            sb.append(String.format(java.util.Locale.ROOT, " %s avg %.2f p95 %.2f max %.2f ms", names[k],
                    sum / (double) sampleCount / 1e6, v[(int) (sampleCount * 0.95)] / 1e6, v[sampleCount - 1] / 1e6));
        }
        sb.append(String.format(java.util.Locale.ROOT, " | failed searches %d, slowest %.2f ms",
                PlacementSolver.fails, PlacementSolver.slowestFailNanos / 1e6));
        return sb.toString();
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

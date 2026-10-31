package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * Rail Placement Fix (2No2Name, mod id {@code rail-placement-fix}): sneak-placing a rail picks its shape from where the
 * block is clicked (corner = curve, upper half = slope) and leaves neighbouring rails alone. With it installed here
 * (and on the server), rail shapes become something the printer gets exactly right: it places rails sneaking only and
 * its placement simulation runs the mod's own code. Without the mod, rail shapes follow the neighbours as in vanilla.
 */
public final class RailFix {
    private static final String MOD = "rail-placement-fix";
    private static Boolean loaded;
    private static ThreadLocal<?>[] threadLocals;

    private RailFix() {
    }

    public static boolean active() {
        if (loaded == null) loaded = FabricLoader.getInstance().isModLoaded(MOD);
        return loaded && Configs.RAIL_SHAPES.getBooleanValue();
    }

    /**
     * The mod remembers the clicked spot in thread-locals from {@code getStateForPlacement} until the rail is really
     * placed. A simulated click never places anything, so they're cleared afterwards.
     */
    static void clearAfterSimulation() {
        if (threadLocals == null) {
            try {
                Class<?> c = Class.forName("no2.railplacementfix.common.RailPlacementHelper");
                Field a = c.getField("NO_CONNECT_POS"), b = c.getField("PLAYER_PLACED_POS");
                threadLocals = new ThreadLocal<?>[]{(ThreadLocal<?>) a.get(null), (ThreadLocal<?>) b.get(null)};
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                CytraPrinter.LOGGER.warn("Rail Placement Fix changed; its thread-locals can't be cleared ({})", e.toString());
                threadLocals = new ThreadLocal<?>[0];
            }
        }
        for (ThreadLocal<?> t : threadLocals) t.remove();
    }
}

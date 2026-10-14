package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * Missing items are fetched by Cytra Container (shulker boxes in the inventory, linked storage), through the same
 * hook it offers other printers. Optional: without Cytra Container this does nothing. Cytra Container's own
 * "printer pulls" setting decides whether it acts.
 */
final class Restock {
    private static final String CONTAINER_MOD = "containerautofill";
    private static MethodHandle hook;
    private static boolean looked;

    private Restock() {
    }

    static void request(List<Item> items) {
        MethodHandle h = hook();
        if (h == null) return;
        List<ItemStack> stacks = new ArrayList<>(items.size());
        for (Item item : items) stacks.add(new ItemStack(item));
        try {
            h.invoke(stacks);
        } catch (Throwable t) {
            CytraPrinter.LOGGER.warn("Cytra Container restock failed, turning it off for this session", t);
            hook = null;
        }
    }

    private static MethodHandle hook() {
        if (looked) return hook;
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded(CONTAINER_MOD)) return null;
        try {
            Class<?> c = Class.forName("dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures");
            hook = MethodHandles.publicLookup().findStatic(c, "onPrinterMissing", MethodType.methodType(void.class, List.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            CytraPrinter.LOGGER.info("Cytra Container is installed but has no printer hook; restocking is off");
        }
        return hook;
    }
}

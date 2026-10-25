package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.CytraPrinter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * 26.3: missing items are fetched by Take It Out (rofumer, mod id {@code takeitout}), the same way its own printer
 * integrations do it: a shulker box in the inventory that holds the item gets one stack pulled out by Take It Out's
 * server part ({@code GetShulkerStackPayload}); otherwise Take It Out's linked world containers are asked. While a
 * pull is on its way ({@code TakeitoutClient.awaitingStack}) the printer waits. Everything goes through reflection,
 * so without Take It Out (or with a version that changed these names) this does nothing. Our own "Restock" setting
 * turns it on and off; Take It Out's auto-take-out toggle isn't needed.
 */
final class Restock {
    private static final String MOD = "takeitout";
    private static Hooks hooks;
    private static boolean looked;

    private record Hooks(MethodHandle awaitingGet, MethodHandle awaitingSet, MethodHandle shulkerSingleMode,
                         MethodHandle takeSingleMode, MethodHandle shulkerWithStack, MethodHandle slotWithStack,
                         MethodHandle inventoryFromShulker, MethodHandle payload, MethodHandle requestStack) {
    }

    private Restock() {
    }

    /** True while Take It Out is still bringing a stack; the printer leaves the inventory alone meanwhile. */
    static boolean busy() {
        Hooks h = hooks();
        if (h == null) return false;
        try {
            ItemStack waiting = (ItemStack) h.awaitingGet().invoke();
            return waiting != null && !waiting.isEmpty();
        } catch (Throwable t) {
            off(t);
            return false;
        }
    }

    /** Asks Take It Out for the first missing item it can find; one request at a time. */
    static void request(List<Item> items) {
        Hooks h = hooks();
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.player == null || busy()) return;
        Inventory inv = mc.player.getInventory();
        try {
            for (Item item : items) {
                ItemStack stack = new ItemStack(item);
                if (inv.findSlotMatchingItem(stack) != -1) continue;
                int shulker = (int) h.shulkerWithStack().invoke(inv, stack);
                if (shulker >= 0) {
                    Container box = (Container) h.inventoryFromShulker().invoke(inv.getItem(shulker));
                    int slot = (int) h.slotWithStack().invoke(box, stack);
                    if (slot < 0) continue;
                    h.awaitingSet().invoke(stack.copyWithCount(1));
                    ClientPlayNetworking.send((CustomPacketPayload) h.payload().invoke(slot, shulker, (boolean) h.shulkerSingleMode().invoke()));
                    return;
                }
                if ((boolean) h.requestStack().invoke(mc, stack, (boolean) h.takeSingleMode().invoke())) return;
            }
        } catch (Throwable t) {
            off(t);
        }
    }

    private static void off(Throwable t) {
        CytraPrinter.LOGGER.warn("Take It Out restock failed, turning it off for this session", t);
        hooks = null;
    }

    private static Hooks hooks() {
        if (looked) return hooks;
        looked = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD)) return null;
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            Class<?> client = Class.forName("net.maxbel.takeitout.client.TakeitoutClient");
            Class<?> util = Class.forName("net.maxbel.takeitout.client.Util");
            Class<?> box = Class.forName("net.maxbel.takeitout.client.ItemStackInventory");
            Class<?> payload = Class.forName("net.maxbel.takeitout.Takeitout$GetShulkerStackPayload");
            Class<?> sources = Class.forName("net.maxbel.takeitout.client.WorldContainerSources");
            hooks = new Hooks(
                    l.findStaticGetter(client, "awaitingStack", ItemStack.class),
                    l.findStaticSetter(client, "awaitingStack", ItemStack.class),
                    l.findStaticGetter(client, "SHULKER_SINGLE_ITEM_MODE", boolean.class),
                    l.findStaticGetter(client, "TAKE_SINGLE_ITEM_MODE", boolean.class),
                    l.findStatic(util, "getShulkerWithStack", MethodType.methodType(int.class, Inventory.class, ItemStack.class)),
                    l.findStatic(util, "getSlotWithStack", MethodType.methodType(int.class, Container.class, ItemStack.class)),
                    l.findStatic(box, "getInventoryFromShulker", MethodType.methodType(box, ItemStack.class)),
                    l.findConstructor(payload, MethodType.methodType(void.class, int.class, int.class, boolean.class)),
                    l.findStatic(sources, "requestStack", MethodType.methodType(boolean.class, Minecraft.class, ItemStack.class, boolean.class)));
            CytraPrinter.LOGGER.info("Take It Out found: missing blocks are pulled from shulker boxes");
        } catch (ReflectiveOperationException | LinkageError e) {
            CytraPrinter.LOGGER.info("Take It Out is installed but its restock hooks weren't found; restocking is off ({})", e.toString());
        }
        return hooks;
    }
}

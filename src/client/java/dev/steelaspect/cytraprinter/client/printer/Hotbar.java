package dev.steelaspect.cytraprinter.client.printer;

import dev.steelaspect.cytraprinter.client.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Gets items into the selected hotbar slot: select, swap in from the inventory, or (creative) create. */
public final class Hotbar {
    private static int nextSlot;

    private Hotbar() {
    }

    /** True if the item is (now) in the main hand. */
    public static boolean select(Minecraft mc, Item item) {
        LocalPlayer player = mc.player;
        Inventory inv = player.getInventory();
        if (inv.getSelectedItem().is(item)) return true;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        if (player.hasInfiniteMaterials()) {
            int slot = pickSlot(inv);
            ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
            inv.setItem(slot, stack);
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
            inv.setSelectedSlot(slot);
            return true;
        }
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(item)) {
                swapIn(mc, i);
                return inv.getSelectedItem().is(item);
            }
        }
        return false;
    }

    /**
     * Puts the best tool for the block in hand (survival). When no tool is faster than a bare hand (glass, for
     * example), a pickaxe is used, so the printer never breaks with a sword or a block in hand. False when there's
     * neither.
     */
    public static boolean selectTool(Minecraft mc, BlockState state) {
        if (selectFastest(mc, state)) return true;
        return selectBest(mc, s -> s.is(net.minecraft.tags.ItemTags.PICKAXES) && !(s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2) ? 1 : 0);
    }

    private static boolean selectFastest(Minecraft mc, BlockState state) {
        Inventory inv = mc.player.getInventory();
        ToDoubleFunction<ItemStack> score = s -> {
            if (s.isEmpty() || (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2)) return -1;
            double speed = s.getDestroySpeed(state);
            if (state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state)) speed += 1000;
            return speed;
        };
        int best = -1;
        double bestScore = 1.0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            double sc = score.applyAsDouble(inv.getItem(i));
            if (sc > bestScore || (sc == bestScore && best >= Inventory.SELECTION_SIZE && i < Inventory.SELECTION_SIZE)) {
                best = i;
                bestScore = sc;
            }
        }
        if (best < 0) return false;
        if (best < Inventory.SELECTION_SIZE) inv.setSelectedSlot(best);
        else swapIn(mc, best);
        return true;
    }

    /** Swaps inventory slot {@code invSlot} (9-35) with one of the printer's hotbar slots and selects it. */
    private static void swapIn(Minecraft mc, int invSlot) {
        LocalPlayer player = mc.player;
        int hotbar = pickSlot(player.getInventory());
        // Inventory menu slots 9-35 are the main inventory, same numbers as Inventory.
        mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, invSlot, hotbar, ContainerInput.SWAP, player);
        player.getInventory().setSelectedSlot(hotbar);
    }

    /** An empty configured slot if there is one, otherwise the configured slots in turn. */
    private static int pickSlot(Inventory inv) {
        int[] slots = Configs.hotbarSlots();
        for (int s : slots) {
            if (inv.getItem(s).isEmpty()) return s;
        }
        nextSlot = (nextSlot + 1) % slots.length;
        return slots[nextSlot];
    }

    /**
     * Puts the best-scoring stack (score above 0) in the main hand; survival only, nothing is created. Ties go to the
     * hotbar. False when nothing in the inventory scores.
     */
    public static boolean selectBest(Minecraft mc, ToDoubleFunction<ItemStack> score) {
        Inventory inv = mc.player.getInventory();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            double sc = score.applyAsDouble(s);
            if (sc > bestScore || (sc == bestScore && best >= Inventory.SELECTION_SIZE && i < Inventory.SELECTION_SIZE)) {
                best = i;
                bestScore = sc;
            }
        }
        if (best < 0) return false;
        if (best < Inventory.SELECTION_SIZE) inv.setSelectedSlot(best);
        else swapIn(mc, best);
        return true;
    }

    /** True if some stack in the inventory passes the test. */
    public static boolean any(LocalPlayer player, Predicate<ItemStack> test) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(s)) return true;
        }
        return false;
    }

    /** Has the item in the inventory, creative or not. */
    public static boolean carries(LocalPlayer player, Item item) {
        return any(player, s -> s.is(item));
    }

    public static boolean has(LocalPlayer player, Item item) {
        if (player.hasInfiniteMaterials()) return true;
        Inventory inv = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }
}

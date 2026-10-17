package dev.steelaspect.cytraprinter.client.printer;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A block or item list from the settings: ids ("stone", "minecraft:oak_log") and tags ("#logs",
 * "#minecraft:wool"). Recompiled only when the list changes.
 */
final class IdFilter<T> {
    private final Registry<T> registry;
    private final ResourceKey<? extends Registry<T>> key;
    private List<String> source = null;
    private final Set<T> values = new HashSet<>();
    private final Set<TagKey<T>> tags = new HashSet<>();

    private IdFilter(Registry<T> registry) {
        this.registry = registry;
        this.key = registry.key();
    }

    static IdFilter<Block> blocks() {
        return new IdFilter<>(BuiltInRegistries.BLOCK);
    }

    static IdFilter<Item> items() {
        return new IdFilter<>(BuiltInRegistries.ITEM);
    }

    boolean matches(List<String> entries, T value) {
        if (!entries.equals(source)) compile(entries);
        if (values.contains(value)) return true;
        if (tags.isEmpty()) return false;
        var holder = registry.wrapAsHolder(value);
        for (TagKey<T> tag : tags) {
            if (holder.is(tag)) return true;
        }
        return false;
    }

    private void compile(List<String> entries) {
        source = List.copyOf(entries);
        values.clear();
        tags.clear();
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            boolean tag = entry.startsWith("#");
            Identifier id = Identifier.tryParse(tag ? entry.substring(1) : entry);
            if (id == null) continue;
            if (tag) tags.add(TagKey.create(key, id));
            else registry.getOptional(id).ifPresent(values::add);
        }
    }

    /** Id to store for a block: "minecraft:" is left out. */
    static String idOf(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
    }
}

package com.mcfriends.knowledge;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/** Turns a chat phrase ("wooden pickaxe", "sticks", "a crafting table") into an item. */
public final class ItemNames {
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("workbench", "crafting_table"), Map.entry("crafting bench", "crafting_table"), Map.entry("table", "crafting_table"),
		Map.entry("pick", "wooden_pickaxe"), Map.entry("wood pick", "wooden_pickaxe"), Map.entry("stone pick", "stone_pickaxe"),
		Map.entry("iron pick", "iron_pickaxe"), Map.entry("plank", "oak_planks"), Map.entry("planks", "oak_planks"),
		Map.entry("wood planks", "oak_planks"), Map.entry("wooden planks", "oak_planks"), Map.entry("chests", "chest"),
		Map.entry("bed", "white_bed"), Map.entry("boat", "oak_boat"), Map.entry("door", "oak_door"), Map.entry("fence", "oak_fence"));

	private ItemNames() {
	}

	@Nullable
	public static Item resolve(String phrase) {
		String p = phrase.toLowerCase(Locale.ROOT).trim().replaceAll("^(a|an|the|some|me)\\s+", "");
		if (p.isEmpty()) {
			return null;
		}
		String alias = ALIASES.get(p);
		if (alias != null) {
			return item(alias);
		}
		p = p.replaceAll("\\bwood\\b(?= (pickaxe|sword|axe|shovel|hoe))", "wooden").replaceAll("\\bgold\\b(?= (pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots))", "golden");
		String id = p.replace(' ', '_');
		Item it = item(id);
		if (it == null && id.endsWith("es")) {
			it = item(id.substring(0, id.length() - 2));
		}
		if (it == null && id.endsWith("s")) {
			it = item(id.substring(0, id.length() - 1));
		}
		if (it == null && !id.contains("_")) {
			// "pickaxe" -> "wooden_pickaxe": the cheapest version of a tool.
			it = item("wooden_" + id);
		}
		return it;
	}

	@Nullable
	private static Item item(String id) {
		Identifier ident = Identifier.tryParse("minecraft:" + id);
		if (ident == null || !Registries.ITEM.containsId(ident)) {
			return null;
		}
		Item it = Registries.ITEM.get(ident);
		return it == Items.AIR ? null : it;
	}

	/** "wooden_pickaxe" -> "wooden pickaxe". */
	public static String display(Item item) {
		return Registries.ITEM.getId(item).getPath().replace('_', ' ');
	}

	public static String id(Item item) {
		return Registries.ITEM.getId(item).getPath();
	}
}

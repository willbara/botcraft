package com.botcraft.colony;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** What a base chest is for. Items go to the chest for their kind first. */
public enum Purpose {
	ORES("ores and metals"),
	WOOD("wood"),
	FOOD("food"),
	GEAR("tools and gear"),
	BLOCKS("building blocks"),
	MISC("everything else");

	public final String label;

	Purpose(String label) {
		this.label = label;
	}

	/** The kind of thing this is. */
	public static Purpose of(ItemStack s) {
		var item = s.getItem();
		if (item.getFoodComponent() != null) {
			return FOOD;
		}
		if (s.isDamageable() || item instanceof ArmorItem || item == Items.SHIELD || item == Items.BUCKET || item == Items.WATER_BUCKET
			|| item == Items.LAVA_BUCKET || item == Items.TORCH) {
			return GEAR;
		}
		if (s.isIn(ItemTags.LOGS) || s.isIn(ItemTags.PLANKS) || item == Items.STICK || s.isIn(ItemTags.SAPLINGS) || s.isIn(ItemTags.WOODEN_SLABS)
			|| item == Items.CRAFTING_TABLE || item == Items.CHEST) {
			return WOOD;
		}
		if (com.botcraft.knowledge.OreKind.byDrop(item) != null && item != Items.COBBLESTONE && item != Items.COBBLED_DEEPSLATE
			|| item == Items.IRON_INGOT || item == Items.GOLD_INGOT || item == Items.COPPER_INGOT || item == Items.NETHERITE_INGOT
			|| item == Items.IRON_NUGGET || item == Items.GOLD_NUGGET || item == Items.CHARCOAL || item == Items.OBSIDIAN
			|| s.isIn(ItemTags.COAL_ORES) || s.isIn(ItemTags.IRON_ORES) || s.isIn(ItemTags.DIAMOND_ORES)) {
			return ORES;
		}
		if (item instanceof BlockItem) {
			return BLOCKS;
		}
		return MISC;
	}

	/** From what someone said ("ores", "food goes here", "the wood chest"). */
	@Nullable
	public static Purpose fromWords(String text) {
		String t = text.toLowerCase(Locale.ROOT);
		if (t.matches(".*\\b(ores?|minerals?|metals?|ingots?|iron|gold|coal|diamonds?|gems?|mining)\\b.*")) {
			return ORES;
		}
		if (t.matches(".*\\b(wood|woods|logs?|planks?|sticks?|timber|lumber)\\b.*")) {
			return WOOD;
		}
		if (t.matches(".*\\b(food|meat|foods|snacks|cooking|kitchen)\\b.*")) {
			return FOOD;
		}
		if (t.matches(".*\\b(tools?|weapons?|swords?|armou?r|gear|equipment)\\b.*")) {
			return GEAR;
		}
		if (t.matches(".*\\b(blocks?|stone|cobble(stone)?|dirt|building|builds?)\\b.*")) {
			return BLOCKS;
		}
		if (t.matches(".*\\b(misc|junk|everything else|other stuff|leftovers|odds and ends|random stuff)\\b.*")) {
			return MISC;
		}
		return null;
	}
}

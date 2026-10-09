package com.mcfriends.knowledge;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/** Farm crops: which seed plants what, maturity, and what can be tilled. */
public final class Crops {
	/** Crop block -> the item that plants it. */
	public static final Map<Block, Item> SEED_FOR = Map.of(
		Blocks.WHEAT, Items.WHEAT_SEEDS, Blocks.CARROTS, Items.CARROT, Blocks.POTATOES, Items.POTATO, Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

	private Crops() {
	}

	public static boolean isCrop(BlockState s) {
		return s.getBlock() instanceof CropBlock && SEED_FOR.containsKey(s.getBlock());
	}

	public static boolean isMature(BlockState s) {
		return s.getBlock() instanceof CropBlock crop && crop.isMature(s);
	}

	public static boolean isSeed(Item item) {
		return SEED_FOR.containsValue(item);
	}

	/** Blocks a hoe turns into farmland (with air above). */
	public static boolean tillable(BlockState s) {
		return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.ROOTED_DIRT);
	}

	/** Seed item for a chat word ("wheat", "carrots", "potatoes", "beetroot"), default wheat. */
	public static Item seedFor(String phrase) {
		String p = phrase == null ? "" : phrase.toLowerCase(Locale.ROOT);
		if (p.contains("carrot")) {
			return Items.CARROT;
		}
		if (p.contains("potato")) {
			return Items.POTATO;
		}
		if (p.contains("beet")) {
			return Items.BEETROOT_SEEDS;
		}
		return Items.WHEAT_SEEDS;
	}

	/** Plants we can break for wheat seeds. */
	public static boolean seedGrass(BlockState s) {
		return s.isOf(Blocks.GRASS) || s.isOf(Blocks.TALL_GRASS) || s.isOf(Blocks.FERN) || s.isOf(Blocks.LARGE_FERN);
	}

	@Nullable
	public static String cropName(Item seed) {
		if (seed == Items.CARROT) {
			return "carrots";
		}
		if (seed == Items.POTATO) {
			return "potatoes";
		}
		if (seed == Items.BEETROOT_SEEDS) {
			return "beetroots";
		}
		return seed == Items.WHEAT_SEEDS ? "wheat" : null;
	}
}

package com.mcfriends.knowledge;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Things a companion can mine, what they drop, which pickaxe they need and where to look. */
public enum OreKind {
	STONE("stone", null, Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE), Blocks.STONE, "wooden_pickaxe", Integer.MAX_VALUE),
	COAL("coal", BlockTags.COAL_ORES, Set.of(Items.COAL), Blocks.COAL_ORE, "wooden_pickaxe", 96),
	COPPER("copper", BlockTags.COPPER_ORES, Set.of(Items.RAW_COPPER), Blocks.COPPER_ORE, "stone_pickaxe", 64),
	IRON("iron", BlockTags.IRON_ORES, Set.of(Items.RAW_IRON), Blocks.IRON_ORE, "stone_pickaxe", 40),
	LAPIS("lapis", BlockTags.LAPIS_ORES, Set.of(Items.LAPIS_LAZULI), Blocks.LAPIS_ORE, "stone_pickaxe", 16),
	GOLD("gold", BlockTags.GOLD_ORES, Set.of(Items.RAW_GOLD), Blocks.GOLD_ORE, "iron_pickaxe", 16),
	REDSTONE("redstone", BlockTags.REDSTONE_ORES, Set.of(Items.REDSTONE), Blocks.REDSTONE_ORE, "iron_pickaxe", 0),
	DIAMOND("diamond", BlockTags.DIAMOND_ORES, Set.of(Items.DIAMOND), Blocks.DIAMOND_ORE, "iron_pickaxe", -40),
	EMERALD("emerald", BlockTags.EMERALD_ORES, Set.of(Items.EMERALD), Blocks.EMERALD_ORE, "iron_pickaxe", 200);

	public final String word;
	@Nullable
	private final TagKey<Block> tag;
	public final Set<Item> drops;
	private final Block sample;
	/** Cheapest pickaxe that can harvest it. */
	public final String pickaxe;
	/** Dig down when exploring above this Y (ores are more common below it). */
	public final int searchBelowY;

	OreKind(String word, @Nullable TagKey<Block> tag, Set<Item> drops, Block sample, String pickaxe, int searchBelowY) {
		this.word = word;
		this.tag = tag;
		this.drops = drops;
		this.sample = sample;
		this.pickaxe = pickaxe;
		this.searchBelowY = searchBelowY;
	}

	public Predicate<BlockState> blocks() {
		if (tag == null) {
			return s -> s.isOf(Blocks.STONE) || s.isOf(Blocks.DEEPSLATE);
		}
		return s -> s.isIn(tag);
	}

	public BlockState sample() {
		return sample.getDefaultState();
	}

	public boolean isDrop(ItemStack s) {
		return drops.contains(s.getItem());
	}

	/** Human-friendly plural noun for chat. */
	public String noun() {
		return switch (this) {
			case STONE -> "cobblestone";
			case COAL, REDSTONE, LAPIS -> word;
			case DIAMOND, EMERALD -> word + "s";
			default -> word + " ore";
		};
	}

	@Nullable
	public static OreKind byWord(String phrase) {
		String p = phrase.toLowerCase(Locale.ROOT).trim();
		if (p.matches("(raw )?(stone|stones|rock|rocks|cobble|cobblestone|cobbled deepslate|deepslate)")) {
			return STONE;
		}
		for (OreKind k : values()) {
			if (k == STONE) {
				continue;
			}
			if (p.matches("(raw )?" + k.word + "( ore| ores| lazuli)?s?")) {
				return k;
			}
		}
		return null;
	}

	@Nullable
	public static OreKind byDrop(Item item) {
		for (OreKind k : values()) {
			if (k.drops.contains(item)) {
				return k;
			}
		}
		return null;
	}
}

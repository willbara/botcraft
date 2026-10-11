package com.botcraft.knowledge;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterials;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Armour, tools and weapons as one body of knowledge, shared by free mode's progression and by
 * orders from chat: what "diamond armour" or "iron tools" means, how good a piece is, and
 * whether something is an upgrade on what a companion already has. Free mode and orders ask the
 * same questions here, so both understand gear the same way.
 */
public final class Gear {
	private Gear() {
	}

	/** Material -> pieces, best-known first in each kind. Only what can be crafted. */
	private static final Map<String, Item[]> ARMOUR = Map.of(
		"leather", new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
		"golden", new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS},
		"iron", new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
		"diamond", new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS});
	private static final Map<String, Item[]> TOOLS = Map.of(
		"wooden", new Item[]{Items.WOODEN_PICKAXE, Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_SHOVEL},
		"stone", new Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_SHOVEL},
		"golden", new Item[]{Items.GOLDEN_PICKAXE, Items.GOLDEN_SWORD, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL},
		"iron", new Item[]{Items.IRON_PICKAXE, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_SHOVEL},
		"diamond", new Item[]{Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL});

	private static final Pattern GROUP = Pattern.compile(
		"^(?:(?:a|an|the|some|my|your|full|whole|complete|set of|full set of|a full set of|a set of)\\s+)*"
			+ "(leather|gold|golden|iron|diamond|wood|wooden|stone)?\\s*"
			+ "(armou?r(?: set)?|armou?r pieces|tools?|tool set|gear|kit|equipment)$");

	/**
	 * A group of gear named in chat: "diamond armour" (helmet, chestplate, leggings, boots),
	 * "iron tools" (pickaxe, sword, axe, shovel), "diamond gear" (armour plus pickaxe, sword and
	 * axe). Plain "armour" means iron armour; tools and gear need a material. Null if the phrase
	 * isn't a group.
	 */
	@Nullable
	public static List<Item> group(String phrase) {
		Matcher m = GROUP.matcher(phrase.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " "));
		if (!m.matches() || (m.group(1) == null && !m.group(2).startsWith("armo"))) {
			return null;
		}
		String material = m.group(1) == null ? "iron" : switch (m.group(1)) {
			case "gold" -> "golden";
			case "wood" -> "wooden";
			default -> m.group(1);
		};
		String what = m.group(2);
		List<Item> out = new ArrayList<>();
		boolean armour = what.startsWith("armo");
		boolean tools = what.startsWith("tool");
		if (armour || !tools) {
			Item[] a = ARMOUR.get(material);
			if (a == null) {
				return null; // "stone armour"
			}
			// Chestplate first: the most protection for the materials.
			out.add(a[1]);
			out.add(a[2]);
			out.add(a[0]);
			out.add(a[3]);
		}
		if (tools || !armour) {
			Item[] t = TOOLS.get(material);
			if (t == null) {
				return null; // "leather tools"
			}
			for (int i = 0; i < (tools ? 4 : 3); i++) {
				out.add(t[i]);
			}
		}
		return out;
	}

	/** The words for a group, for replies: "diamond armour". */
	public static String describe(String phrase) {
		return phrase.toLowerCase(Locale.ROOT).trim().replaceAll("^(a|an|the|some|my|your|full|whole|complete|set of)\\s+", "")
			.replace("armor", "armour");
	}

	// ---- how good gear is ------------------------------------------------------------------------

	/** Protection of an armour piece (0 for anything else). */
	public static int protection(ItemStack s) {
		return s.getItem() instanceof ArmorItem a ? a.getProtection() : 0;
	}

	/** Tool tier: 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite; 0 if not a tool. */
	public static int tier(ItemStack s) {
		if (!(s.getItem() instanceof ToolItem t)) {
			return 0;
		}
		var m = t.getMaterial();
		return m == ToolMaterials.NETHERITE ? 5 : m == ToolMaterials.DIAMOND ? 4 : m == ToolMaterials.IRON ? 3 : m == ToolMaterials.STONE ? 2 : 1;
	}

	/** The kind of tool or weapon ("pickaxe", "sword", "axe", "shovel"), or null. */
	@Nullable
	public static String toolKind(ItemStack s) {
		Item it = s.getItem();
		return it instanceof PickaxeItem ? "pickaxe" : it instanceof SwordItem ? "sword" : it instanceof AxeItem ? "axe"
			: it instanceof ShovelItem ? "shovel" : null;
	}

	/** Best protection worn or carried for an armour slot (carried pieces get put on). */
	public static int armour(PlayerEntity p, EquipmentSlot slot) {
		int best = protection(p.getEquippedStack(slot));
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			if (inv.getStack(i).getItem() instanceof ArmorItem a && a.getSlotType() == slot) {
				best = Math.max(best, a.getProtection());
			}
		}
		return best;
	}

	/** Best tier carried of a kind of tool ("pickaxe", "sword"...). */
	public static int toolTier(PlayerEntity p, String kind) {
		int best = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && kind.equals(toolKind(s))) {
				best = Math.max(best, tier(s));
			}
		}
		return best;
	}

	/** Armour, or a tool or weapon with tiers (pickaxe, sword, axe, shovel). */
	public static boolean isGear(Item item) {
		return item instanceof ArmorItem || toolKind(new ItemStack(item)) != null;
	}

	/** Already has this piece or something at least as good in its place. */
	public static boolean hasAtLeast(PlayerEntity p, Item item) {
		ItemStack s = new ItemStack(item);
		if (item instanceof ArmorItem a) {
			return armour(p, a.getSlotType()) >= a.getProtection();
		}
		String kind = toolKind(s);
		if (kind != null) {
			return toolTier(p, kind) >= tier(s);
		}
		return false;
	}

	/**
	 * Base-chest gear better than what we wear or carry, best piece per armour slot and tool
	 * kind: item -> 1 for each piece worth taking. Empty without a base or stock.
	 * @param kinds which tool kinds to look at ("pickaxe", "sword"...); null = all
	 */
	public static java.util.LinkedHashMap<Item, Integer> fromStock(PlayerEntity p, Map<Item, Integer> stock,
		@Nullable java.util.Set<String> kinds, boolean armour) {
		Map<String, Item> best = new java.util.HashMap<>();
		Map<String, Integer> bestScore = new java.util.HashMap<>();
		for (Map.Entry<Item, Integer> e : stock.entrySet()) {
			ItemStack s = new ItemStack(e.getKey());
			if (e.getValue() <= 0 || !upgrade(p, s)) {
				continue;
			}
			String slot;
			int score;
			if (s.getItem() instanceof ArmorItem a) {
				if (!armour) {
					continue;
				}
				slot = a.getSlotType().getName();
				score = a.getProtection();
			} else {
				slot = toolKind(s);
				if (kinds != null && !kinds.contains(slot)) {
					continue;
				}
				score = tier(s);
			}
			if (score > bestScore.getOrDefault(slot, 0)) {
				bestScore.put(slot, score);
				best.put(slot, e.getKey());
			}
		}
		var out = new java.util.LinkedHashMap<Item, Integer>();
		best.values().forEach(it -> out.put(it, 1));
		return out;
	}

	/** Strictly better than what we wear or carry for its slot or kind: worth taking. */
	public static boolean upgrade(PlayerEntity p, ItemStack s) {
		if (s.getItem() instanceof ArmorItem a) {
			return a.getProtection() > armour(p, a.getSlotType());
		}
		String kind = toolKind(s);
		return kind != null && tier(s) > toolTier(p, kind);
	}
}

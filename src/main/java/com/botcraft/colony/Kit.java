package com.botcraft.colony;

import com.botcraft.action.Inventories;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a companion keeps on it, and what goes into the base chests. Like a player heading out:
 * the best tool of each kind, a weapon, some food, building blocks and torches. Everything else
 * (spare tools, logs, ores, ingots, coal, odds and ends) is stored, so it's safe if the
 * companion dies and anyone at the base can use it.
 */
public final class Kit {
	public static final int FOOD = 16;
	public static final int BLOCKS = 32;
	public static final int TORCHES = 16;

	/** Materials kept on hand (up to these amounts) because progress keeps needing them. */
	private static final Map<Predicate<ItemStack>, Integer> WORKING = new LinkedHashMap<>();

	static {
		WORKING.put(s -> s.isIn(net.minecraft.registry.tag.ItemTags.LOGS), 16);
		WORKING.put(s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 16);
		WORKING.put(s -> s.isOf(Items.STICK), 16);
		WORKING.put(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), 16);
		WORKING.put(s -> s.isOf(Items.IRON_INGOT), 32);
		WORKING.put(s -> s.isOf(Items.RAW_IRON), 32);
		WORKING.put(s -> s.isOf(Items.DIAMOND), 16);
		WORKING.put(s -> s.isOf(Items.CRAFTING_TABLE), 1);
		WORKING.put(s -> s.isOf(Items.FURNACE), 1);
		WORKING.put(s -> s.isOf(Items.WHITE_BED) || s.isIn(net.minecraft.registry.tag.ItemTags.BEDS), 1);
	}

	private Kit() {
	}

	/**
	 * Like {@link #toStore(PlayerInventory)}, but at least {@code n} of {@code extra} goes in too,
	 * even out of the kit: a batch fetched for the base is handed over, not kept as working stock.
	 */
	public static Map<Integer, Integer> toStore(PlayerInventory inv, @org.jetbrains.annotations.Nullable Predicate<ItemStack> extra, int n) {
		Map<Integer, Integer> out = toStore(inv);
		if (extra == null || n <= 0) {
			return out;
		}
		int already = 0;
		for (Map.Entry<Integer, Integer> e : out.entrySet()) {
			if (extra.test(inv.getStack(e.getKey()))) {
				already += e.getValue();
			}
		}
		for (int i = 0; i < inv.main.size() && already < n; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || toolKind(s) != null || !extra.test(s)) {
				continue;
			}
			int stored = out.getOrDefault(i, 0);
			int more = Math.min(s.getCount() - stored, n - already);
			if (more > 0) {
				out.put(i, stored + more);
				already += more;
			}
		}
		return out;
	}

	/** How many of this a companion keeps on itself (kit or working stock); 0 if it's all stored. */
	public static int keeps(ItemStack s) {
		if (s.getItem().getFoodComponent() != null) {
			return FOOD;
		}
		if (s.isOf(Items.TORCH)) {
			return TORCHES;
		}
		if (Inventories.isThrowaway(s)) {
			return BLOCKS;
		}
		for (Map.Entry<Predicate<ItemStack>, Integer> w : WORKING.entrySet()) {
			if (w.getKey().test(s)) {
				return w.getValue();
			}
		}
		return 0;
	}

	/** Slot -> how many of that stack to store. */
	public static Map<Integer, Integer> toStore(PlayerInventory inv) {
		Map<Integer, Integer> out = new LinkedHashMap<>();
		// Tools: keep the best of each kind (and one of each other useful tool), store the rest.
		Map<String, Integer> bestSlot = new HashMap<>();
		Map<String, Double> bestScore = new HashMap<>();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			String kind = toolKind(s);
			if (kind == null) {
				continue;
			}
			double score = Inventories.attackDamage(s) + (s.getItem() instanceof net.minecraft.item.ToolItem t ? t.getMaterial().getMiningSpeedMultiplier() : 0)
				- s.getDamage() / (double) Math.max(1, s.getMaxDamage());
			if (!bestScore.containsKey(kind) || score > bestScore.get(kind)) {
				if (bestSlot.containsKey(kind)) {
					out.put(bestSlot.get(kind), inv.getStack(bestSlot.get(kind)).getCount());
				}
				bestScore.put(kind, score);
				bestSlot.put(kind, i);
			} else {
				out.put(i, s.getCount());
			}
		}
		// Food: keep the best (cooked first), up to FOOD items.
		List<Integer> food = new ArrayList<>();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && s.getItem().getFoodComponent() != null && toolKind(s) == null) {
				food.add(i);
			}
		}
		food.sort(Comparator.comparingInt((Integer i) -> -inv.getStack(i).getItem().getFoodComponent().getHunger()));
		keepUpTo(inv, food, FOOD, out);
		// Building blocks and torches.
		List<Integer> blocks = new ArrayList<>();
		List<Integer> torches = new ArrayList<>();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (Inventories.isThrowaway(s)) {
				blocks.add(i);
			} else if (s.isOf(Items.TORCH)) {
				torches.add(i);
			}
		}
		keepUpTo(inv, blocks, BLOCKS, out);
		keepUpTo(inv, torches, TORCHES, out);
		// The working stock for making progress: what the next crafts and smelts need. Only what
		// goes beyond it is stored, so a companion doesn't put things away and fetch them right back.
		java.util.Set<Integer> working = new java.util.HashSet<>();
		for (Map.Entry<Predicate<ItemStack>, Integer> w : WORKING.entrySet()) {
			List<Integer> slots = new ArrayList<>();
			for (int i = 0; i < inv.main.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty() && toolKind(s) == null && !food.contains(i) && w.getKey().test(s)) {
					slots.add(i);
				}
			}
			working.addAll(slots);
			keepUpTo(inv, slots, w.getValue(), out);
		}
		// Everything else gets stored.
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || out.containsKey(i) || toolKind(s) != null || food.contains(i) || blocks.contains(i) || torches.contains(i)
				|| working.contains(i)) {
				continue;
			}
			out.put(i, s.getCount());
		}
		out.values().removeIf(n -> n <= 0);
		return out;
	}

	/** How many separate stacks (slots) would be stored. */
	public static int storeSlots(PlayerInventory inv) {
		return toStore(inv).size();
	}

	public static int emptySlots(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < inv.main.size(); i++) {
			if (inv.getStack(i).isEmpty()) {
				n++;
			}
		}
		return n;
	}

	private static void keepUpTo(PlayerInventory inv, List<Integer> slots, int keep, Map<Integer, Integer> out) {
		int left = keep;
		for (int i : slots) {
			int n = inv.getStack(i).getCount();
			int kept = Math.min(n, left);
			left -= kept;
			if (n - kept > 0) {
				out.put(i, n - kept);
			}
		}
	}

	/** Tools and gear kept one per kind; null for anything else. */
	private static String toolKind(ItemStack s) {
		Item it = s.getItem();
		if (it instanceof PickaxeItem) {
			return "pickaxe";
		}
		if (it instanceof AxeItem) {
			return "axe";
		}
		if (it instanceof SwordItem) {
			return "sword";
		}
		if (it instanceof ShovelItem) {
			return "shovel";
		}
		if (it instanceof HoeItem) {
			return "hoe";
		}
		if (it == Items.SHIELD || it == Items.BOW || it == Items.CROSSBOW || it == Items.SHEARS || it == Items.FLINT_AND_STEEL
			|| it == Items.FISHING_ROD || it == Items.BUCKET || it == Items.WATER_BUCKET || it == Items.LAVA_BUCKET) {
			return net.minecraft.registry.Registries.ITEM.getId(it).getPath();
		}
		return null;
	}
}

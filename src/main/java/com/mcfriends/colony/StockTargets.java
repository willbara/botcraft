package com.mcfriends.colony;

import com.mcfriends.action.Inventories;
import com.mcfriends.knowledge.Animals;
import com.mcfriends.knowledge.ItemNames;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the base should keep in its chests: the shared stockpile the colony works towards, as
 * opposed to each companion's own progression. A shortfall becomes a request on the
 * {@link com.mcfriends.brain.Board}; whoever is best placed takes it, gets a batch and stores it.
 *
 * <p>The defaults below can be changed (or switched off with 0) by the player, and the player can
 * add any other item ("keep 20 glass at the base").
 */
public final class StockTargets {
	/**
	 * One kind of thing to keep.
	 *
	 * @param key    stable id, also the delivery key for deposit/withdraw jobs
	 * @param counts which stacks count towards it
	 * @param item   what to plan for when getting more
	 * @param batch  how much one trip brings back
	 * @param weight how much a shortfall matters, relative to the others
	 */
	public record StockTarget(String key, String label, Predicate<ItemStack> counts, Item item, int defaultWant, int batch, double weight) {
		public int want(Colony colony) {
			return colony.want(key, defaultWant);
		}
	}

	private static final Predicate<ItemStack> COOKED = s -> Inventories.isGoodFood(s) && !Animals.RAW_MEAT.contains(s.getItem());

	public static final List<StockTarget> DEFAULTS = List.of(
		new StockTarget("food", "cooked food", COOKED, Items.COOKED_BEEF, 32, 6, 1.5),
		new StockTarget("coal", "coal", s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL), Items.COAL, 32, 8, 1.0),
		new StockTarget("logs", "logs", Inventories::isLog, Items.OAK_LOG, 64, 16, 1.0),
		new StockTarget("iron_ingot", "iron ingots", s -> s.isOf(Items.IRON_INGOT), Items.IRON_INGOT, 16, 6, 1.2),
		new StockTarget("torch", "torches", s -> s.isOf(Items.TORCH), Items.TORCH, 32, 16, 0.8),
		new StockTarget("cobblestone", "cobblestone", s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), Items.COBBLESTONE,
			64, 32, 0.6));

	private StockTargets() {
	}

	/** The defaults plus any other item the player asked the base to keep. */
	public static List<StockTarget> active(Colony colony) {
		List<StockTarget> out = new ArrayList<>(DEFAULTS);
		for (Map.Entry<String, Integer> e : colony.wants().entrySet()) {
			if (byKey(e.getKey()) == null) {
				StockTarget extra = custom(e.getKey());
				if (extra != null) {
					out.add(extra);
				}
			}
		}
		return out;
	}

	@Nullable
	public static StockTarget byKey(String key) {
		for (StockTarget t : DEFAULTS) {
			if (t.key().equals(key)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * The target for something the player named ("logs", "food", "torches", "glass"...): one of
	 * the defaults if it's one of those, else a target for that item. Null if it isn't an item.
	 */
	@Nullable
	public static StockTarget forPhrase(String phrase) {
		String p = phrase.toLowerCase(java.util.Locale.ROOT).trim();
		if (p.matches("(some |any )?(food|meat|cooked food|something to eat)")) {
			return byKey("food");
		}
		if (p.matches("logs?|wood")) {
			return byKey("logs");
		}
		if (p.matches("coal|charcoal")) {
			return byKey("coal");
		}
		if (p.matches("iron|iron ingots?|ingots?")) {
			return byKey("iron_ingot");
		}
		if (p.matches("torch(es)?")) {
			return byKey("torch");
		}
		if (p.matches("cobble(stone)?|stone|cobblestones")) {
			return byKey("cobblestone");
		}
		Item it = ItemNames.resolve(p);
		return it == null ? null : custom(ItemNames.id(it));
	}

	@Nullable
	private static StockTarget custom(String id) {
		Item it = ItemNames.resolve(id.replace('_', ' '));
		if (it == null || it == Items.AIR) {
			return null;
		}
		String label = ItemNames.plural(ItemNames.display(it), 2);
		return new StockTarget(ItemNames.id(it), label, s -> s.isOf(it), it, 0, Math.max(1, Math.min(16, it.getMaxCount())), 1.0);
	}
}

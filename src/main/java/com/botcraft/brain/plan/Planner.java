package com.botcraft.brain.plan;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Jobs;
import com.botcraft.brain.task.CraftTask;
import com.botcraft.colony.Colony;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.Animals;
import com.botcraft.knowledge.CraftPlanner;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.knowledge.OreKind;
import com.botcraft.knowledge.Smelting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hierarchical planning for "get N of X": every way we know (already have it, the base chests,
 * craft it, smelt it, mine it, hunt it, chop it) is a method; methods that need other things
 * plan for those too (an iron pickaxe means ingots and sticks; ingots mean raw iron and fuel;
 * raw iron means mining, which means a stone pickaxe...). Each method gets a cost in seconds from
 * what we know: distances to the base and to known ore or animals, what we learned about how long
 * things take and how often they work, deaths, and whether it's night. The cheapest wins.
 *
 * <p>The plan is a list of ordinary job specs. The tasks still check reality as they run (if
 * the chest turns out empty, crafting re-plans), so the planner chooses the approach; it doesn't
 * have to be perfect.
 */
public final class Planner {
	/** One way to get the thing. {@code cost} is in seconds; infinite = not possible. */
	public record Option(String method, double cost, List<String> steps, String note) {
		public boolean possible() {
			return cost < Double.POSITIVE_INFINITY;
		}
	}

	/** The choice for one goal, with everything that was considered (for /companion why). */
	public record Decision(String goal, Option chosen, List<Option> considered) {
	}

	private static final int MAX_DEPTH = 4;
	/** Walking speed for travel estimates. */
	private static final double BLOCKS_PER_SECOND = 4.0;
	private static final Option IMPOSSIBLE = new Option("none", Double.POSITIVE_INFINITY, List.of(), "no way known");

	private final Companion c;
	private final CompanionPlayer p;
	/** What we have to work with: carried, minus what earlier parts of this plan already used. */
	private final Map<Item, Integer> carried;
	private final Map<Item, Integer> stock;
	private final boolean fetch;
	private final Map<String, Option> memo = new HashMap<>();

	private Planner(Companion c) {
		this.c = c;
		this.p = c.player();
		this.carried = new HashMap<>(CraftTask.inventoryCounts(p.getInventory()));
		this.fetch = Jobs.canFetch(c);
		Colony colony = Colony.get();
		this.stock = fetch && colony != null ? new HashMap<>(colony.stock()) : new HashMap<>();
	}

	/**
	 * The best way to bring {@code n} new {@code item} to the base: what's already stored there
	 * (or carried as working stock) of the thing itself doesn't count, but other stored things can
	 * still be used to make it (coal and sticks for torches, raw iron for ingots).
	 */
	public static Decision acquireForBase(Companion c, Item item, int n, java.util.function.Predicate<ItemStack> same) {
		Planner planner = new Planner(c);
		planner.carried.keySet().removeIf(it -> same.test(new ItemStack(it)));
		planner.stock.keySet().removeIf(it -> same.test(new ItemStack(it)));
		List<Option> options = planner.options(item, n, 0);
		Option best = options.stream().filter(Option::possible).min(Comparator.comparingDouble(Option::cost)).orElse(IMPOSSIBLE);
		return new Decision(ItemNames.display(item) + (n > 1 ? " x" + n : "") + " for the base", best, options);
	}

	/** Seconds to walk from where we are to the base (0 without one). */
	public static double secondsToBase(Companion c) {
		Colony colony = Colony.get();
		BlockPos base = colony == null ? null : colony.base();
		return base == null ? 0 : Math.sqrt(base.getSquaredDistance(c.player().getBlockPos())) / BLOCKS_PER_SECOND;
	}

	/** The best way to end up with {@code n} of {@code item}, and what else was considered. */
	public static Decision acquire(Companion c, Item item, int n) {
		Planner planner = new Planner(c);
		List<Option> options = planner.options(item, n, 0);
		Option best = options.stream().filter(Option::possible).min(Comparator.comparingDouble(Option::cost)).orElse(IMPOSSIBLE);
		return new Decision(ItemNames.display(item) + (n > 1 ? " x" + n : ""), best, options);
	}

	// ---- methods ----------------------------------------------------------------------------------

	private Option best(Item item, int n, int depth) {
		String key = ItemNames.id(item) + "x" + n;
		Option cached = memo.get(key);
		if (cached != null) {
			return cached;
		}
		Option best = options(item, n, depth).stream().filter(Option::possible).min(Comparator.comparingDouble(Option::cost)).orElse(IMPOSSIBLE);
		memo.put(key, best);
		return best;
	}

	private List<Option> options(Item item, int n, int depth) {
		List<Option> out = new ArrayList<>();
		int have = carried.getOrDefault(item, 0);
		if (have >= n) {
			out.add(new Option("have", 0, List.of(), "already carrying " + have));
			return out;
		}
		int need = n - have;
		if (depth > MAX_DEPTH) {
			return out;
		}
		String id = ItemNames.id(item);
		// The base chests.
		int stored = stock.getOrDefault(item, 0);
		if (stored > 0) {
			int take = Math.min(stored, need);
			double cost = travelToBase() + 4;
			List<String> steps = new ArrayList<>(List.of("withdraw " + id + " " + take));
			String note = take + " in the chests";
			if (take < need) {
				Option rest = bestExcept(item, need - take, depth, "chests");
				cost += rest.cost();
				steps.addAll(rest.steps());
				note += ", rest by " + rest.method();
			}
			out.add(new Option("chests", cost, steps, note));
		}
		// Craft it.
		if (CraftPlanner.craftable(c.server, item) && !isRawMaterial(item)) {
			out.add(craft(item, need, depth));
		}
		// Smelt it.
		if (Smelting.smeltable(c.server, item)) {
			out.add(smelt(item, need, depth));
		}
		// Mine it.
		OreKind ore = OreKind.byDrop(item);
		if (ore != null) {
			out.add(mine(ore, need, depth));
		}
		// Hunt it.
		String animal = animalFor(item);
		if (animal != null) {
			double perUnit = MethodStats.secondsPerUnit(c, "hunt " + animal, 25);
			double cost = (perUnit * need + herdDistance() / BLOCKS_PER_SECOND) * risk("hunt " + animal, true);
			out.add(new Option("hunt", cost, List.of("hunt " + animal + " " + need), "about " + Math.round(perUnit) + "s each"));
		}
		// Chop it.
		if (new ItemStack(item).isIn(ItemTags.LOGS)) {
			double perUnit = MethodStats.secondsPerUnit(c, "gather logs", 6);
			double cost = (perUnit * need + nearest("tree", 64) / BLOCKS_PER_SECOND) * risk("gather logs", true);
			out.add(new Option("chop", cost, List.of("gather logs " + need), "about " + Math.round(perUnit) + "s a log"));
		}
		if (out.isEmpty()) {
			out.add(IMPOSSIBLE);
		}
		return out;
	}

	private Option bestExcept(Item item, int n, int depth, String method) {
		return options(item, n, depth + 1).stream().filter(o -> o.possible() && !o.method().equals(method))
			.min(Comparator.comparingDouble(Option::cost)).orElse(IMPOSSIBLE);
	}

	private Option craft(Item item, int need, int depth) {
		// What the recipe tree needs, using what we carry and (if close enough) what's stored.
		Map<Item, Integer> pile = new HashMap<>(carried);
		stock.forEach((it, k) -> pile.merge(it, k, Integer::sum));
		boolean table = Inventories.count(p.getInventory(), s -> s.isOf(Items.CRAFTING_TABLE)) == 0 && !tableNear();
		CraftPlanner.Plan plan = CraftPlanner.plan(c.server, pile, item, need, table);
		if (plan.problem() != null) {
			return new Option("craft", Double.POSITIVE_INFINITY, List.of(), plan.problem());
		}
		List<String> steps = new ArrayList<>();
		double cost = 3.0 * plan.crafts().size() + (table ? 5 : 0);
		// Take from the chests what the plan uses from them.
		boolean fromChests = false;
		for (Map.Entry<Item, Integer> e : plan.used().entrySet()) {
			int take = Math.min(e.getValue() - carried.getOrDefault(e.getKey(), 0), stock.getOrDefault(e.getKey(), 0));
			if (take > 0) {
				steps.add("withdraw " + ItemNames.id(e.getKey()) + " " + take);
				fromChests = true;
			}
		}
		if (fromChests) {
			cost += travelToBase() + 4;
		}
		// Valuable materials used up count too: a chestplate made from 8 diamonds isn't "3 seconds"
		// when one is waiting in a chest. Half of what mining them again would take.
		for (Map.Entry<Item, Integer> e : com.botcraft.util.Experiments.on("craftMaterialCost") ? plan.used().entrySet() : Map.<Item, Integer>of().entrySet()) {
			OreKind ore = oreBehind(e.getKey());
			if (ore != null && ore != OreKind.STONE) {
				cost += 0.5 * e.getValue() * MethodStats.secondsPerUnit(c, "mine " + ore.word, orePrior(ore));
			}
		}
		// Whatever's still missing, get it the best way we can.
		for (Map.Entry<Item, Integer> e : plan.missing().entrySet()) {
			Option sub = best(e.getKey(), e.getValue(), depth + 1);
			if (!sub.possible()) {
				return new Option("craft", Double.POSITIVE_INFINITY, List.of(), "can't get " + ItemNames.display(e.getKey()));
			}
			cost += sub.cost();
			steps.addAll(sub.steps());
		}
		steps.add("craft " + ItemNames.id(item) + " " + need);
		return new Option("craft", cost, steps, plan.missing().isEmpty() ? "have everything for it" : "needs " + describe(plan.missing()));
	}

	private Option smelt(Item item, int need, int depth) {
		Option bestInput = IMPOSSIBLE;
		for (SmeltingRecipe r : Smelting.allForOutput(c.server, item)) {
			for (ItemStack in : Smelting.input(r).getMatchingStacks()) {
				Option o = best(in.getItem(), need, depth + 1);
				if (o.possible() && o.cost() < bestInput.cost()) {
					bestInput = o;
				}
			}
		}
		if (!bestInput.possible()) {
			return new Option("smelt", Double.POSITIVE_INFINITY, List.of(), "nothing to smelt it from");
		}
		// 10 s per item in a furnace, plus fuel if we're short.
		double cost = bestInput.cost() + 10.0 * need + 6;
		List<String> steps = new ArrayList<>(bestInput.steps());
		if (Smelting.fuelAvailable(p.getInventory(), null) < 200 * need && stock.getOrDefault(Items.COAL, 0) == 0) {
			cost += 15; // fetch or chop some fuel on the way
		}
		steps.add("smelt " + ItemNames.id(item) + " " + need);
		return new Option("smelt", cost, steps, "from " + bestInput.method());
	}

	private Option mine(OreKind ore, int need, int depth) {
		String method = "mine " + ore.word;
		List<String> steps = new ArrayList<>();
		double cost = 0;
		// The right pickaxe first.
		Item pick = ItemNames.resolve(ore.pickaxe.replace('_', ' '));
		if (pick != null && !hasPickaxeFor(ore)) {
			Option tool = best(pick, 1, depth + 1);
			if (!tool.possible()) {
				return new Option("mine", Double.POSITIVE_INFINITY, List.of(), "no way to get a " + ItemNames.display(pick));
			}
			cost += tool.cost();
			steps.addAll(tool.steps());
		}
		double perUnit = MethodStats.secondsPerUnit(c, method, orePrior(ore));
		double travel = ore == OreKind.STONE ? 0 : Math.min(nearest(ore.word, 128), nearest("cave", 128)) / BLOCKS_PER_SECOND;
		cost += (perUnit * need + travel) * risk(method, false) * (ore.deep() ? 1.4 : 1.0);
		steps.add("mine " + ore.word + " " + need);
		return new Option("mine", cost, steps, "about " + Math.round(perUnit) + "s each" + (MethodStats.attempts(c, method) > 0
			? ", " + Math.round(MethodStats.successRate(c, method) * 100) + "% of tries worked" : ""));
	}

	/** Seconds to mine one, before we've learned better. */
	private static double orePrior(OreKind ore) {
		return switch (ore) {
			case STONE -> 4;
			case COAL -> 25;
			case COPPER, IRON -> 45;
			case LAPIS, REDSTONE, GOLD -> 90;
			case DIAMOND -> 240;
			case EMERALD -> 400;
		};
	}

	/** The ore an item comes from: its drop (diamond, raw iron) or the ingot smelted from it. */
	@org.jetbrains.annotations.Nullable
	private static OreKind oreBehind(Item item) {
		if (item == Items.IRON_INGOT) {
			return OreKind.IRON;
		}
		if (item == Items.GOLD_INGOT) {
			return OreKind.GOLD;
		}
		if (item == Items.COPPER_INGOT) {
			return OreKind.COPPER;
		}
		return OreKind.byDrop(item);
	}

	// ---- beliefs -----------------------------------------------------------------------------------

	/**
	 * Experience and danger: methods that fail often, or have killed us, cost more; so does being
	 * out on the surface at night.
	 */
	private double risk(String method, boolean outdoors) {
		double r = 1.0 / Math.max(0.15, MethodStats.successRate(c, method));
		r *= 1.0 + 0.5 * MethodStats.deaths(c, method);
		if (outdoors && c.perception().isNight()) {
			r *= 2.0;
		}
		return r;
	}

	private double travelToBase() {
		Colony colony = Colony.get();
		BlockPos base = colony == null ? null : colony.base();
		return base == null ? 0 : Math.sqrt(base.getSquaredDistance(p.getBlockPos())) / BLOCKS_PER_SECOND;
	}

	/** Distance to the nearest remembered sighting of a kind, or a guess if we know of none. */
	private double nearest(String kind, double guess) {
		var seen = c.memory().sightings(kind, p.getBlockPos(), c.dimension(), 256);
		return seen.isEmpty() ? guess : Math.sqrt(seen.get(0).pos().getSquaredDistance(p.getBlockPos()));
	}

	private double herdDistance() {
		return nearest("herd", 96);
	}

	private boolean tableNear() {
		for (BlockPos pos : BlockPos.iterate(p.getBlockPos().add(-6, -2, -6), p.getBlockPos().add(6, 2, 6))) {
			if (p.getWorld().getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasPickaxeFor(OreKind ore) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && s.isSuitableFor(ore.sample())) {
				return true;
			}
		}
		return false;
	}

	/** Things we'd rather gather than "craft" (logs from wood blocks, ores from storage blocks...). */
	private static boolean isRawMaterial(Item item) {
		ItemStack s = new ItemStack(item);
		return s.isIn(ItemTags.LOGS) || OreKind.byDrop(item) != null || Animals.RAW_MEAT.contains(item);
	}

	private static String animalFor(Item item) {
		if (item == Items.BEEF || item == Items.LEATHER) {
			return "cow";
		}
		if (item == Items.PORKCHOP) {
			return "pig";
		}
		if (item == Items.MUTTON || new ItemStack(item).isIn(ItemTags.WOOL)) {
			return "sheep";
		}
		if (item == Items.CHICKEN || item == Items.FEATHER) {
			return "chicken";
		}
		return null;
	}

	private static String describe(Map<Item, Integer> items) {
		List<String> parts = new ArrayList<>();
		items.forEach((it, k) -> parts.add(k + " " + ItemNames.display(it)));
		return String.join(", ", parts);
	}
}

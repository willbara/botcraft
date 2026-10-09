package com.mcfriends.knowledge;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recipe planner over the server's crafting recipes (so data packs are respected).
 *
 * <p>Given what is in the inventory, it works out which crafts to do, in order, to make
 * {@code count} of an item, and which raw materials are missing. Raw materials are items with
 * a known way to get them (logs: chop trees; cobblestone, coal, raw ores: mine) or items that
 * have no crafting recipe (reported as unobtainable, e.g. iron ingots, which need smelting).
 * Depth-limited, greedy: for "any planks" style ingredients it prefers what we already have,
 * then what we can make from what we have.
 */
public final class CraftPlanner {
	private static final int MAX_DEPTH = 6;

	public record Craft(CraftingRecipe recipe, Item output, int times) {
	}

	public record Plan(List<Craft> crafts, Map<Item, Integer> missing, @Nullable String problem, boolean needsTable) {
		public boolean ready() {
			return problem == null && missing.isEmpty();
		}
	}

	private final MinecraftServer server;
	private final Map<Item, List<CraftingRecipe>> byOutput = new HashMap<>();
	private final Map<Item, Integer> have;
	private final List<Craft> crafts = new ArrayList<>();
	private final Map<Item, Integer> missing = new LinkedHashMap<>();
	private String problem;

	private CraftPlanner(MinecraftServer server, Map<Item, Integer> inventory) {
		this.server = server;
		this.have = new HashMap<>(inventory);
		for (CraftingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			if (r.isIgnoredInRecipeBook() || r.getIngredients().isEmpty()) {
				continue; // special recipes (map cloning, dyeing armour...) can't be planned
			}
			ItemStack out = r.getOutput(server.getRegistryManager());
			if (!out.isEmpty()) {
				byOutput.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(r);
			}
		}
	}

	public static Plan plan(MinecraftServer server, Map<Item, Integer> inventory, Item target, int count) {
		CraftPlanner p = new CraftPlanner(server, inventory);
		p.need(target, count, 0, true);
		boolean table = false;
		for (Craft c : p.crafts) {
			table |= !c.recipe().fits(2, 2);
		}
		return new Plan(p.crafts, p.missing, p.problem, table);
	}

	public static boolean craftable(MinecraftServer server, Item item) {
		for (CraftingRecipe r : server.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			if (!r.isIgnoredInRecipeBook() && r.getOutput(server.getRegistryManager()).isOf(item)) {
				return true;
			}
		}
		return false;
	}

	/** Items we know how to collect directly rather than craft. */
	public static boolean isGatherable(Item item) {
		return new ItemStack(item).isIn(ItemTags.LOGS) || OreKind.byDrop(item) != null;
	}

	private void need(Item item, int n, int depth, boolean isTarget) {
		if (problem != null || n <= 0) {
			return;
		}
		if (!isTarget) {
			int available = have.getOrDefault(item, 0);
			int use = Math.min(available, n);
			have.put(item, available - use);
			n -= use;
			if (n == 0) {
				return;
			}
		}
		if (isGatherable(item)) {
			missing.merge(item, n, Integer::sum);
			return;
		}
		List<CraftingRecipe> recipes = byOutput.get(item);
		if (recipes == null || recipes.isEmpty()) {
			problem = "I don't know how to get " + ItemNames.display(item) + (needsSmelting(item) ? " (it needs smelting)" : "");
			return;
		}
		if (depth > MAX_DEPTH) {
			problem = "that recipe is too deep for me";
			return;
		}
		CraftingRecipe recipe = chooseRecipe(recipes);
		int perCraft = recipe.getOutput(server.getRegistryManager()).getCount();
		int times = (n + perCraft - 1) / perCraft;
		// Count each concrete ingredient choice across the grid, then satisfy them recursively.
		Map<Item, Integer> inputs = new LinkedHashMap<>();
		for (Ingredient ing : recipe.getIngredients()) {
			if (ing.isEmpty()) {
				continue;
			}
			Item choice = chooseOption(ing);
			if (choice == null) {
				problem = "a recipe for " + ItemNames.display(item) + " has an ingredient I can't use";
				return;
			}
			inputs.merge(choice, times, Integer::sum);
		}
		for (Map.Entry<Item, Integer> e : inputs.entrySet()) {
			need(e.getKey(), e.getValue(), depth + 1, false);
		}
		crafts.add(new Craft(recipe, item, times));
		int surplus = times * perCraft - n;
		have.merge(item, surplus + (isTarget ? n : 0), Integer::sum);
	}

	private static boolean needsSmelting(Item item) {
		String id = ItemNames.id(item);
		return id.endsWith("_ingot") || id.equals("glass") || id.equals("stone") || id.equals("charcoal") || id.startsWith("cooked_") || id.equals("smooth_stone");
	}

	/** Prefer a recipe whose ingredients we can already cover; otherwise the simplest one. */
	private CraftingRecipe chooseRecipe(List<CraftingRecipe> recipes) {
		CraftingRecipe best = recipes.get(0);
		int bestScore = Integer.MIN_VALUE;
		for (CraftingRecipe r : recipes) {
			int score = 0;
			int kinds = 0;
			for (Ingredient ing : r.getIngredients()) {
				if (ing.isEmpty()) {
					continue;
				}
				kinds++;
				Item opt = chooseOption(ing);
				if (opt == null) {
					score -= 100; // nothing we could ever get
				} else if (have.getOrDefault(opt, 0) > 0) {
					score += 10;
				} else if (isGatherable(opt)) {
					score += 2;
				} else if (byOutput.containsKey(opt)) {
					score += 1;
				}
			}
			score -= kinds;
			if (score > bestScore) {
				bestScore = score;
				best = r;
			}
		}
		return best;
	}

	/**
	 * For ingredients with several options ("any planks"): one we have, else one craftable from
	 * something we have (birch planks when holding birch logs), else the first gatherable or
	 * craftable option.
	 */
	@Nullable
	private Item chooseOption(Ingredient ing) {
		ItemStack[] options = ing.getMatchingStacks();
		Item fallback = null;
		for (ItemStack s : options) {
			if (have.getOrDefault(s.getItem(), 0) > 0) {
				return s.getItem();
			}
		}
		for (ItemStack s : options) {
			List<CraftingRecipe> rs = byOutput.get(s.getItem());
			if (rs != null) {
				for (CraftingRecipe r : rs) {
					for (Ingredient in : r.getIngredients()) {
						for (ItemStack o : in.getMatchingStacks()) {
							if (have.getOrDefault(o.getItem(), 0) > 0) {
								return s.getItem();
							}
						}
					}
				}
			}
			if (fallback == null && (rs != null || isGatherable(s.getItem()))) {
				fallback = s.getItem();
			}
		}
		return fallback;
	}
}

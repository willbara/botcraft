package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.task.CraftTask;
import com.botcraft.brain.task.DeliverTask;
import com.botcraft.brain.task.GatherLogsTask;
import com.botcraft.brain.task.GoToTask;
import com.botcraft.brain.task.HuntTask;
import com.botcraft.brain.task.SmeltTask;
import com.botcraft.knowledge.Animals;
import com.botcraft.knowledge.CraftPlanner;
import com.botcraft.knowledge.Smelting;
import com.botcraft.brain.task.MineTask;
import com.botcraft.brain.task.SleepTask;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.knowledge.OreKind;
import com.botcraft.memory.Memory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Job specs: short persisted strings describing a job, turned into tasks when the job starts.
 * <pre>
 *   gather logs N        mine &lt;ore|stone&gt; N      craft &lt;item_id&gt; N
 *   smelt &lt;item_id|food&gt; N                       hunt &lt;animal|meat&gt; N
 *   deliver &lt;logs|ore|item_id|all&gt; N             goto X Y Z [label]
 *   home                 sleep                tidy
 * </pre>
 * A trailing "~N" counts how often a job deferred to its prerequisites (loop guard).
 */
public final class Jobs {
	private Jobs() {
	}

	@Nullable
	public static Task create(Companion c, String spec) {
		String[] w = base(spec).split(" ");
		try {
			switch (w[0]) {
				case "gather" -> {
					return new GatherLogsTask(c, num(w, 2, 16));
				}
				case "mine" -> {
					OreKind k = w.length > 1 ? OreKind.byWord(w[1]) : null;
					return k == null ? null : new MineTask(c, k, num(w, 2, 8));
				}
				case "craft" -> {
					Item it = w.length > 1 ? ItemNames.resolve(w[1].replace('_', ' ')) : null;
					return it == null ? null : new CraftTask(c, it, num(w, 2, 1));
				}
				case "deliver" -> {
					String what = w.length > 1 ? w[1] : "logs";
					String to = w.length > 4 && w[3].equals("to") ? w[4] : null;
					return new DeliverTask(c, deliverable(what), label(what), num(w, 2, 64), to);
				}
				case "goto" -> {
					BlockPos pos = new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
					String label = w.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(w, 4, w.length)) : "there";
					return new GoToTask(c, pos, label);
				}
				case "home" -> {
					Memory.Place home = c.memory().place("home");
					return home == null ? null : new GoToTask(c, home.pos(), "home");
				}
				case "sleep" -> {
					return new SleepTask(c);
				}
				case "collect" -> {
					return new com.botcraft.brain.task.CollectTask(c);
				}
				case "light" -> {
					return new com.botcraft.brain.task.LightHomeTask(c);
				}
				case "tidy" -> {
					return new com.botcraft.brain.task.TidyTask(c);
				}
				case "raid" -> {
					return w.length > 1 && com.botcraft.faction.Factions.get(w[1]) != null ? new com.botcraft.brain.task.RaidTask(c, w[1]) : null;
				}
				case "found" -> {
					return new com.botcraft.brain.task.FoundTask(c);
				}
				case "farm" -> {
					return new com.botcraft.brain.task.FarmTask(c, num(w, 1, 0));
				}
				case "fish" -> {
					return new com.botcraft.brain.task.FishTask(c, num(w, 1, 5));
				}
				case "dance" -> {
					return new com.botcraft.brain.task.DanceTask(c);
				}
				case "deposit" -> {
					// "deposit" stores the extras; "deposit coal 8" also hands over a batch for the base.
					return w.length > 1 ? new com.botcraft.brain.task.DepositTask(c, deliverable(w[1]), num(w, 2, 1),
						ItemNames.plural(label(w[1]), num(w, 2, 1)))
						: new com.botcraft.brain.task.DepositTask(c);
				}
				case "gear" -> {
					Item it = ItemNames.resolve(w[1].replace('_', ' '));
					return it == null ? null : new com.botcraft.brain.task.GearTask(c, it);
				}
				case "withdraw" -> {
					String what = w.length > 1 ? w[1] : "food";
					return new com.botcraft.brain.task.WithdrawTask(c, deliverable(what), label(what), num(w, 2, 1));
				}
				case "shelter" -> {
					return new com.botcraft.brain.task.ShelterTask(c);
				}
				case "smelt" -> {
					String what = w.length > 1 ? w[1] : "food";
					Item it = what.equals("food") ? null : ItemNames.resolve(what.replace('_', ' '));
					if (it == null && !what.equals("food")) {
						return null;
					}
					return new SmeltTask(c, it, num(w, 2, 1));
				}
				case "place" -> {
					Item it = ItemNames.resolve(w[1].replace('_', ' '));
					BlockPos at = new BlockPos(Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]));
					return it == null ? null : new com.botcraft.brain.task.PlaceTask(c, it, at);
				}
				case "await" -> {
					return new com.botcraft.brain.task.AwaitTask(c, w[1], num(w, 2, 1), w.length > 3 ? w[3] : "a friend");
				}
				case "hunt" -> {
					var species = Animals.byWord(w.length > 1 ? w[1] : "meat");
					return species == null ? null : new HuntTask(c, species, num(w, 2, 3));
				}
				default -> {
					return null;
				}
			}
		} catch (RuntimeException e) {
			return null; // malformed spec
		}
	}

	/** Human description for acknowledgements: "chop 16 logs", "mine 8 coal", "craft a wooden pickaxe". */
	public static String describe(String spec) {
		String[] w = base(spec).split(" ");
		return switch (w[0]) {
			case "gather" -> "chop " + num(w, 2, 16) + " logs";
			case "mine" -> {
				OreKind k = w.length > 1 ? OreKind.byWord(w[1]) : null;
				yield "mine " + num(w, 2, 8) + " " + (k == null ? w[1] : k.noun());
			}
			case "craft" -> {
				int n = num(w, 2, 1);
				String item = w.length > 1 ? w[1].replace('_', ' ') : "something";
				yield "craft " + (n == 1 ? (item.matches("^[aeiou].*") ? "an " : "a ") + item : n + " " + ItemNames.plural(item, n));
			}
			case "deliver" -> "bring " + (w.length > 4 && w[3].equals("to") ? w[4] : "you") + " " + label(w.length > 1 ? w[1] : "logs");
			case "goto" -> "go " + (w.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(w, 4, w.length)) : "there");
			case "home" -> "go home";
			case "sleep" -> "go to bed";
			case "shelter" -> "dig in for the night";
			case "deposit" -> w.length > 1 ? "bring " + num(w, 2, 1) + " " + ItemNames.plural(label(w[1]), num(w, 2, 1)) + " to the base" : "put my things away";
			case "dance" -> "dance";
			case "light" -> "light up the base";
			case "tidy" -> "tidy up the base";
			case "farm" -> "tend the farm";
			case "fish" -> "go fishing";
			case "found" -> "found our base";
			case "raid" -> {
				var f = w.length > 1 ? com.botcraft.faction.Factions.get(w[1]) : null;
				yield "raid " + (f == null ? (w.length > 1 ? w[1] : "the enemy") : f.name);
			}
			case "withdraw" -> "get " + label(w.length > 1 ? w[1] : "food") + " from the chests";
			case "gear" -> "get " + (w.length > 1 ? "a " + w[1].replace('_', ' ') : "gear");
			case "smelt" -> {
				String what = w.length > 1 ? w[1].replace('_', ' ') : "food";
				int n = num(w, 2, 1);
				if (what.equals("food")) {
					yield "cook some food";
				}
				if (what.startsWith("cooked ")) {
					yield "cook the " + what.substring(7);
				}
				yield "smelt " + n + " " + ItemNames.plural(what, n);
			}
			case "place" -> "set up a " + (w.length > 1 ? w[1].replace('_', ' ') : "block");
			case "await" -> "wait for " + (w.length > 3 ? w[3] : "a friend");
			case "hunt" -> {
				String what = w.length > 1 ? w[1].replaceAll("s$", "") : "meat";
				int n = num(w, 2, 3);
				if (what.equals("meat")) {
					yield "hunt for food";
				}
				yield n == 1 ? "hunt a " + what : "hunt " + n + " " + ItemNames.plural(what, n);
			}
			default -> spec;
		};
	}

	/**
	 * The job that gets {@code n} of an item: chop logs, mine ores/stone, smelt ingots/glass/
	 * cooked food, hunt for raw meat, craft anything with a recipe. Null if we don't know how.
	 */
	@Nullable
	public static String sourceSpec(Companion c, Item item, int n) {
		// The base chests come first: no point mining iron that's sitting at home.
		String fromStock = withdrawSpec(c, ItemNames.id(item), s -> s.isOf(item), n);
		if (fromStock != null) {
			return fromStock;
		}
		ItemStack stack = new ItemStack(item);
		if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
			return "gather logs " + n;
		}
		OreKind ore = OreKind.byDrop(item);
		if (ore != null) {
			return "mine " + ore.word + " " + n;
		}
		if (Animals.RAW_MEAT.contains(item)) {
			String animal = item == net.minecraft.item.Items.BEEF ? "cow" : item == net.minecraft.item.Items.PORKCHOP ? "pig"
				: item == net.minecraft.item.Items.MUTTON ? "sheep" : item == net.minecraft.item.Items.CHICKEN ? "chicken" : "meat";
			return "hunt " + animal + " " + n;
		}
		if (stack.isIn(net.minecraft.registry.tag.ItemTags.WOOL)) {
			return "hunt sheep " + n; // each sheep drops a block of wool
		}
		if (item == net.minecraft.item.Items.LEATHER) {
			return "hunt cow " + Math.max(1, n); // 0-2 each
		}
		if (item == net.minecraft.item.Items.FEATHER) {
			return "hunt chicken " + Math.max(1, n);
		}
		if (Smelting.smeltable(c.server, item)) {
			return "smelt " + ItemNames.id(item) + " " + n;
		}
		if (CraftPlanner.craftable(c.server, item)) {
			return "craft " + ItemNames.id(item) + " " + n;
		}
		return null;
	}

	/**
	 * "withdraw <key> <n>" if the base chests have some of it and we're close enough to fetch it,
	 * else null. Takes what's there (up to n); the caller sources any remainder the usual way.
	 */
	@org.jetbrains.annotations.Nullable
	public static String withdrawSpec(Companion c, String key, java.util.function.Predicate<ItemStack> which, int n) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		if (!canFetch(c)) {
			return null;
		}
		int have = colony.count(which);
		return have > 0 ? "withdraw " + key + " " + Math.min(n, have) : null;
	}

	/**
	 * Withdraw jobs for base-chest gear that beats what we have (see {@link com.botcraft.knowledge.Gear#fromStock}).
	 * Empty unless we live at the base (our home is there) and it's close enough to fetch from:
	 * the chests' gear is for the colony, not for any companion passing by.
	 */
	public static java.util.List<String> gearFromStock(Companion c, @Nullable java.util.Set<String> kinds, boolean armour) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		Memory.Place home = c.memory().place("home");
		if (!canFetch(c) || home == null || colony.world() == null || !com.botcraft.util.Experiments.on("gearFromStock")
			|| !colony.near(colony.world(), home.pos(), com.botcraft.colony.Colony.BASE_RADIUS)
			|| !home.dim.equals(colony.world().getRegistryKey().getValue().toString())) {
			return java.util.List.of();
		}
		java.util.List<String> out = new java.util.ArrayList<>();
		com.botcraft.knowledge.Gear.fromStock(c.player(), colony.stock(), kinds, armour)
			.forEach((it, n) -> out.add("withdraw " + ItemNames.id(it) + " " + n));
		return out;
	}

	/** The base chests are worth going to: there's a base, it's close enough, and we can get there. */
	public static boolean canFetch(Companion c) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		return colony != null && colony.hasBase() && c.player() != null && c.now() >= c.baseUnreachableUntil
			&& colony.near(c.player().getServerWorld(), c.player().getBlockPos(), com.botcraft.colony.Colony.FETCH_RANGE);
	}

	/** The spec without markers: a leading "?" (optional job), trailing "~N" (deferrals) and "!" (tools checked). */
	static String base(String spec) {
		String s = spec.trim();
		if (s.startsWith("?")) {
			s = s.substring(1);
		}
		return s.replaceAll("(\\s+(~\\d+|!))+$", "");
	}

	/** Optional jobs (tool prep): failing one doesn't sink the plan. */
	public static boolean optional(String spec) {
		return spec != null && spec.trim().startsWith("?");
	}

	public static int attempt(String spec) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("~(\\d+)(\\s+!)?$").matcher(spec.trim());
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	static String withAttempt(String spec, int attempt) {
		return (optional(spec) ? "?" : "") + base(spec) + " ~" + attempt;
	}

	private static int num(String[] w, int i, int def) {
		if (w.length > i && w[i].matches("\\d+")) {
			return Math.max(1, Integer.parseInt(w[i]));
		}
		return def;
	}

	private static String label(String what) {
		if (what.equals("all")) {
			return "your stuff";
		}
		OreKind k = OreKind.byWord(what);
		if (k != null) {
			return k.noun();
		}
		return what.replace('_', ' ');
	}

	/** Which stacks count for a deliver job. "all" = materials, not our tools/armour/food. */
	public static Predicate<ItemStack> deliverable(String what) {
		if (what.equals("food")) {
			return Inventories::isGoodFood;
		}
		if (what.equals("pickaxe")) {
			return s -> s.getItem() instanceof net.minecraft.item.PickaxeItem;
		}
		if (what.equals("all")) {
			return s -> !s.isDamageable() && s.getItem().getFoodComponent() == null;
		}
		if (what.matches("logs?|wood|trees?")) {
			return Inventories::isLog;
		}
		OreKind k = OreKind.byWord(what);
		if (k != null) {
			return k::isDrop;
		}
		Item it = ItemNames.resolve(what.replace('_', ' '));
		return it == null ? s -> false : s -> s.isOf(it);
	}
}

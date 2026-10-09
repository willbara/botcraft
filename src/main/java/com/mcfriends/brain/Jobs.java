package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.task.CraftTask;
import com.mcfriends.brain.task.DeliverTask;
import com.mcfriends.brain.task.GatherLogsTask;
import com.mcfriends.brain.task.GoToTask;
import com.mcfriends.brain.task.HuntTask;
import com.mcfriends.brain.task.GatherSeedsTask;
import com.mcfriends.brain.task.HarvestTask;
import com.mcfriends.brain.task.MakeFarmTask;
import com.mcfriends.brain.task.SmeltTask;
import com.mcfriends.knowledge.Animals;
import com.mcfriends.knowledge.CraftPlanner;
import com.mcfriends.knowledge.Smelting;
import com.mcfriends.brain.task.MineTask;
import com.mcfriends.brain.task.SleepTask;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.knowledge.OreKind;
import com.mcfriends.memory.Memory;
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
 *   home                 sleep
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
					return new DeliverTask(c, deliverable(what), label(what), num(w, 2, 64));
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
				case "shelter" -> {
					return new com.mcfriends.brain.task.ShelterTask(c);
				}
				case "smelt" -> {
					String what = w.length > 1 ? w[1] : "food";
					Item it = what.equals("food") ? null : ItemNames.resolve(what.replace('_', ' '));
					if (it == null && !what.equals("food")) {
						return null;
					}
					return new SmeltTask(c, it, num(w, 2, 1));
				}
				case "harvest" -> {
					BlockPos at = new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
					return new HarvestTask(c, at, num(w, 4, 8));
				}
				case "farm" -> {
					BlockPos at = new BlockPos(Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
					Item seedItem = w.length > 5 ? ItemNames.resolve(w[5].replace('_', ' ')) : net.minecraft.item.Items.WHEAT_SEEDS;
					return new MakeFarmTask(c, at, num(w, 4, 4), seedItem == null ? net.minecraft.item.Items.WHEAT_SEEDS : seedItem);
				}
				case "place" -> {
					Item it = ItemNames.resolve(w[1].replace('_', ' '));
					BlockPos at = new BlockPos(Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]));
					return it == null ? null : new com.mcfriends.brain.task.PlaceTask(c, it, at);
				}
				case "seeds" -> {
					return new GatherSeedsTask(c, num(w, 1, 8));
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
			case "deliver" -> "bring you " + label(w.length > 1 ? w[1] : "logs");
			case "goto" -> "go " + (w.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(w, 4, w.length)) : "there");
			case "home" -> "go home";
			case "sleep" -> "go to bed";
			case "shelter" -> "dig in for the night";
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
			case "harvest" -> "harvest and replant the farm";
			case "farm" -> {
				int n = num(w, 4, 4);
				String crop = w.length > 5 ? com.mcfriends.knowledge.Crops.cropName(ItemNames.resolve(w[5].replace('_', ' '))) : "wheat";
				yield "make a " + n + "x" + n + " " + (crop == null ? "" : crop + " ") + "farm";
			}
			case "seeds" -> "find some seeds";
			case "place" -> "set up a " + (w.length > 1 ? w[1].replace('_', ' ') : "block");
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
		if (item == net.minecraft.item.Items.WHEAT_SEEDS) {
			return "seeds " + n;
		}
		if (item == net.minecraft.item.Items.WHEAT || item == net.minecraft.item.Items.CARROT || item == net.minecraft.item.Items.POTATO
			|| item == net.minecraft.item.Items.BEETROOT) {
			Memory.Place farm = c.memory().place("farm");
			return farm == null ? null : "harvest " + farm.x + " " + farm.y + " " + farm.z + " 8";
		}
		if (Smelting.smeltable(c.server, item)) {
			return "smelt " + ItemNames.id(item) + " " + n;
		}
		if (CraftPlanner.craftable(c.server, item)) {
			return "craft " + ItemNames.id(item) + " " + n;
		}
		return null;
	}

	static String base(String spec) {
		return spec.trim().replaceAll("\\s+~\\d+$", "");
	}

	static int attempt(String spec) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("~(\\d+)$").matcher(spec.trim());
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	static String withAttempt(String spec, int attempt) {
		return base(spec) + " ~" + attempt;
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

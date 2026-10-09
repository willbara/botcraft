package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.task.CraftTask;
import com.mcfriends.brain.task.DeliverTask;
import com.mcfriends.brain.task.GatherLogsTask;
import com.mcfriends.brain.task.GoToTask;
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
				yield "craft " + (n == 1 ? "a " + item : n + " " + item);
			}
			case "deliver" -> "bring you " + label(w.length > 1 ? w[1] : "logs");
			case "goto" -> "go " + (w.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(w, 4, w.length)) : "there");
			case "home" -> "go home";
			case "sleep" -> "go to bed";
			default -> spec;
		};
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

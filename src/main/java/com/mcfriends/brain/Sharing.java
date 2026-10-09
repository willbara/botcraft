package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.action.Inventories;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Companions helping each other out: before going off to chop, mine or hunt for something,
 * a companion asks nearby friends whether they can spare it. A friend with enough to spare
 * pauses what they're doing, walks over and hands it across (their own job resumes after).
 *
 * <p>Only spares are given: a reserve of the item is kept, and tools only when they have more
 * than one. Friends in a fight, fleeing or asleep aren't asked.
 */
public final class Sharing {
	private static final double RANGE = 48;

	private Sharing() {
	}

	/** How many matching items a companion can give away. */
	public static int spare(Companion giver, Predicate<ItemStack> what, boolean tool) {
		CompanionPlayer p = giver.player();
		int have = Inventories.count(p.getInventory(), what);
		if (tool) {
			return Math.max(0, have - 1);
		}
		return Math.max(0, have - 2);
	}

	/**
	 * Ask the nearest friend who can spare {@code count} of something.
	 *
	 * @param key   a delivery key understood by Jobs.deliverable (logs, coal, food, an item id...)
	 * @return the friend who'll bring it, or null if nobody can
	 */
	@Nullable
	public static Companion ask(Companion asker, String key, String label, int count, boolean tool) {
		CompanionManager m = CompanionManager.get();
		CompanionPlayer ap = asker.player();
		if (m == null || ap == null) {
			return null;
		}
		Predicate<ItemStack> what = Jobs.deliverable(key);
		Companion best = null;
		double bestD = Double.MAX_VALUE;
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (o == asker || op == null || !o.isOnline() || op.isDead() || op.isSleeping() || op.getWorld() != ap.getWorld()) {
				continue;
			}
			Objective obj = o.brain().currentObjective();
			if (obj == Objective.FIGHT || obj == Objective.FLEE || obj == Objective.RETREAT) {
				continue;
			}
			double d = op.distanceTo(ap);
			if (d > RANGE || spare(o, what, tool) < Math.min(count, tool ? 1 : count)) {
				continue;
			}
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		if (best == null) {
			return null;
		}
		int n = tool ? 1 : Math.min(count, spare(best, what, false));
		AiLog.log(asker.name(), "asking {} for {} {}", best.name(), n, key);
		best.jobs().interruptWith(List.of("deliver " + key + " " + n + " to " + asker.name()));
		asker.speech().sayIn("share.ask", null, Map.of("friend", best.name(), "item", label, "count", Integer.toString(n)), 0);
		best.speech().sayIn("share.yes", null, Map.of("friend", asker.name(), "item", label), 100);
		return best;
	}
}

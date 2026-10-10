package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.action.Inventories;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
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
	/** One request per companion every 3 minutes; the same thing at most every 10 minutes. */
	private static final int ASK_COOLDOWN = 20 * 60 * 3;
	private static final int SAME_ITEM_COOLDOWN = 20 * 60 * 10;
	/** A helper gets a breather after helping someone. */
	private static final int HELPER_COOLDOWN = 20 * 60 * 2;
	/** Cheap things you'd get yourself faster than someone could bring them. */
	private static final java.util.Set<String> NOT_WORTH_ASKING = java.util.Set.of("stone", "logs", "dirt", "cobblestone",
		"cobbled_deepslate", "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks",
		"mangrove_planks", "cherry_planks", "stick", "crafting_table", "all");
	private static final Map<String, Long> nextAsk = new java.util.HashMap<>();
	private static final Map<String, Long> helperBusyUntil = new java.util.HashMap<>();

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
	 * Ask the friend best placed to spare {@code count} of something: closest, with the most to
	 * spare, not busy with something of their own. The bids are kept on the {@link Board}.
	 *
	 * @param key   a delivery key understood by Jobs.deliverable (logs, coal, food, an item id...)
	 * @return the friend who'll bring it, or null if nobody can
	 */
	@Nullable
	public static Companion ask(Companion asker, String key, String label, int count, boolean tool) {
		CompanionManager m = CompanionManager.get();
		CompanionPlayer ap = asker.player();
		if (m == null || ap == null || NOT_WORTH_ASKING.contains(key) || key.endsWith("_planks")) {
			return null;
		}
		long now = asker.now();
		if (now < nextAsk.getOrDefault(asker.name(), 0L) || now < nextAsk.getOrDefault(asker.name() + "|" + key, 0L)) {
			return null; // asked recently: go get it ourselves
		}
		Predicate<ItemStack> what = Jobs.deliverable(key);
		Companion best = null;
		List<Board.Bid> bids = new java.util.ArrayList<>();
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (o == asker || op == null || !o.isOnline() || op.isDead() || op.isSleeping() || op.getWorld() != ap.getWorld() || Popbob.is(o)) {
				continue;
			}
			Objective obj = o.brain().currentObjective();
			if (obj == Objective.FIGHT || obj == Objective.FLEE || obj == Objective.RETREAT) {
				continue;
			}
			// Don't pull someone off an order they were given, or someone already helping / just helped.
			boolean available = o.jobs().isIdle() || "free".equals(o.data.mode);
			if (!available || o.brain().job() instanceof com.botcraft.brain.task.DeliverTask
				|| now < helperBusyUntil.getOrDefault(o.name(), 0L)) {
				continue;
			}
			double d = op.distanceTo(ap);
			int spare = spare(o, what, tool);
			if (d > RANGE || spare < Math.min(count, tool ? 1 : count)) {
				continue;
			}
			Map<String, Double> terms = new java.util.LinkedHashMap<>();
			terms.put("distance", -d / 24.0);
			terms.put("spare", 0.5 * Math.min(1.0, spare / (double) Math.max(1, count * 2)));
			if (!o.jobs().isIdle()) {
				terms.put("busy", -0.5);
			}
			terms.put("helpful", 0.2 * o.data.helpfulness);
			double score = terms.values().stream().mapToDouble(Double::doubleValue).sum();
			bids.add(new Board.Bid(o.name(), score, terms, "give", d / 4.0, List.of()));
		}
		nextAsk.put(asker.name(), now + ASK_COOLDOWN);
		nextAsk.put(asker.name() + "|" + key, now + SAME_ITEM_COOLDOWN);
		bids.sort(java.util.Comparator.comparingDouble((Board.Bid b) -> -b.score()));
		if (!bids.isEmpty()) {
			best = m.byName(bids.get(0).who());
		}
		Board.delivery(asker.name(), key, label, count, bids, best == null ? null : best.name(), now);
		if (best == null) {
			return null;
		}
		helperBusyUntil.put(best.name(), now + HELPER_COOLDOWN);
		int n = tool ? 1 : Math.min(count, spare(best, what, false));
		AiLog.log(asker.name(), "asking {} for {} {}", best.name(), n, key);
		best.jobs().interruptWith(List.of("deliver " + key + " " + n + " to " + asker.name()));
		asker.speech().sayIn("share.ask", null, Map.of("friend", best.name(), "item", label, "count", Integer.toString(n)), 0);
		best.speech().sayIn("share.yes", null, Map.of("friend", asker.name(), "item", label), 100);
		return best;
	}
}

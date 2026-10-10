package com.mcfriends.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counters for a soak run (see {@link Soak}): deaths and their causes, job results and failure
 * reasons, stalls, interrupts, and how long the AI took each tick. Does nothing unless a soak run
 * is active, so normal play pays nothing for it.
 */
public final class SoakStats {
	static boolean active;

	static final Map<String, Integer> deaths = new TreeMap<>();
	static final Map<String, Integer> deathCauses = new TreeMap<>();
	static final Map<String, Integer> jobsDone = new TreeMap<>();
	static final Map<String, Integer> jobsFailed = new TreeMap<>();
	static final Map<String, Integer> failReasons = new TreeMap<>();
	static final Map<String, Integer> stalls = new TreeMap<>();
	static final Map<String, Integer> interrupts = new TreeMap<>();
	static final Map<String, Double> distance = new LinkedHashMap<>();

	static long stored;
	static long ticks;
	static long tickNanos;
	static long maxTickNanos;
	static long ticksOverBudget;

	private SoakStats() {
	}

	public static boolean active() {
		return active;
	}

	public static void death(String who, String cause) {
		if (active) {
			deaths.merge(who, 1, Integer::sum);
			deathCauses.merge(cause.replace(who, "<companion>"), 1, Integer::sum);
		}
	}

	public static void job(String who, String task, boolean ok, String reason) {
		if (!active) {
			return;
		}
		if (ok) {
			jobsDone.merge(task, 1, Integer::sum);
		} else {
			jobsFailed.merge(task, 1, Integer::sum);
			failReasons.merge(task + ": " + reason, 1, Integer::sum);
		}
	}

	public static void stall(String who, String job) {
		if (active) {
			stalls.merge(who + " (" + job + ")", 1, Integer::sum);
		}
	}

	public static void interrupt(String objective) {
		if (active) {
			interrupts.merge(objective, 1, Integer::sum);
		}
	}

	public static void stored(int items) {
		if (active) {
			stored += items;
		}
	}

	public static void moved(String who, double blocks) {
		if (active) {
			distance.merge(who, blocks, Double::sum);
		}
	}

	/** Time spent in the companion tick (all companions together) this server tick. */
	public static void tickTime(long nanos, double budgetMs) {
		if (!active) {
			return;
		}
		ticks++;
		tickNanos += nanos;
		maxTickNanos = Math.max(maxTickNanos, nanos);
		if (nanos > budgetMs * 1_000_000 * 1.5) {
			ticksOverBudget++;
		}
	}
}

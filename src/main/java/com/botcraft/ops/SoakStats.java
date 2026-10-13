package com.botcraft.ops;

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
	static final Map<String, Integer> rescues = new TreeMap<>();
	static final Map<String, Integer> interrupts = new TreeMap<>();
	/** Request board events: "posted coal", "done coal by Soak2", "reopened coal: why"... */
	static final Map<String, Integer> board = new TreeMap<>();
	static final Map<String, Double> distance = new LinkedHashMap<>();

	static long stored;
	static long ticks;
	static long tickNanos;
	static long maxTickNanos;
	static long ticksOverBudget;
	/** What the companions took on the most recent tick (nanoseconds), for per-trip maxima. */
	static long lastTickNanos;
	static double budgetSum;
	static double serverMsSum;
	static long serverSlowTicks;

	// Navigation: path searches, how long they took to finish (in ticks, so a starved shared
	// budget shows up), how many nodes they cost, and what went wrong while following them.
	static final Map<String, Integer> searches = new TreeMap<>();
	static final Map<String, Integer> stuck = new TreeMap<>();
	static final Map<String, Integer> navFails = new TreeMap<>();
	/** Other navigation events: paths planned ahead and taken up, replans because the world changed. */
	static final Map<String, Integer> navEvents = new TreeMap<>();
	static final java.util.List<Integer> searchTicks = new java.util.ArrayList<>();
	static long searchNodes;
	static long searchNanos;
	/** Ticks a companion had a search waiting but got no node expansions (budget used up by others). */
	static long starvedTicks;
	static long searchingTicks;

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

	public static void rescue(String who, String why) {
		if (active) {
			rescues.merge(who + " (" + why + ")", 1, Integer::sum);
		}
	}

	public static void board(String event) {
		if (active) {
			board.merge(event, 1, Integer::sum);
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

	public static void search(String outcome, int nodes, long ticks, boolean escape) {
		if (active) {
			searches.merge(outcome + (escape ? " (escape)" : ""), 1, Integer::sum);
			searchNodes += nodes;
			searchTicks.add((int) ticks);
		}
	}

	/** One tick of a waiting search: how many nodes it got and how long that took. */
	public static void searchTick(int expandedThisTick, long nanos) {
		if (active) {
			searchingTicks++;
			searchNanos += nanos;
			if (expandedThisTick == 0) {
				starvedTicks++;
			}
		}
	}

	/** Search work done while walking (planning ahead): CPU only, nobody is waiting on it. */
	public static void searchCpu(long nanos) {
		if (active) {
			searchNanos += nanos;
		}
	}

	/** The server's own (smoothed) tick time, sampled each tick. */
	public static void serverTick(float ms) {
		if (active) {
			serverMsSum += ms;
			if (ms > 50) {
				serverSlowTicks++;
			}
		}
	}

	public static void stuck(String why) {
		if (active) {
			stuck.merge(why.replaceAll("-?\\d+, -?\\d+, -?\\d+", "<pos>"), 1, Integer::sum);
		}
	}

	public static void navEvent(String what) {
		if (active) {
			navEvents.merge(what, 1, Integer::sum);
		}
	}

	public static void navFailed(String why) {
		if (active) {
			navFails.merge(why.replaceAll("-?\\d+, -?\\d+, -?\\d+", "<pos>"), 1, Integer::sum);
		}
	}

	/** Time spent in the companion tick (all companions together) this server tick. */
	public static void tickTime(long nanos, double budgetMs) {
		if (!active) {
			return;
		}
		budgetSum += budgetMs;
		ticks++;
		tickNanos += nanos;
		lastTickNanos = nanos;
		maxTickNanos = Math.max(maxTickNanos, nanos);
		if (nanos > budgetMs * 1_000_000 * 1.5) {
			ticksOverBudget++;
		}
	}
}

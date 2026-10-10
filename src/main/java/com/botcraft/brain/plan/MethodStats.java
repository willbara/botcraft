package com.botcraft.brain.plan;

import com.botcraft.Companion;

/**
 * What a companion has learned about how well each way of getting things works: attempts,
 * successes, items gained, time spent and deaths, per method ("mine iron", "hunt meat",
 * "gather logs", "craft", "smelt", "withdraw"). Kept as counters in the companion's memory, so it
 * survives restarts. The planner turns these into costs: a method that keeps failing or killing
 * us here starts to look expensive, and something else wins.
 */
public final class MethodStats {
	private static final String PREFIX = "method.";

	private MethodStats() {
	}

	/** The method a job spec stands for ("mine iron 3" -> "mine iron", "craft x 1" -> "craft"). */
	public static String key(String spec) {
		String[] w = spec.trim().replaceFirst("^\\?", "").replaceAll("(\\s+(~\\d+|!))+$", "").split(" ");
		return switch (w[0]) {
			case "mine", "hunt" -> w.length > 1 ? w[0] + " " + w[1] : w[0];
			case "gather" -> "gather logs";
			default -> w[0];
		};
	}

	public static void record(Companion c, String method, boolean ok, int units, int ticks) {
		var m = c.memory();
		m.count(PREFIX + method + ".attempts", 1);
		if (ok) {
			m.count(PREFIX + method + ".successes", 1);
		}
		if (units > 0) {
			m.count(PREFIX + method + ".units", units);
		}
		m.count(PREFIX + method + ".seconds", Math.max(1, ticks / 20));
	}

	public static void death(Companion c, String method) {
		c.memory().count(PREFIX + method + ".deaths", 1);
	}

	private static int get(Companion c, String method, String field) {
		return c.memory().stat(PREFIX + method + "." + field);
	}

	/** Seconds per item, from experience; {@code prior} when we have no experience yet. */
	public static double secondsPerUnit(Companion c, String method, double prior) {
		int units = get(c, method, "units");
		int seconds = get(c, method, "seconds");
		if (units < 3) {
			// Little data: lean on the prior, nudged by what we've seen.
			return units == 0 ? prior : (prior * 3 + seconds / (double) units) / 4;
		}
		return seconds / (double) units;
	}

	/** How often it works (optimistic with little data). */
	public static double successRate(Companion c, String method) {
		int attempts = get(c, method, "attempts");
		int ok = get(c, method, "successes");
		return (ok + 2.0) / (attempts + 2.0);
	}

	public static int deaths(Companion c, String method) {
		return get(c, method, "deaths");
	}

	public static int attempts(Companion c, String method) {
		return get(c, method, "attempts");
	}

	/** One line per method we have experience with (for /companion why). */
	public static java.util.List<String> summary(Companion c) {
		java.util.Set<String> methods = new java.util.TreeSet<>();
		for (String k : c.memory().stats.keySet()) {
			if (k.startsWith(PREFIX) && k.endsWith(".attempts")) {
				methods.add(k.substring(PREFIX.length(), k.length() - ".attempts".length()));
			}
		}
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String m : methods) {
			int units = get(c, m, "units");
			out.add(String.format("%s: %d tries, %.0f%% ok, %d items, %s, %d deaths", m, attempts(c, m), successRate(c, m) * 100, units,
				units > 0 ? String.format("%.0fs/item", get(c, m, "seconds") / (double) units) : "-", deaths(c, m)));
		}
		return out;
	}
}

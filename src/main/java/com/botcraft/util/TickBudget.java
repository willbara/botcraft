package com.botcraft.util;

/**
 * Shared per-server-tick time budget for AI work. Heavy work (path search, block
 * scans) checks {@link #hasTime()} and yields to the next tick when exhausted.
 */
public final class TickBudget {
	private static long deadline;
	/**
	 * Bench runs: never out of time, so how much gets searched each tick depends only on node
	 * counts, not on how busy the machine is (runs then go the same way on any machine).
	 */
	public static boolean unlimited;

	private TickBudget() {
	}

	public static void begin(double budgetMs) {
		deadline = System.nanoTime() + (long) (budgetMs * 1_000_000L);
	}

	public static boolean hasTime() {
		return unlimited || System.nanoTime() < deadline;
	}
}

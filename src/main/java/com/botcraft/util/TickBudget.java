package com.botcraft.util;

/**
 * Shared per-server-tick time budget for AI work. Heavy work (path search, block
 * scans) checks {@link #hasTime()} and yields to the next tick when exhausted.
 */
public final class TickBudget {
	private static long deadline;

	private TickBudget() {
	}

	public static void begin(double budgetMs) {
		deadline = System.nanoTime() + (long) (budgetMs * 1_000_000L);
	}

	public static boolean hasTime() {
		return System.nanoTime() < deadline;
	}
}

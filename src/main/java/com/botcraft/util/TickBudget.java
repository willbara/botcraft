package com.botcraft.util;

/**
 * Time for heavy AI work (path search, block scans) each server tick. Heavy work checks
 * {@link #hasTime()} and yields to the next tick when it's out.
 *
 * <p>Each companion has its own allowance (time it saved up on quiet ticks, up to a burst), so
 * one companion's long search can't starve the rest; past that it may borrow from a pool shared
 * by everyone this tick. The manager sizes both from how busy the server is.
 */
public final class TickBudget {
	/** Shared time left this tick (nanoseconds). */
	private static long pool;
	/** However much companions have saved up, AI work stops for the tick here (the server comes first). */
	private static long ceiling = Long.MAX_VALUE;
	/** The companion being ticked: when its own allowance runs out. */
	private static long own = Long.MAX_VALUE;
	private static long ownStart;
	private static boolean inCompanion;
	/**
	 * Bench runs: never out of time, so how much gets searched each tick depends only on node
	 * counts, not on how busy the machine is (runs then go the same way on any machine).
	 */
	public static boolean unlimited;

	private TickBudget() {
	}

	/** A new tick: everything shares {@code poolMs} (used directly by work outside any companion). */
	public static void begin(double poolMs) {
		begin(poolMs, Double.MAX_VALUE / 2);
	}

	/** A new tick, with no AI work at all past {@code ceilingMs} from now. */
	public static void begin(double poolMs, double ceilingMs) {
		pool = (long) (poolMs * 1_000_000L);
		ownStart = System.nanoTime();
		own = ownStart;
		ceiling = ceilingMs >= Long.MAX_VALUE / 2_000_000.0 ? Long.MAX_VALUE : ownStart + (long) (ceilingMs * 1_000_000L);
		inCompanion = false;
	}

	/** A companion's turn, with {@code allowanceMs} of its own before it borrows from the pool. */
	public static void beginCompanion(double allowanceMs) {
		ownStart = System.nanoTime();
		own = ownStart + (long) (allowanceMs * 1_000_000L);
		inCompanion = true;
	}

	/** The companion's turn is over: time it took beyond its allowance comes out of the pool. Returns ms taken. */
	public static double endCompanion() {
		long now = System.nanoTime();
		if (now > own) {
			pool = Math.max(0, pool - (now - own));
		}
		inCompanion = false;
		return (now - ownStart) / 1e6;
	}

	public static boolean hasTime() {
		if (unlimited) {
			return true;
		}
		long now = System.nanoTime();
		if (now >= ceiling) {
			return false;
		}
		if (!inCompanion) {
			return now < ownStart + pool;
		}
		return now < own || now < own + pool;
	}
}

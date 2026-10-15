package com.botcraft.faction;

/**
 * Which faction the code running right now works for. The colony, its base map, upkeep and the
 * request board are per faction; code that asks for "the colony" ({@code Colony.get()}) gets the
 * one of the faction in scope. The manager enters a companion's faction around everything that
 * companion does, and each faction's in turn for the board and base upkeep. Outside of that it's
 * the default colony, which is everything there is with one faction (as before factions).
 *
 * <p>Server thread only, like the rest of the AI.
 */
public final class Scope {
	private static String current = Factions.DEFAULT;

	private Scope() {
	}

	public static String current() {
		return current;
	}

	/** Switch to {@code id}; returns the faction to go back to with {@link #exit}. */
	public static String enter(String id) {
		String prev = current;
		current = id == null ? Factions.DEFAULT : id;
		return prev;
	}

	public static void exit(String prev) {
		current = prev == null ? Factions.DEFAULT : prev;
	}

	/** Run something for one faction. */
	public static void run(String id, Runnable r) {
		String prev = enter(id);
		try {
			r.run();
		} finally {
			exit(prev);
		}
	}

	/** For tests and server stop. */
	public static void reset() {
		current = Factions.DEFAULT;
	}
}

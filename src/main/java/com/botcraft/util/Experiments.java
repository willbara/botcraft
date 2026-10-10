package com.botcraft.util;

import java.util.Set;

/**
 * Switches for comparing behaviour in soak runs: {@code -Dbotcraft.off=name,name} turns the
 * named features off. Everything is on by default; normal play never sets the property.
 */
public final class Experiments {
	private static final Set<String> OFF = Set.of(System.getProperty("botcraft.off", "").split(","));

	private Experiments() {
	}

	public static boolean on(String name) {
		return !OFF.contains(name);
	}

	/** A tuning value for soak comparisons: {@code -Dbotcraft.<name>=<value>}, else the default. */
	public static double num(String name, double fallback) {
		String v = System.getProperty("botcraft." + name);
		try {
			return v == null ? fallback : Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}

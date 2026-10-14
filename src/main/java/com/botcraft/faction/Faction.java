package com.botcraft.faction;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A group of companions (and optionally players) with its own base, request board and opinions:
 * what it thinks of each player ({@link Standing}) and of the other factions (-100..100). Saved in
 * {@code <world>/botcraft/factions.json}; which faction a companion is in is saved with the
 * companion. Plain data; {@link Factions} does the work.
 */
public final class Faction {
	public enum Rank {
		MEMBER, OFFICER, LEADER;

		public String word() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public String id;
	public String name;
	/** A chat colour name (red, blue, green, gold...), used for the scoreboard team. */
	public String color = "white";
	/** Player UUID -> what this faction thinks of them. */
	public Map<String, Standing> players = new LinkedHashMap<>();
	/** Player UUID -> rank, for players who are members. */
	public Map<String, Rank> members = new LinkedHashMap<>();
	/** Other faction id -> standing with it, -100..100 (missing = neutral, 0). */
	public Map<String, Integer> relations = new LinkedHashMap<>();
	/** Warfare for this faction only (off/defend/pvp/raid), or null for the server's setting. */
	public String warfare;
	/** World time it was founded. */
	public long founded;

	public Faction() {
	}

	public Faction(String id, String name, String color, long now) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.founded = now;
	}

	/** Standing with another faction (0 if never met). */
	public int relation(String other) {
		if (relations == null) {
			relations = new LinkedHashMap<>();
		}
		return relations.getOrDefault(other, 0);
	}

	public void setRelation(String other, int value) {
		if (relations == null) {
			relations = new LinkedHashMap<>();
		}
		relations.put(other, Math.max(-100, Math.min(100, value)));
	}

	/** The rank of a member player, or null if they aren't one. */
	public Rank rank(String uuid) {
		return members == null ? null : members.get(uuid);
	}

	/** Fill in collections Gson left null (older or hand-edited files). */
	void repair() {
		if (players == null) {
			players = new LinkedHashMap<>();
		}
		if (members == null) {
			members = new LinkedHashMap<>();
		}
		if (relations == null) {
			relations = new LinkedHashMap<>();
		}
		if (name == null) {
			name = id;
		}
		if (color == null) {
			color = "white";
		}
	}
}

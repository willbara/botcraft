package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.brain.Mood;
import com.botcraft.brain.Personality;
import com.botcraft.config.CompanionConfig;
import com.botcraft.util.AiLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Factions splitting up (dynamic mode only). Every few minutes each member's unhappiness is
 * worked out from what it is and what has happened to it:
 * <ul>
 *   <li>disliking the others (grudges, fights, no friends among them);</li>
 *   <li>ambition without loyalty (wanting to lead rather than follow);</li>
 *   <li>frustration (things keep going wrong);</li>
 *   <li>crowding (big factions).</li>
 * </ul>
 * Members unhappy twice in a row grumble; when enough of them are (at least three, and a quarter
 * of the faction) the most ambitious one leaves with them and whoever likes it more than the
 * rest, and founds a new faction, at odds with the old one. They found a base of their own, well
 * away (see Life's founding goal), and when their land meets the old faction's, it's war or a
 * truce ({@link Territory}). Empty factions are disbanded.
 */
public final class Fracture {
	private static final long CHECK_TICKS = 20 * 60 * 4;
	/** One split per faction per in-game day at most. */
	private static final long SPLIT_COOLDOWN = 24000;
	static final double UNHAPPY = 0.35;
	private static final int MIN_SIDE = 3;
	private static final Map<String, Integer> STREAK = new HashMap<>();
	private static final Map<String, Long> LAST_SPLIT = new HashMap<>();

	private Fracture() {
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 113 || !Factions.dynamic()) {
			return;
		}
		disband();
		for (Faction f : new ArrayList<>(Factions.all())) {
			try {
				check(f, now);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error checking faction {}", f.id, e);
			}
		}
	}

	/** How unhappy a companion is with its faction (0 = content, ~1 = ready to leave). */
	public static double unhappiness(Companion c, List<Companion> mates) {
		Personality p = c.brain().personality();
		double dislike = 0;
		double like = 0;
		int n = 0;
		for (Companion o : mates) {
			if (o == c) {
				continue;
			}
			float a = c.relations().affinity(o.name());
			dislike += Math.max(0, -a);
			like += Math.max(0, a);
			n++;
		}
		dislike = n == 0 ? 0 : dislike / n;
		boolean friendless = n > 0 && like / n < 0.02;
		double crowd = Math.min(1.0, mates.size() / 10.0);
		return 0.9 * dislike + (friendless ? 0.1 : 0.0) + 0.3 * p.ambition() * (1.0 - p.loyalty()) + 0.2 * c.mood().get(Mood.Kind.FRUSTRATION)
			+ 0.15 * crowd - 0.25 * p.loyalty() + 0.1;
	}

	private static void check(Faction f, long now) {
		List<Companion> members = Factions.members(f.id);
		members.removeIf(c -> !c.isOnline());
		if (members.size() < MIN_SIDE * 2) {
			return;
		}
		List<Companion> unhappy = new ArrayList<>();
		for (Companion c : members) {
			double u = unhappiness(c, members);
			int streak = u > UNHAPPY ? STREAK.getOrDefault(c.name(), 0) + 1 : 0;
			STREAK.put(c.name(), streak);
			if (streak >= 1 && u > UNHAPPY) {
				c.speech().event("faction.grumble", Map.of("faction", f.name), 20 * 60 * 5, 0.5F);
			}
			if (streak >= 2) {
				unhappy.add(c);
			}
		}
		if (unhappy.size() < MIN_SIDE || unhappy.size() * 4 < members.size() || now - LAST_SPLIT.getOrDefault(f.id, -SPLIT_COOLDOWN) < SPLIT_COOLDOWN
			|| Factions.all().size() >= CompanionConfig.get().maxFactions) {
			return;
		}
		split(f, members, unhappy, now);
	}

	/** The unhappy leave, led by the most ambitious of them, with whoever likes the leader better than the rest. */
	static void split(Faction from, List<Companion> members, List<Companion> unhappy, long now) {
		Companion leader = unhappy.get(0);
		for (Companion c : unhappy) {
			if (c.brain().personality().ambition() > leader.brain().personality().ambition()) {
				leader = c;
			}
		}
		List<Companion> leaving = new ArrayList<>(unhappy);
		for (Companion c : members) {
			if (!leaving.contains(c) && c.relations().affinity(leader.name()) > 0.25F
				&& c.relations().affinity(leader.name()) > averageAffinity(c, members, leaving)) {
				leaving.add(c);
			}
		}
		if (members.size() - leaving.size() < MIN_SIDE) {
			return; // nobody would be left: not a split, a move
		}
		String base = leader.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		base = base.substring(0, Math.min(10, base.length()));
		String id = base;
		for (int i = 2; Factions.get(id) != null; i++) {
			id = base + i;
		}
		Faction nf = Factions.create(id, leader.name() + "'s Band", "", now);
		if (nf == null) {
			return;
		}
		for (Companion c : leaving) {
			from.members.remove(c.data.uuid.toString());
			c.data.faction = nf.id;
			c.data.mode = "free";
			c.jobs().clear();
			c.brain().reconsider();
			STREAK.remove(c.name());
		}
		nf.members.put(leader.data.uuid.toString(), Faction.Rank.LEADER);
		// Splits are bitter: with land to fight over they start cool and settle far apart (the fight
		// starts when their borders meet); without, they start out as enemies.
		Factions.setRelation(from.id, nf.id, Territory.on() ? -15 : -45);
		LAST_SPLIT.put(from.id, now);
		LAST_SPLIT.put(nf.id, now);
		Factions.markDirty();
		Factions.syncTeams();
		leader.speech().event("faction.split", Map.of("faction", nf.name, "old", from.name), 0, 1.0F);
		AiLog.log("faction", "{} split from {}: {}", nf.id, from.id, leaving.stream().map(Companion::name).toList());
	}

	private static double averageAffinity(Companion c, List<Companion> members, List<Companion> exclude) {
		double sum = 0;
		int n = 0;
		for (Companion o : members) {
			if (o != c && !exclude.contains(o)) {
				sum += c.relations().affinity(o.name());
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** Factions with nobody left in them go (the default colony stays). */
	private static void disband() {
		for (String id : Factions.ids()) {
			Faction f = Factions.get(id);
			if (f != null && !id.equals(Factions.DEFAULT) && Factions.members(id).isEmpty() && f.members.isEmpty()) {
				Factions.remove(id);
				com.botcraft.colony.Colony.drop(id);
				AiLog.log("faction", "{} disbanded (nobody left)", id);
			}
		}
	}

	public static void clear() {
		STREAK.clear();
		LAST_SPLIT.clear();
	}
}

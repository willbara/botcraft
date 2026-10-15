package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.colony.Colony;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Wars that end, and start again (dynamic mode). Every two minutes:
 * <ul>
 *   <li><b>War weariness:</b> a faction at war that has lost {@link #WEARY_DEATHS} or more members
 *       to the other side in the last {@link #WINDOW} ticks, at least twice what it took from them,
 *       sues for peace: the two become neutral, and neither may declare war on the other for a
 *       while ({@link #TRUCE}).</li>
 *   <li><b>Declaring war:</b> with no truce in the way, a faction whose most aggressive member is
 *       spoiling for a fight, and that outnumbers a neutral neighbour with a base, may declare war on
 *       it. Aggressive factions do it more.</li>
 * </ul>
 * Without this, two hostile factions in dynamic mode fight forever (neutral factions don't fight
 * at all except in self-defence).
 */
public final class Diplomacy {
	private static final long CHECK_TICKS = 20 * 60 * 2;
	static final long WINDOW = 20 * 60 * 20;
	static final int WEARY_DEATHS = 6;
	static final long TRUCE = 20 * 60 * 20;
	/** "loser>winner" -> when each death happened. */
	private static final Map<String, List<Long>> DEATHS = new HashMap<>();
	/** "a|b" (sorted) -> no war between them before this tick. */
	private static final Map<String, Long> TRUCE_UNTIL = new HashMap<>();
	private static final Random RANDOM = new Random();

	private Diplomacy() {
	}

	/** A member of {@code victim} was killed by a member of {@code killer}. */
	public static void death(String victim, String killer, long now) {
		if (victim.equals(killer)) {
			return;
		}
		DEATHS.computeIfAbsent(victim + ">" + killer, k -> new ArrayList<>()).add(now);
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 71 || !Factions.dynamic()) {
			return;
		}
		List<String> ids = Factions.ids();
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				String a = ids.get(i);
				String b = ids.get(j);
				if (Factions.hostile(a, b)) {
					weariness(a, b, now);
				} else if (!Factions.friendly(a, b) && now >= TRUCE_UNTIL.getOrDefault(pair(a, b), 0L)) {
					maybeWar(a, b, now);
				}
			}
		}
	}

	private static void weariness(String a, String b, long now) {
		int lostA = recent(a + ">" + b, now);
		int lostB = recent(b + ">" + a, now);
		String weary = null;
		String other = null;
		if (lostA >= WEARY_DEATHS && lostA >= 2 * lostB) {
			weary = a;
			other = b;
		} else if (lostB >= WEARY_DEATHS && lostB >= 2 * lostA) {
			weary = b;
			other = a;
		}
		if (weary == null) {
			return;
		}
		Factions.setRelation(a, b, -5);
		TRUCE_UNTIL.put(pair(a, b), now + TRUCE);
		DEATHS.remove(a + ">" + b);
		DEATHS.remove(b + ">" + a);
		Faction w = Factions.get(weary);
		Faction o = Factions.get(other);
		announce(weary, "faction.truce", o == null ? other : o.name);
		AiLog.log("faction", "{} sued {} for peace after losing {} to {}", weary, other, Math.max(lostA, lostB), Math.min(lostA, lostB));
		if (w != null && o != null) {
			clearFights(weary, other);
		}
	}

	/** Neutral neighbours: an aggressive, bigger faction may declare war. */
	private static void maybeWar(String a, String b, long now) {
		Colony ca = Colony.of(a);
		Colony cb = Colony.of(b);
		if (ca == null || cb == null || !ca.hasBase() || !cb.hasBase()) {
			return;
		}
		int na = Factions.members(a).size();
		int nb = Factions.members(b).size();
		if (na == 0 || nb == 0) {
			return;
		}
		String attacker = na >= nb ? a : b;
		String target = attacker.equals(a) ? b : a;
		float aggression = mostAggressive(attacker);
		// A bigger, aggressive faction picks fights; evenly matched or peaceful ones rarely do.
		double odds = 0.15 * aggression * Math.min(2.0, Math.max(na, nb) / (double) Math.min(na, nb));
		if (RANDOM.nextDouble() >= odds) {
			return;
		}
		Factions.setRelation(a, b, -50);
		Faction t = Factions.get(target);
		announce(attacker, "faction.war", t == null ? target : t.name);
		AiLog.log("faction", "{} declared war on {}", attacker, target);
	}

	private static float mostAggressive(String faction) {
		float best = 0;
		for (Companion c : Factions.members(faction)) {
			best = Math.max(best, c.brain().personality().aggression());
		}
		return best;
	}

	/** Someone from the faction says it (its leader if it has an online companion leader). */
	private static void announce(String faction, String key, String otherName) {
		Companion speaker = spokesperson(faction);
		if (speaker != null) {
			speaker.speech().event(key, Map.of("faction", otherName), 0, 1.0F);
		}
	}

	@Nullable
	static Companion spokesperson(String faction) {
		Faction f = Factions.get(faction);
		Companion best = null;
		for (Companion c : Factions.members(faction)) {
			if (!c.isOnline()) {
				continue;
			}
			if (f != null && f.rank(c.data.uuid.toString()) == Faction.Rank.LEADER) {
				return c;
			}
			if (best == null || c.brain().personality().ambition() > best.brain().personality().ambition()) {
				best = c;
			}
		}
		return best;
	}

	/** Peace: stop the fights already under way between the two. */
	private static void clearFights(String a, String b) {
		for (Companion c : Factions.members(a)) {
			c.brain().reconsider();
		}
		for (Companion c : Factions.members(b)) {
			c.brain().reconsider();
		}
	}

	private static int recent(String key, long now) {
		List<Long> times = DEATHS.get(key);
		if (times == null) {
			return 0;
		}
		times.removeIf(t -> now - t > WINDOW);
		return times.size();
	}

	private static String pair(String a, String b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	public static void clear() {
		DEATHS.clear();
		TRUCE_UNTIL.clear();
	}
}

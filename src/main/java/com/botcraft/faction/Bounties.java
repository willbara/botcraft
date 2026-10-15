package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.brain.Mood;
import com.botcraft.config.CompanionConfig;
import com.botcraft.util.AiLog;
import com.botcraft.util.Experiments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A price on the heads of those who keep killing a faction's members. Every kill of one of ours
 * adds to the killer's bounty (a leader's death counts double); bounties halve every
 * {@link #HALF_LIFE} ticks without another kill. From {@link #WANTED} on, the killer is wanted:
 * fought on sight wherever they're met (as long as the two sides are enemies), chosen before
 * other enemies, and their faction is the first one raided. A member who kills a wanted target
 * collects: the bounty is cleared, the killer is cheered, and everyone hears about it.
 */
public final class Bounties {
	/** Wanted from this bounty on (three kills, or fewer if they were leaders). */
	public static final double WANTED = 3.0;
	/** Announced again when it climbs past this. */
	static final double MOST_WANTED = 6.0;
	/** A bounty halves this long after the last kill. */
	static final long HALF_LIFE = 20 * 60 * 30;
	/** Bounties this small are forgotten. */
	private static final double FORGET = 0.25;

	private Bounties() {
	}

	public static boolean on() {
		return Factions.enabled() && CompanionConfig.get().bounties && Experiments.on("bounties");
	}

	/** Halve-life decay since it was last brought up to date. */
	static double decayed(double value, long elapsed) {
		return elapsed <= 0 ? value : value * Math.pow(0.5, elapsed / (double) HALF_LIFE);
	}

	/** One of {@code faction}'s members was killed by {@code killer}. */
	public static void killed(Faction faction, String killer, boolean leader, long now) {
		if (!on() || faction == null || killer == null || killer.isEmpty() || Factions.loners(faction.id)) {
			return; // the factionless keep their own scores (grudges), not a price on anyone's head
		}
		Faction.Bounty b = faction.bounties.computeIfAbsent(key(killer), k -> {
			Faction.Bounty nb = new Faction.Bounty();
			nb.name = killer;
			nb.lastDecay = now;
			return nb;
		});
		double before = current(b, now);
		b.kills++;
		b.value = before + (leader ? 2.0 : 1.0);
		b.lastKill = now;
		Factions.markDirty();
		Map<String, String> vars = Map.of("target", b.name, "kills", Integer.toString(b.kills));
		if (before < WANTED && b.value >= WANTED) {
			say(faction.id, "bounty.posted", vars);
			AiLog.log("faction", "{} puts a bounty on {} ({} kills)", faction.id, b.name, b.kills);
		} else if (before < MOST_WANTED && b.value >= MOST_WANTED) {
			say(faction.id, "bounty.raised", vars);
			AiLog.log("faction", "{} raises the bounty on {} ({} kills)", faction.id, b.name, b.kills);
		}
	}

	/** What {@code faction} would give for {@code name}'s death now (0 if nothing). */
	public static double value(Faction faction, String name, long now) {
		if (!on() || faction == null) {
			return 0;
		}
		Faction.Bounty b = faction.bounties.get(key(name));
		return b == null ? 0 : current(b, now);
	}

	/** {@code name} is wanted by {@code faction}. */
	public static boolean wanted(Faction faction, String name, long now) {
		return value(faction, name, now) >= WANTED;
	}

	/** The sum of the bounties {@code faction} has on members of {@code other}. */
	public static double against(Faction faction, String other, long now) {
		if (!on() || faction == null || faction.bounties.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (Companion c : Factions.members(other)) {
			sum += value(faction, c.name(), now);
		}
		return sum;
	}

	/**
	 * {@code killer} (a companion) killed {@code victim}: if its faction had a price on the victim's
	 * head, it's collected.
	 */
	public static void collect(Companion killer, String victim, long now) {
		Faction f = Factions.of(killer);
		if (!on() || f == null) {
			return;
		}
		Faction.Bounty b = f.bounties.get(key(victim));
		if (b == null) {
			return;
		}
		double v = current(b, now);
		f.bounties.remove(key(victim));
		Factions.markDirty();
		if (v < WANTED) {
			return;
		}
		killer.mood().bump(Mood.Kind.JOY, 0.4F, now);
		killer.memory().count("bounties_collected", 1);
		killer.speech().event("bounty.collected", Map.of("target", b.name), 0, 1.0F);
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion o : Factions.members(f.id)) {
				if (o != killer && o.isOnline()) {
					o.relations().note(killer.name(), com.botcraft.brain.Relationship.Event.HELPED);
				}
			}
		}
		AiLog.log("faction", "{} collected {}'s bounty on {} ({})", killer.name(), f.id, b.name, String.format(Locale.ROOT, "%.1f", v));
	}

	/** The faction's bounties, highest first (for /botcraft faction info). */
	public static List<Faction.Bounty> list(Faction f, long now) {
		List<Faction.Bounty> out = new ArrayList<>();
		for (var it = f.bounties.values().iterator(); it.hasNext(); ) {
			Faction.Bounty b = it.next();
			if (current(b, now) < FORGET) {
				it.remove();
				Factions.markDirty();
			} else {
				out.add(b);
			}
		}
		out.sort(Comparator.comparingDouble((Faction.Bounty b) -> b.value).reversed());
		return out;
	}

	/** Bring a bounty's value up to date. */
	private static double current(Faction.Bounty b, long now) {
		if (now > b.lastDecay) {
			b.value = decayed(b.value, now - b.lastDecay);
			b.lastDecay = now;
		}
		return b.value;
	}

	private static void say(String faction, String key, Map<String, String> vars) {
		Companion speaker = Diplomacy.spokesperson(faction);
		if (speaker != null) {
			speaker.speech().event(key, vars, 0, 1.0F);
		}
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}

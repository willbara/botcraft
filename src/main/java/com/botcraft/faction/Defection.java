package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.brain.Personality;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Members leaving for another faction (dynamic mode), the one-at-a-time side of {@link Fracture}:
 * a member that's stayed unhappy with its faction may go over to one that suits it better,
 * whichever faction that is for its character:
 * <ul>
 *   <li><b>friends:</b> it likes the people there;</li>
 *   <li><b>prosperity:</b> their base is better stocked than ours;</li>
 *   <li><b>peace:</b> cautious ones, with their faction at war, go where there's no war;</li>
 *   <li><b>might:</b> aggressive, brave ones go where the best fighters are;</li>
 *   <li><b>room to rise:</b> ambitious ones go to a smaller faction;</li>
 *   <li>and not too far, nor to anyone at war with us (unless it's the disloyal sort), nor to
 *       a faction whose people can't stand it.</li>
 * </ul>
 * Leaders don't leave (they'd split instead), officers rarely, the loyal hardly ever; a faction
 * down to {@link #KEEP} members keeps them; one member leaves a faction per {@link #COOLDOWN} at
 * most. If the new faction's leader is close by, it's a recruitment ("You're wasted there"); else
 * the member just goes. Either way it moves to its new base, and its old leader isn't pleased.
 */
public final class Defection {
	/** A member leaves a faction this often at most. */
	static final long COOLDOWN = 20 * 60 * 6;
	/** A companion stays at least this long with a faction it's joined before leaving it again. */
	static final long SETTLE = 24000;
	/** Factions this small keep who they have. */
	static final int KEEP = 3;
	/** How much better the other faction has to look. */
	static final double PULL = 0.2;
	/** A leader within this distance of the member recruits it in person. */
	private static final double RECRUIT_RANGE = 64;
	private static final Map<String, Long> LAST_FROM = new HashMap<>();
	private static final Map<String, Long> JOINED = new HashMap<>();
	private static final Random RANDOM = new Random();

	private Defection() {
	}

	public static boolean on() {
		return Factions.dynamic() && CompanionConfig.get().defection;
	}

	/**
	 * From Fracture's check: {@code unhappy} (unhappy twice in a row, most unhappy first) may
	 * leave {@code from}. At most one goes: the one that did, or null.
	 */
	@Nullable
	static Companion consider(Faction from, List<Companion> members, List<Companion> unhappy, Map<Companion, Double> howUnhappy, long now) {
		if (!on() || members.size() <= KEEP || now - LAST_FROM.getOrDefault(from.id, -COOLDOWN) < COOLDOWN) {
			return null;
		}
		for (Companion c : unhappy) {
			Faction.Rank rank = from.rank(c.data.uuid.toString());
			if (rank == Faction.Rank.LEADER || now - JOINED.getOrDefault(c.name(), -SETTLE) < SETTLE) {
				continue;
			}
			Personality p = c.brain().personality();
			Faction to = bestElsewhere(c, from, members, now);
			if (to == null) {
				continue;
			}
			double u = howUnhappy.getOrDefault(c, Fracture.UNHAPPY);
			double odds = Math.min(0.7, 0.2 + 0.6 * (u - Fracture.UNHAPPY) + 0.25 * (1 - p.loyalty())) * (rank == Faction.Rank.OFFICER ? 0.4 : 1.0);
			if (RANDOM.nextDouble() >= odds) {
				AiLog.log("faction", "{} thinks about leaving {} for {} (odds {})", c.name(), from.id, to.id, String.format("%.2f", odds));
				continue;
			}
			leave(c, from, to, now);
			return c;
		}
		return null;
	}

	/** The faction that suits {@code c} better than its own, by enough, or null. */
	@Nullable
	static Faction bestElsewhere(Companion c, Faction from, List<Companion> mates, long now) {
		Personality p = c.brain().personality();
		Colony ours = Colony.of(from.id);
		double ourProsperity = ours == null ? 0 : Leadership.performance(ours);
		double ourMight = might(mates);
		boolean weAtWar = atWar(from.id);
		double ourScore = friends(c, mates);
		Faction best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Faction o : Factions.groups()) {
			if (o == from) {
				continue;
			}
			List<Companion> them = Factions.members(o.id);
			Colony col = Colony.of(o.id);
			if (them.isEmpty() || col == null || !col.hasBase()) {
				continue;
			}
			boolean enemy = Factions.hostile(from.id, o.id);
			if (enemy && p.loyalty() > 0.3F) {
				continue; // only the disloyal go over to the enemy
			}
			double friends = friends(c, them);
			if (friends < -0.2) {
				continue; // they can't stand it (or it them)
			}
			double score = friends;
			score += 0.6 * (Leadership.performance(col) - ourProsperity);
			if (weAtWar && !atWar(o.id)) {
				score += 0.5 * p.caution();
			}
			double theirMight = might(them);
			score += 0.5 * Math.max(p.aggression(), p.bravery()) * Math.max(-1, Math.min(1, (theirMight - ourMight) / Math.max(1, ourMight)));
			if (them.size() < mates.size()) {
				score += 0.2 * p.ambition();
			}
			if (c.player() != null && col.world() == c.player().getWorld()) {
				score -= 0.3 * Math.min(512, Math.sqrt(col.base().getSquaredDistance(c.player().getBlockPos()))) / 512;
			} else {
				score -= 0.3;
			}
			if (enemy) {
				score -= 0.3; // a turncoat's welcome is cold
			}
			if (score > bestScore) {
				bestScore = score;
				best = o;
			}
		}
		return best != null && bestScore - ourScore >= PULL ? best : null;
	}

	/** {@code c} goes over to {@code to}: recruited by its leader if it's about, else it just goes. */
	static void leave(Companion c, Faction from, Faction to, long now) {
		Companion oldBoss = Leadership.commander(from, Factions.members(from.id));
		Companion newBoss = Leadership.commander(to, Factions.members(to.id));
		boolean recruited = newBoss != null && newBoss.player() != null && c.player() != null
			&& newBoss.player().getWorld() == c.player().getWorld() && newBoss.player().distanceTo(c.player()) <= RECRUIT_RANGE;
		Factions.join(c, to.id); // home moves to their base (and a bed at ours is forgotten)
		LAST_FROM.put(from.id, now);
		JOINED.put(c.name(), now);
		c.jobs().clear();
		c.jobs().append(List.of("home"));
		c.brain().reconsider();
		if (recruited) {
			newBoss.speech().event("faction.poach", Map.of("name", c.name(), "faction", from.name), 0, 1.0F);
			c.speech().sayIn("faction.defect", null, Map.of("faction", to.name), 60);
		} else {
			c.speech().event("faction.defect", Map.of("faction", to.name), 0, 1.0F);
			if (newBoss != null) {
				newBoss.speech().sayIn("faction.recruit", null, Map.of("name", c.name(), "faction", to.name), 60);
			}
		}
		if (oldBoss != null && oldBoss != c) {
			oldBoss.speech().sayIn("faction.deserted", null, Map.of("name", c.name(), "faction", to.name), 120);
			oldBoss.relations().note(c.name(), com.botcraft.brain.Relationship.Event.INSULT);
		}
		Factions.clash(from.id, to.id, 2); // they took one of ours
		AiLog.log("faction", "{} left {} for {} ({})", c.name(), from.id, to.id, recruited ? "recruited by " + newBoss.name() : "went on its own");
	}

	/** How much {@code c} likes these people, on average (-1..1). */
	private static double friends(Companion c, List<Companion> people) {
		double sum = 0;
		int n = 0;
		for (Companion o : people) {
			if (o != c) {
				sum += c.relations().affinity(o.name());
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** How well armed these people are, per head (the raids' strength measure). */
	private static double might(List<Companion> people) {
		double sum = 0;
		int n = 0;
		for (Companion o : people) {
			if (o.isOnline()) {
				sum += Raids.strength(o.player());
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	private static boolean atWar(String id) {
		for (Faction o : Factions.groups()) {
			if (!o.id.equals(id) && Factions.hostile(id, o.id)) {
				return true;
			}
		}
		return false;
	}

	public static void clear() {
		LAST_FROM.clear();
		JOINED.clear();
	}
}

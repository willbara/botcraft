package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.colony.Colony;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Factions coming together (dynamic mode), the other half of {@link Fracture}. Every few minutes,
 * for every two factions:
 * <ul>
 *   <li><b>Who could:</b> not enemies, neither just split or merged (a day's cooldown, so a band
 *       doesn't walk straight back), and close: their land touches or their bases are within
 *       {@link #NEAR} blocks (or one has no base yet).</li>
 *   <li><b>Who'd want to:</b> the two think well of each other ({@link #WARM} or more both ways) and
 *       their members get on (they like each other, on average). The smaller side's leader has to
 *       be willing to give up leading: not too ambitious, or fond of the other leader. A faction
 *       down to two members, or a band warming back to the faction it split from, comes round
 *       more easily.</li>
 *   <li><b>Then,</b> now and then (likelier for reunions and the tiny), they merge.</li>
 * </ul>
 * The larger (or, for a reunion, the old faction) takes in the smaller: its members, its land,
 * the players it knew, its bounties. The smaller's leader becomes an officer. The smaller faction
 * is gone; its base's chests stay where they are. Happens by command too:
 * {@code /companion faction merge <into> <from>}.
 */
public final class Merger {
	private static final long CHECK_TICKS = 20 * 60 * 3;
	/** Bases this close count as neighbours even without land touching. */
	static final double NEAR = 200;
	/** Relations both ways this warm or more. */
	static final int WARM = 40;
	/** A faction this small (or smaller) is glad of company. */
	static final int TINY = 2;
	private static final Random RANDOM = new Random();

	private Merger() {
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 131 || !Factions.dynamic()) {
			return;
		}
		List<Faction> groups = Factions.groups();
		for (int i = 0; i < groups.size(); i++) {
			for (int j = i + 1; j < groups.size(); j++) {
				Faction a = groups.get(i);
				Faction b = groups.get(j);
				try {
					double odds = odds(a, b, now);
					if (odds > 0 && RANDOM.nextDouble() < odds) {
						Faction[] order = order(a, b);
						merge(order[0], order[1], now, "warmed to each other");
						return; // one merge a check: the list just changed
					}
				} catch (RuntimeException e) {
					com.botcraft.Botcraft.LOG.error("Error considering a merge of {} and {}", a.id, b.id, e);
				}
			}
		}
	}

	/** The chance the two merge this check (0 if they wouldn't). */
	static double odds(Faction a, Faction b, long now) {
		List<Companion> ma = Factions.members(a.id);
		List<Companion> mb = Factions.members(b.id);
		if (ma.isEmpty() || mb.isEmpty() || Factions.hostile(a.id, b.id) || Factions.inGrace(a.id, b.id)) {
			return 0;
		}
		long cooldown = Fracture.cooldown();
		if (now - Fracture.lastChange(a.id) < cooldown || now - Fracture.lastChange(b.id) < cooldown) {
			return 0;
		}
		if (!close(a, b)) {
			return 0;
		}
		boolean reunion = b.id.equals(a.parent) || a.id.equals(b.parent);
		boolean tiny = ma.size() <= TINY || mb.size() <= TINY;
		int need = reunion || tiny ? WARM / 2 : WARM;
		if (a.relation(b.id) < need || b.relation(a.id) < need) {
			return 0;
		}
		if (getOn(ma, mb) < (tiny ? 0.0 : 0.1)) {
			return 0;
		}
		Faction[] order = order(a, b);
		if (!willing(order[1], order[0]) && !tiny) {
			return 0;
		}
		return 0.2 + (reunion ? 0.2 : 0) + (tiny ? 0.2 : 0);
	}

	/** Land touching, or bases near each other (or one with no base yet to be far from). */
	private static boolean close(Faction a, Faction b) {
		if (Territory.neighbours(a.id, b.id) && Territory.on()) {
			return true;
		}
		Colony ca = Colony.of(a.id);
		Colony cb = Colony.of(b.id);
		if (ca == null || cb == null || !ca.hasBase() || !cb.hasBase()) {
			return true;
		}
		return ca.world() == cb.world() && ca.base().getSquaredDistance(cb.base()) < NEAR * NEAR;
	}

	/** How the two sides' members feel about each other on average (affinity, -1..1). */
	static double getOn(List<Companion> ma, List<Companion> mb) {
		double sum = 0;
		int n = 0;
		for (Companion x : ma) {
			for (Companion y : mb) {
				sum += x.relations().affinity(y.name()) + y.relations().affinity(x.name());
				n += 2;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** The smaller side's leader would give up leading: not too ambitious, or fond of the other leader. */
	private static boolean willing(Faction smaller, Faction larger) {
		Companion l = leader(smaller);
		Companion other = leader(larger);
		if (l == null) {
			return true;
		}
		float ambition = l.brain().personality().ambition();
		return ambition < 0.6F || (other != null && l.relations().affinity(other.name()) > 0.4F);
	}

	/** {survivor, absorbed}: the old faction in a reunion, else the larger (then the older). */
	static Faction[] order(Faction a, Faction b) {
		if (b.id.equals(a.parent)) {
			return new Faction[]{b, a};
		}
		if (a.id.equals(b.parent)) {
			return new Faction[]{a, b};
		}
		int na = Factions.members(a.id).size();
		int nb = Factions.members(b.id).size();
		if (na != nb) {
			return na > nb ? new Faction[]{a, b} : new Faction[]{b, a};
		}
		return a.founded <= b.founded ? new Faction[]{a, b} : new Faction[]{b, a};
	}

	/** {@code from} joins {@code into}: members, land, players known, bounties. {@code from} is gone. */
	public static void merge(Faction into, Faction from, long now, String why) {
		if (into == from || Factions.loners(into.id) || Factions.loners(from.id)) {
			return;
		}
		Companion oldLeader = leader(from);
		Companion newLeader = leader(into);
		Colony home = Colony.of(into.id);
		for (Companion c : Factions.members(from.id)) {
			Factions.join(c, into.id);
			if (home != null && home.hasBase() && home.world() != null) {
				c.memory().setPlace("home", home.base(), home.world().getRegistryKey().getValue().toString(), now);
			}
			c.jobs().clear();
			c.brain().reconsider();
		}
		if (oldLeader != null) {
			into.members.put(oldLeader.data.uuid.toString(), Faction.Rank.OFFICER); // led its own; an officer now
		}
		// Players who were members come along (leaders become officers); what it knew of players, too.
		for (Map.Entry<String, Faction.Rank> e : from.members.entrySet()) {
			if (from.players.containsKey(e.getKey()) && !into.members.containsKey(e.getKey())) {
				into.members.put(e.getKey(), e.getValue() == Faction.Rank.LEADER ? Faction.Rank.OFFICER : e.getValue());
			}
		}
		for (Map.Entry<String, Standing> e : from.players.entrySet()) {
			into.players.putIfAbsent(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, Faction.Bounty> e : from.bounties.entrySet()) {
			Faction.Bounty mine = into.bounties.get(e.getKey());
			if (mine == null) {
				into.bounties.put(e.getKey(), e.getValue());
			} else {
				mine.kills += e.getValue().kills;
				mine.value += e.getValue().value;
			}
		}
		Territory.absorb(from.id, into.id);
		Fracture.changed(into.id, now);
		String fromName = from.name;
		Factions.remove(from.id);
		Colony.drop(from.id);
		Factions.markDirty();
		Factions.syncTeams();
		Leadership.reviewAll();
		if (newLeader != null) {
			newLeader.speech().event("faction.merge", Map.of("faction", fromName), 0, 1.0F);
		}
		if (oldLeader != null) {
			oldLeader.speech().event("faction.merge_join", Map.of("faction", into.name), 0, 1.0F);
		}
		AiLog.log("faction", "{} merged into {} ({})", from.id, into.id, why);
	}

	@Nullable
	private static Companion leader(Faction f) {
		for (Companion c : Factions.members(f.id)) {
			if (f.rank(c.data.uuid.toString()) == Faction.Rank.LEADER) {
				return c;
			}
		}
		return null;
	}
}

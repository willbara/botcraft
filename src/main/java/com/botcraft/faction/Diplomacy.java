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
 *   <li><b>First contact</b> ({@link #contact}, any faction mode): when two factions' land first
 *       touches ({@link Territory}), enemies already at war say so; in dynamic mode neutral ones size
 *       each other up, and it's war or a truce.</li>
 * </ul>
 * With territory on, wars are declared (and fought by raids) only between factions whose borders
 * touch, and the side suing for peace gives up border land.
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
		if (victim.equals(killer) || Factions.loners(victim) || Factions.loners(killer)) {
			return;
		}
		DEATHS.computeIfAbsent(victim + ">" + killer, k -> new ArrayList<>()).add(now);
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 71 || !Factions.enabled()) {
			return;
		}
		mingle(m, now);
		if (!Factions.dynamic()) {
			return;
		}
		List<String> ids = Factions.groups().stream().map(f -> f.id).toList();
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				String a = ids.get(i);
				String b = ids.get(j);
				Ceasefire.check(a, b, now);
				if (Factions.hostile(a, b)) {
					weariness(a, b, now);
				} else if (!Factions.friendly(a, b) && now >= TRUCE_UNTIL.getOrDefault(pair(a, b), 0L) && !Factions.inGrace(a, b)
					&& !Ceasefire.active(a, b) && !Ceasefire.parleying(a, b) && Territory.neighbours(a, b)) {
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
			// An even war: nobody's losing badly enough to sue. The leaders may still agree to stop.
			if (Ceasefire.consider(a, b, lostA, lostB, now)) {
				DEATHS.remove(a + ">" + b);
				DEATHS.remove(b + ">" + a);
			}
			return;
		}
		Factions.setRelation(a, b, -5);
		TRUCE_UNTIL.put(pair(a, b), now + TRUCE);
		DEATHS.remove(a + ">" + b);
		DEATHS.remove(b + ">" + a);
		Faction w = Factions.get(weary);
		Faction o = Factions.get(other);
		announce(weary, "faction.truce", o == null ? other : o.name);
		int ceded = Territory.cede(weary, other, 3);
		AiLog.log("faction", "{} sued {} for peace after losing {} to {} (gave up {} chunk(s))", weary, other, Math.max(lostA, lostB),
			Math.min(lostA, lostB), ceded);
		if (w != null && o != null) {
			clearFights(weary, other);
		}
	}

	/**
	 * May {@code a} declare war on {@code b} now? Not friends, not already at war, no grace or
	 * truce in the way, and (with territory on) their land touches.
	 */
	public static boolean mayDeclare(String a, String b, long now) {
		return Factions.dynamic() && !Factions.hostile(a, b) && !Factions.friendly(a, b) && !Factions.inGrace(a, b)
			&& now >= TRUCE_UNTIL.getOrDefault(pair(a, b), 0L) && !Ceasefire.active(a, b) && !Ceasefire.parleying(a, b) && Territory.neighbours(a, b);
	}

	/** {@code attacker} declares war on {@code target}; its spokesperson says why ({@code key}). */
	public static void declare(String attacker, String target, String key, long now) {
		Factions.setRelation(attacker, target, -50);
		Faction t = Factions.get(target);
		announce(attacker, key, t == null ? target : t.name);
		AiLog.log("faction", "{} declared war on {} ({})", attacker, target, key);
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
		if (Ceasefire.wary(a, b)) {
			odds /= 2; // a ceasefire just ran out: neither's keen to start again
		}
		if (RANDOM.nextDouble() >= odds) {
			return;
		}
		Factions.setRelation(a, b, -50);
		Faction t = Factions.get(target);
		announce(attacker, "faction.war", t == null ? target : t.name);
		AiLog.log("faction", "{} declared war on {}", attacker, target);
	}

	/**
	 * Two factions' land has just touched. Already enemies: the war is on at the border. Already
	 * allies, or in teams mode (where the operator sets who fights whom): nothing changes. Otherwise
	 * (dynamic mode) the two size each other up: aggressive leaders and lopsided numbers make war
	 * likely; sociable ones make a truce (a while of guaranteed peace, and warmer relations).
	 */
	public static void contact(String a, String b, long now) {
		Faction fa = Factions.get(a);
		Faction fb = Factions.get(b);
		if (fa == null || fb == null) {
			return;
		}
		if (Factions.hostile(a, b)) {
			announce(a, "faction.border_war", fb.name);
			announce(b, "faction.border_war", fa.name);
			AiLog.log("faction", "{} and {} meet at the border, already at war", a, b);
			return;
		}
		if (Factions.friendly(a, b) || !Factions.dynamic() || Factions.inGrace(a, b) || Ceasefire.active(a, b)) {
			announce(a, "faction.border_meet", fb.name);
			AiLog.log("faction", "{} and {} meet at the border", a, b);
			return;
		}
		int na = Math.max(1, Factions.members(a).size());
		int nb = Math.max(1, Factions.members(b).size());
		float aggA = mostAggressive(a);
		float aggB = mostAggressive(b);
		String bolder = aggA * na >= aggB * nb ? a : b;
		String other = bolder.equals(a) ? b : a;
		Companion them = spokesperson(other);
		double ratio = Math.min(2.0, Math.max(na, nb) / (double) Math.min(na, nb));
		double war = 0.15 + 0.45 * Math.max(aggA, aggB) * ratio / 2.0 - (them == null ? 0 : 0.2 * them.brain().personality().sociability());
		Faction fo = Factions.get(other);
		Faction fbold = Factions.get(bolder);
		if (RANDOM.nextDouble() < war) {
			Factions.setRelation(a, b, -50);
			announce(bolder, "faction.war", fo == null ? other : fo.name);
			AiLog.log("faction", "{} met {} at the border and declared war (odds {})", bolder, other, String.format("%.2f", war));
		} else {
			Factions.setRelation(a, b, Math.max(fa.relation(b), 15));
			TRUCE_UNTIL.put(pair(a, b), now + TRUCE);
			announce(bolder, "faction.border_truce", fo == null ? other : fo.name);
			announce(other, "faction.border_truce", fbold == null ? bolder : fbold.name);
			AiLog.log("faction", "{} and {} met at the border and agreed a truce (war odds were {})", a, b, String.format("%.2f", war));
		}
	}

	/**
	 * How factions get on when their members meet (any faction mode). Each check, for every two
	 * factions with members within {@link #MEET_RANGE} of each other:
	 * <ul>
	 *   <li><b>A spark:</b> a hothead (aggression {@link #HOTHEAD} or more) may pick a quarrel with
	 *       the other side's member it's near: the two factions think less of each other, and
	 *       enough quarrels add up to enmity (and then fighting). Not in a faction's grace period.</li>
	 *   <li><b>Getting along:</b> otherwise, sociable members who meet without trouble warm the
	 *       factions to each other, slowly, up to alliance.</li>
	 * </ul>
	 */
	static void mingle(CompanionManager m, long now) {
		List<Companion> all = new ArrayList<>();
		for (Companion c : m.all()) {
			if (c.isOnline() && c.player() != null && !c.player().isDead()) {
				all.add(c);
			}
		}
		Map<String, List<Companion[]>> meetings = new HashMap<>();
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				Companion x = all.get(i);
				Companion y = all.get(j);
				String a = Factions.idOf(x);
				String b = Factions.idOf(y);
				if (a.equals(b) || Factions.loners(a) || Factions.loners(b) || x.player().getWorld() != y.player().getWorld()
					|| x.player().squaredDistanceTo(y.player()) > MEET_RANGE * MEET_RANGE) {
					continue;
				}
				meetings.computeIfAbsent(pair(a, b), k -> new ArrayList<>()).add(new Companion[]{x, y});
			}
		}
		for (Map.Entry<String, List<Companion[]>> e : meetings.entrySet()) {
			Companion[] first = e.getValue().get(0);
			String a = Factions.idOf(first[0]);
			String b = Factions.idOf(first[1]);
			if (Factions.hostile(a, b)) {
				continue; // already enemies: the fighting speaks for itself
			}
			Companion[] spark = null;
			float sociable = 0;
			for (Companion[] meet : e.getValue()) {
				for (int k = 0; k < 2 && spark == null; k++) {
					float aggr = meet[k].brain().personality().aggression();
					if (aggr >= HOTHEAD && RANDOM.nextDouble() < 0.06 + 0.3 * (aggr - HOTHEAD) / (1 - HOTHEAD)) {
						spark = k == 0 ? meet : new Companion[]{meet[1], meet[0]};
					}
				}
				sociable += (meet[0].brain().personality().sociability() + meet[1].brain().personality().sociability()) / 2;
			}
			sociable /= e.getValue().size();
			if (spark != null && !Factions.inGrace(a, b)) {
				Companion hothead = spark[0];
				Companion other = spark[1];
				Factions.clash(a, b, SPARK);
				hothead.speech().event("faction.spat", Map.of("name", other.name()), 20 * 60, 1.0F);
				other.relations().note(hothead.name(), com.botcraft.brain.Relationship.Event.INSULT);
				AiLog.log("faction", "{} picked a quarrel with {} ({} and {} now {})", hothead.name(), other.name(), a, b,
					Factions.get(a) == null ? "?" : Factions.get(a).relation(b));
			} else if (spark == null && sociable > 0.4) {
				Faction fa = Factions.get(a);
				if (fa != null && fa.relation(b) < WARM_CAP) {
					Factions.setRelation(a, b, Math.min(WARM_CAP, fa.relation(b) + (sociable > 0.65 ? 2 : 1)));
				}
			}
		}
	}

	/** Members this close count as meeting. */
	private static final double MEET_RANGE = 12;
	/** Aggression from which a member may start a quarrel. */
	static final float HOTHEAD = 0.65F;
	/** A quarrel costs this much standing between the two factions (four or so make enemies). */
	private static final int SPARK = 8;
	/** Getting along stops here (just past alliance). */
	private static final int WARM_CAP = 65;

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
	public static Companion spokesperson(String faction) {
		Faction f = Factions.get(faction);
		Companion best = null;
		Companion officer = null;
		for (Companion c : Factions.members(faction)) {
			if (!c.isOnline()) {
				continue;
			}
			Faction.Rank r = f == null ? null : f.rank(c.data.uuid.toString());
			if (r == Faction.Rank.LEADER) {
				return c;
			}
			if (r == Faction.Rank.OFFICER && officer == null) {
				officer = c;
			}
			if (best == null || c.brain().personality().ambition() > best.brain().personality().ambition()) {
				best = c;
			}
		}
		return officer != null ? officer : best;
	}

	/** Peace: stop the fights already under way between the two. */
	static void clearFights(String a, String b) {
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

	static String pair(String a, String b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	public static void clear() {
		DEATHS.clear();
		TRUCE_UNTIL.clear();
	}
}

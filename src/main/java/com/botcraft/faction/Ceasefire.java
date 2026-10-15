package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.util.AiLog;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Ceasefires (dynamic mode): a deal between two leaders to stop a war that's costing both sides.
 * <p>
 * War weariness ({@link Diplomacy}) ends lopsided wars: the side losing badly sues for peace. An
 * even war has no loser, and went on forever, every death costing iron and diamond gear. So, every
 * Diplomacy check (2 minutes), a war that has run at least {@link #MIN_WAR} with both sides bleeding
 * ({@link #MIN_LOSSES} deaths between them lately, each side at least {@link #MIN_EACH}) may end in a
 * ceasefire: likelier the longer and bloodier it's been, and with calmer, more sociable leaders.
 * <ul>
 *   <li><b>The parley:</b> first the leaders meet: both sides are called home, nobody fights, and
 *       the leaders (with officers) walk out to the ground between their bases, face to face. A
 *       hothead may spoil it and start the war again; the leaders try again a little later, until a
 *       parley holds. The terms, RISK-style: borders frozen, and the side that lost clearly more
 *       cedes border land.</li>
 *   <li><b>The deal:</b> the two stop fighting (neutral, {@link #AFTER_WAR}), raid nobody's base and
 *       declare nothing for {@link #LENGTH}. Kept with the factions, so it survives a restart.</li>
 *   <li><b>Strain:</b> what would have pushed the two back into war while it holds (a member hit or
 *       killed, a raid, a hothead's quarrel) can't, but adds up as strain instead; strain eases off
 *       by {@link #EASE} each check, so only trouble that keeps coming counts.</li>
 *   <li><b>Breaking it:</b> not before it's held {@link #HOLD}. After that, strain past
 *       {@link #BREAK} may make the aggrieved side's leader call it off (an aggressive one sooner):
 *       war again. Warnings first, as strain builds.</li>
 *   <li><b>Running out:</b> after {@link #LENGTH} it ends quietly: the two are neutral, and for
 *       {@link #WARY} after that a new war between them is half as likely.</li>
 * </ul>
 */
public final class Ceasefire {
	/** A war has to have run this long (manager ticks) before the leaders talk. */
	static final long MIN_WAR = 20 * 60 * 8;
	/** Deaths between the two (both ways, in Diplomacy's window) before they've had enough. */
	static final int MIN_LOSSES = 10;
	/** Each side has to have lost at least this many (it hurts both). */
	static final int MIN_EACH = 3;
	/** How long it lasts (world time). */
	public static final long LENGTH = 20 * 60 * 40;
	/** It can't be broken for this long. */
	public static final long HOLD = 20 * 60 * 12;
	/** Strain at which it may be broken. */
	static final double BREAK = 30;
	/** Strain at which the leaders start warning each other. */
	static final double WARN = 15;
	/** Strain eased off each check. */
	static final double EASE = 4;
	/** Relations once it's agreed: no longer enemies, not friends. */
	static final int AFTER_WAR = -5;
	/** After it runs out, new wars between the two are half as likely for this long. */
	static final long WARY = 20 * 60 * 30;
	private static final Random RANDOM = new Random();
	/** "a|b" -> when the two were first seen at war (manager ticks; since the server started). */
	private static final Map<String, Long> WAR_SINCE = new HashMap<>();

	/** One faction's side of a ceasefire with another (both keep a copy). */
	public static final class Terms {
		public long since;
		public long until;
		public double strain;
		/** Warned the other side already (once per build-up). */
		public boolean warned;
		/** Ran out, and that's been said. */
		public boolean ended;
	}

	private Ceasefire() {
	}

	public static boolean on() {
		return Factions.dynamic() && CompanionConfig.get().ceasefires;
	}

	@Nullable
	private static Terms terms(String a, String b) {
		Faction fa = Factions.get(a);
		return fa == null ? null : fa.ceasefires.get(b);
	}

	/** A ceasefire holds between the two now. */
	public static boolean active(String a, String b) {
		Terms t = terms(a, b);
		return t != null && Factions.worldTime() < t.until;
	}

	/** World time a ceasefire between the two runs out (0 if none). */
	public static long until(String a, String b) {
		Terms t = terms(a, b);
		return t == null ? 0 : t.until;
	}

	/** The two had a ceasefire that ended not long ago: wary of starting again. */
	public static boolean wary(String a, String b) {
		Terms t = terms(a, b);
		long now = Factions.worldTime();
		return t != null && now >= t.until && now < t.until + WARY;
	}

	/** Something that would have soured relations by {@code by} during a ceasefire: strain instead. */
	public static void strain(String a, String b, double by) {
		for (String[] side : new String[][]{{a, b}, {b, a}}) {
			Faction f = Factions.get(side[0]);
			Terms t = f == null ? null : f.ceasefires.get(side[1]);
			if (t != null) {
				t.strain += by;
			}
		}
		Factions.markDirty();
	}

	/**
	 * From Diplomacy's check, for two factions at war that weariness didn't settle: perhaps the
	 * leaders agree a ceasefire. {@code lostA}/{@code lostB}: each side's recent deaths to the other.
	 */
	static boolean consider(String a, String b, int lostA, int lostB, long now) {
		long since = WAR_SINCE.computeIfAbsent(Diplomacy.pair(a, b), k -> now);
		if (PARLEYS.containsKey(Diplomacy.pair(a, b))) {
			return false; // they're already trying (between rounds)
		}
		if (!on() || now - since < MIN_WAR || lostA + lostB < MIN_LOSSES || Math.min(lostA, lostB) < MIN_EACH) {
			return false;
		}
		Companion la = Diplomacy.spokesperson(a);
		Companion lb = Diplomacy.spokesperson(b);
		if (la == null || lb == null) {
			return false; // nobody to make the deal
		}
		var pa = la.brain().personality();
		var pb = lb.brain().personality();
		double longer = Math.min(0.3, (now - since - MIN_WAR) / (double) (20 * 60 * 4) * 0.1);
		double bloodier = Math.min(0.3, (lostA + lostB - MIN_LOSSES) * 0.02);
		double temper = 1.3 - (pa.aggression() + pb.aggression()) / 2 + 0.3 * ((pa.sociability() + pb.sociability()) / 2 - 0.5);
		double odds = Math.min(0.85, (0.25 + longer + bloodier) * Math.max(0.2, temper));
		if (RANDOM.nextDouble() >= odds) {
			AiLog.log("faction", "{} and {} keep fighting (ceasefire odds were {})", a, b, String.format("%.2f", odds));
			return false;
		}
		AiLog.log("faction", "{} and {} will parley after {} min of war ({} and {} dead; odds {})", a, b, (now - since) / 1200,
			lostA, lostB, String.format("%.2f", odds));
		parley(a, b, lostA, lostB, now);
		return true;
	}

	// ---- the parley ----------------------------------------------------------------------------------

	/**
	 * Before a ceasefire, the leaders meet. Both call their factions back to base (and nobody
	 * fights the other side: leader's rules), then walk out with up to {@link #ESCORT} officers to
	 * the middle ground between their bases, face to face. They talk; then a hothead (the most
	 * aggressive member of either side) may refuse it and start the fighting again, a small chance
	 * that halves each time. After {@link #REIGNITE} of renewed fighting the leaders try again, and
	 * so on until a parley holds: then it's a ceasefire, on terms (see {@link #terms}).
	 */
	private static final class Parley {
		final String a;
		final String b;
		final int lostA;
		final int lostB;
		BlockPos at;
		Phase phase;
		long phaseAt;
		int round = 1;
		double hot = -1;
		final java.util.Set<String> envoys = new java.util.HashSet<>();

		Parley(String a, String b, int lostA, int lostB) {
			this.a = a;
			this.b = b;
			this.lostA = lostA;
			this.lostB = lostB;
		}
	}

	private enum Phase { GATHER, TALK, FIGHT }

	/** "a|b" -> a parley under way (or between rounds). */
	private static final Map<String, Parley> PARLEYS = new HashMap<>();
	/** Officers each leader brings along. */
	private static final int ESCORT = 2;
	/** How long the leaders have to get to the meeting before they talk anyway (by messenger). */
	private static final long GATHER_TICKS = 20 * 60 * 3;
	/** How long they talk before it's settled (or a hothead spoils it). */
	private static final long TALK_TICKS = 20 * 12;
	/** Fighting after a hothead spoiled it, before the leaders try again. */
	static final long REIGNITE = 20 * 60 * 2;
	/** Leaders this close are face to face. */
	private static final double FACE_TO_FACE = 8;

	/** A parley between the two is on (not between rounds): nobody fights, borders frozen. */
	public static boolean parleying(String a, String b) {
		Parley p = PARLEYS.get(Diplomacy.pair(a, b));
		return p != null && p.phase != Phase.FIGHT;
	}

	/** The faction is in a parley with anyone (its members are called home). */
	public static boolean inParley(String faction) {
		for (Parley p : PARLEYS.values()) {
			if (p.phase != Phase.FIGHT && (p.a.equals(faction) || p.b.equals(faction))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What a member does while its faction parleys (from free mode's choice): an envoy goes to (and
	 * waits at) the meeting; everyone else comes home and stays there. Null when there's no parley.
	 */
	@Nullable
	public static List<String> parleyJobs(Companion c, double fromHome) {
		String id = Factions.idOf(c);
		for (Parley p : PARLEYS.values()) {
			if (p.phase == Phase.FIGHT || !(p.a.equals(id) || p.b.equals(id))) {
				continue;
			}
			var pl = c.player();
			if (p.envoys.contains(c.name()) && pl != null) {
				if (pl.getBlockPos().getSquaredDistance(p.at) <= 16) {
					return List.of(); // there: wait for the other side
				}
				return List.of("goto " + p.at.getX() + " " + p.at.getY() + " " + p.at.getZ() + " to parley with " + nameOf(p.a.equals(id) ? p.b : p.a));
			}
			return fromHome > 16 ? List.of("home") : List.of();
		}
		return null;
	}

	/** Start a parley now (by command): the leaders call everyone home and go to meet. */
	public static void startParley(String a, String b, long now) {
		if (!PARLEYS.containsKey(Diplomacy.pair(a, b))) {
			parley(a, b, 0, 0, now);
		}
	}

	private static void parley(String a, String b, int lostA, int lostB, long now) {
		Colony ca = Colony.of(a);
		Colony cb = Colony.of(b);
		if (ca == null || cb == null || !ca.hasBase() || !cb.hasBase() || ca.world() != cb.world() || ca.world() == null) {
			agree(a, b); // nowhere to meet: it's agreed by messenger
			terms(a, b, lostA, lostB);
			return;
		}
		Parley p = new Parley(a, b, lostA, lostB);
		BlockPos mid = new BlockPos((ca.base().getX() + cb.base().getX()) / 2, 0, (ca.base().getZ() + cb.base().getZ()) / 2);
		var w = ca.world();
		int y = w.isChunkLoaded(mid.getX() >> 4, mid.getZ() >> 4)
			? w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ())
			: (ca.base().getY() + cb.base().getY()) / 2;
		p.at = new BlockPos(mid.getX(), y, mid.getZ());
		PARLEYS.put(Diplomacy.pair(a, b), p);
		begin(p, now);
	}

	/** A round: the call home, and the envoys set off. */
	private static void begin(Parley p, long now) {
		p.phase = Phase.GATHER;
		p.phaseAt = now;
		p.envoys.clear();
		Factions.setRelation(p.a, p.b, AFTER_WAR); // leader's rules: nobody fights while they talk
		for (String[] side : new String[][]{{p.a, p.b}, {p.b, p.a}}) {
			Faction f = Factions.get(side[0]);
			Companion leader = Diplomacy.spokesperson(side[0]);
			if (f == null || leader == null) {
				continue;
			}
			p.envoys.add(leader.name());
			int escort = 0;
			for (Companion c : Factions.members(side[0])) {
				if (escort < ESCORT && c != leader && c.isOnline() && f.rank(c.data.uuid.toString()) == Faction.Rank.OFFICER) {
					p.envoys.add(c.name());
					escort++;
				}
			}
			leader.speech().event(p.round == 1 ? "faction.parley_call" : "faction.parley_again", Map.of("faction", nameOf(side[1])), 0, 1.0F);
		}
		recall(p, now);
		AiLog.log("faction", "{} and {} call their people home and parley at {} (round {}; envoys {})", p.a, p.b, p.at.toShortString(), p.round,
			p.envoys);
	}

	/** Everyone on both sides drops what they're doing and decides again (home, or to the meeting). */
	private static void recall(Parley p, long now) {
		for (String id : new String[]{p.a, p.b}) {
			for (Companion c : Factions.members(id)) {
				if (c.isOnline() && "free".equals(c.data.mode)) {
					c.jobs().clear();
					c.brain().reconsider();
					c.life().hurry(now);
				}
			}
		}
	}

	/** Called every server tick by the manager. */
	public static void tick(com.botcraft.CompanionManager m, long now) {
		if (now % 20 != 7 || PARLEYS.isEmpty()) {
			return;
		}
		for (var it = PARLEYS.values().iterator(); it.hasNext(); ) {
			Parley p = it.next();
			if (Factions.get(p.a) == null || Factions.get(p.b) == null) {
				it.remove();
				continue;
			}
			try {
				if (step(p, now)) {
					it.remove();
				}
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error in the parley between {} and {}", p.a, p.b, e);
				it.remove();
			}
		}
	}

	/** One look at a parley; true when it's over (agreed, or overtaken). */
	private static boolean step(Parley p, long now) {
		Companion la = Diplomacy.spokesperson(p.a);
		Companion lb = Diplomacy.spokesperson(p.b);
		switch (p.phase) {
			case GATHER -> {
				boolean met = la != null && lb != null && la.player() != null && lb.player() != null
					&& la.player().getWorld() == lb.player().getWorld() && la.player().distanceTo(lb.player()) <= FACE_TO_FACE;
				if (met || now - p.phaseAt > GATHER_TICKS) {
					p.phase = Phase.TALK;
					p.phaseAt = now;
					if (la != null && lb != null) {
						la.speech().event("faction.parley_open", Map.of("leader", lb.name(), "faction", nameOf(p.b)), 0, 1.0F);
						lb.speech().sayIn("faction.parley_reply", null, Map.of("leader", la.name(), "faction", nameOf(p.a)), 60);
					}
					AiLog.log("faction", "{} and {} talk ({})", p.a, p.b, met ? "face to face" : "by messenger");
				}
			}
			case TALK -> {
				if (now - p.phaseAt < TALK_TICKS) {
					return false;
				}
				Companion hot = hothead(p);
				if (hot != null) {
					if (p.hot < 0) {
						float aggr = hot.brain().personality().aggression();
						p.hot = 0.12 + 0.25 * (aggr - Diplomacy.HOTHEAD) / (1 - Diplomacy.HOTHEAD);
					}
					if (RANDOM.nextDouble() < p.hot) {
						String side = Factions.idOf(hot);
						hot.speech().event("faction.parley_hothead", Map.of("faction", nameOf(side.equals(p.a) ? p.b : p.a)), 0, 1.0F);
						Factions.setRelation(p.a, p.b, -50);
						WAR_SINCE.put(Diplomacy.pair(p.a, p.b), now);
						AiLog.log("faction", "{} ({}) spoils the parley between {} and {}: the fighting starts again (round {}, odds were {})",
							hot.name(), side, p.a, p.b, p.round, String.format("%.2f", p.hot));
						p.phase = Phase.FIGHT;
						p.phaseAt = now;
						p.round++;
						p.hot /= 2; // each time, the hotheads have less of a following
						sendEnvoysHome(p);
						return false;
					}
				}
				agree(p.a, p.b);
				if (la != null && lb != null) {
					la.speech().event("faction.ceasefire", Map.of("faction", nameOf(p.b), "leader", lb.name()), 0, 1.0F);
					lb.speech().sayIn("faction.ceasefire_reply", null, Map.of("faction", nameOf(p.a), "leader", la.name()), 60);
				}
				terms(p.a, p.b, p.lostA, p.lostB);
				AiLog.log("faction", "{} and {} agreed a ceasefire at the parley (round {})", p.a, p.b, p.round);
				sendEnvoysHome(p);
				recallDone(p, now);
				return true;
			}
			case FIGHT -> {
				if (!Factions.hostile(p.a, p.b) || active(p.a, p.b)) {
					return true; // over another way (one side sued for peace, or an operator stepped in)
				}
				if (now - p.phaseAt >= REIGNITE) {
					begin(p, now);
				}
			}
		}
		return false;
	}

	/** The most aggressive hothead on either side (aggression from Diplomacy's HOTHEAD), or null if neither has one. */
	@Nullable
	private static Companion hothead(Parley p) {
		Companion best = null;
		for (String id : new String[]{p.a, p.b}) {
			for (Companion c : Factions.members(id)) {
				float aggr = c.brain().personality().aggression();
				if (c.isOnline() && aggr >= Diplomacy.HOTHEAD && (best == null || aggr > best.brain().personality().aggression())) {
					best = c;
				}
			}
		}
		return best;
	}

	private static void sendEnvoysHome(Parley p) {
		var m = com.botcraft.CompanionManager.get();
		for (String name : p.envoys) {
			Companion c = m == null ? null : m.byName(name);
			if (c != null && c.isOnline()) {
				c.jobs().clear();
				c.jobs().append(List.of("home"));
			}
		}
	}

	/** The parley's over: members are free to get on with things. */
	private static void recallDone(Parley p, long now) {
		for (String id : new String[]{p.a, p.b}) {
			for (Companion c : Factions.members(id)) {
				c.life().hurry(now);
			}
		}
	}

	/**
	 * The terms, RISK-style: the borders freeze where they are (neither side may take free land
	 * touching the other's while it holds, see Territory), and the side that lost clearly more in
	 * the war pays for it with border land: a chunk for every few deaths more, up to two.
	 */
	private static void terms(String a, String b, int lostA, int lostB) {
		int diff = Math.abs(lostA - lostB);
		String payer = lostA > lostB ? a : b;
		String payee = payer.equals(a) ? b : a;
		int chunks = Math.min(2, diff / TRIBUTE_DEATHS);
		int ceded = chunks > 0 ? Territory.cede(payer, payee, chunks) : 0;
		Companion speaker = Diplomacy.spokesperson(payee);
		if (speaker != null) {
			speaker.speech().sayIn(ceded > 0 ? "faction.ceasefire_tribute" : "faction.ceasefire_terms", null,
				Map.of("faction", nameOf(payer), "count", Integer.toString(ceded)), 140);
		}
		AiLog.log("faction", "ceasefire terms between {} and {}: borders frozen{}", a, b,
			ceded > 0 ? ", " + payer + " gives up " + ceded + " chunk(s) to " + payee : "");
	}

	/** Deaths more than the other side per chunk of tribute. */
	private static final int TRIBUTE_DEATHS = 4;

	/** Make the deal (also by command). */
	public static void agree(String a, String b) {
		long now = Factions.worldTime();
		for (String[] side : new String[][]{{a, b}, {b, a}}) {
			Faction f = Factions.get(side[0]);
			if (f != null) {
				Terms t = new Terms();
				t.since = now;
				t.until = now + LENGTH;
				f.ceasefires.put(side[1], t);
			}
		}
		Factions.setRelation(a, b, AFTER_WAR);
		WAR_SINCE.remove(Diplomacy.pair(a, b));
		Diplomacy.clearFights(a, b);
		Factions.markDirty();
	}

	/** Call off any ceasefire between the two (an operator put them at war). */
	public static void end(String a, String b) {
		Faction fa = Factions.get(a);
		Faction fb = Factions.get(b);
		if (fa != null && fb != null && (fa.ceasefires.remove(b) != null | fb.ceasefires.remove(a) != null)) {
			Factions.markDirty();
		}
	}

	/** From Diplomacy's check: ease the strain, warn, maybe break, or let it run out. */
	static void check(String a, String b, long now) {
		if (!Factions.hostile(a, b)) {
			WAR_SINCE.remove(Diplomacy.pair(a, b));
		}
		Terms ta = terms(a, b);
		Terms tb = terms(b, a);
		if (ta == null || tb == null) {
			return;
		}
		long t = Factions.worldTime();
		if (t >= ta.until) {
			if (!ta.ended) {
				ta.ended = tb.ended = true;
				Companion l = Diplomacy.spokesperson(a);
				if (l != null) {
					l.speech().event("faction.ceasefire_over", Map.of("faction", nameOf(b)), 0, 1.0F);
				}
				AiLog.log("faction", "the ceasefire between {} and {} has run out", a, b);
				Factions.markDirty();
			}
			if (t >= ta.until + WARY) { // wary no longer: forget it
				Factions.get(a).ceasefires.remove(b);
				Factions.get(b).ceasefires.remove(a);
				Factions.markDirty();
			}
			return;
		}
		double strain = Math.max(0, Math.max(ta.strain, tb.strain) - EASE);
		ta.strain = tb.strain = strain;
		if (strain < WARN) {
			ta.warned = tb.warned = false;
			return;
		}
		String angry = mostAggrieved(a, b);
		String other = angry.equals(a) ? b : a;
		Companion leader = Diplomacy.spokesperson(angry);
		if (leader == null) {
			return;
		}
		if (strain >= BREAK && t - ta.since >= HOLD) {
			float aggr = leader.brain().personality().aggression();
			if (RANDOM.nextDouble() < 0.2 + 0.5 * aggr) {
				Factions.get(a).ceasefires.remove(b);
				Factions.get(b).ceasefires.remove(a);
				Factions.setRelation(a, b, -50);
				WAR_SINCE.put(Diplomacy.pair(a, b), now);
				leader.speech().event("faction.ceasefire_broken", Map.of("faction", nameOf(other)), 0, 1.0F);
				AiLog.log("faction", "{} broke the ceasefire with {} (strain {}, held {} min)", angry, other, String.format("%.0f", strain),
					(t - ta.since) / 1200);
				Factions.markDirty();
				return;
			}
		}
		if (!ta.warned) {
			ta.warned = tb.warned = true;
			leader.speech().event("faction.ceasefire_strain", Map.of("faction", nameOf(other)), 0, 1.0F);
			AiLog.log("faction", "{} warns {}: the ceasefire's under strain ({})", angry, other, String.format("%.0f", strain));
		}
	}

	/** The side whose leader is angrier: the one that warns, and may break it. */
	private static String mostAggrieved(String a, String b) {
		Companion la = Diplomacy.spokesperson(a);
		Companion lb = Diplomacy.spokesperson(b);
		float ga = la == null ? 0 : la.brain().personality().aggression();
		float gb = lb == null ? 0 : lb.brain().personality().aggression();
		return ga >= gb ? a : b;
	}

	private static String nameOf(String id) {
		Faction f = Factions.get(id);
		return f == null ? id : f.name;
	}

	public static void clear() {
		WAR_SINCE.clear();
		PARLEYS.clear();
	}
}

package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Personality;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Who leads, worked out by the group itself (any faction mode but single):
 * <ul>
 *   <li><b>A leader</b> when a faction has none (or its leader companion is gone for good): the
 *       member the others would follow. Ambition and nerve count, but so does what the others
 *       think of it ({@link #leaderScore}).</li>
 *   <li><b>Officers,</b> chosen by the leader: one for every four members (three at most, none
 *       under three members), the ones it likes and trusts, who are loyal and capable
 *       ({@link #officerScore}). An officer is only replaced by someone clearly better.</li>
 *   <li><b>Succession:</b> a leader who's gone is followed by its best officer, else by a new pick.</li>
 * </ul>
 * Ranks given by command ({@code /companion faction member}) are the operator's word: never taken
 * away here. A player who leads a faction keeps leading it.
 */
public final class Leadership {
	private static final long CHECK_TICKS = 20 * 60 * 2;
	/** An officer gives way only to someone this much better (no reshuffling every check). */
	static final double MARGIN = 0.15;
	private static final int MAX_OFFICERS = 3;

	private Leadership() {
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % DIRECT_TICKS == 23 && Factions.enabled()) {
			try {
				recruit(m);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error recruiting", e);
			}
			for (Faction f : Factions.groups()) {
				String prev = Scope.enter(f.id);
				try {
					direct(f, now);
					Strategy.plan(f, now);
				} catch (RuntimeException e) {
					com.botcraft.Botcraft.LOG.error("Error directing {}", f.id, e);
				} finally {
					Scope.exit(prev);
				}
			}
		}
		if (now % CHECK_TICKS != 89 || !Factions.enabled()) {
			return;
		}
		for (Faction f : Factions.groups()) {
			try {
				review(f, m);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error reviewing {}'s leadership", f.id, e);
			}
		}
	}

	/** Appoint whoever's missing now (after teams are made, a split, a join). */
	public static void reviewAll() {
		CompanionManager m = CompanionManager.get();
		if (m == null || !Factions.enabled()) {
			return;
		}
		for (Faction f : Factions.groups()) {
			review(f, m);
		}
	}

	static void review(Faction f, CompanionManager m) {
		List<Companion> members = Factions.members(f.id);
		if (members.isEmpty()) {
			return;
		}
		// Ranks held by companions that aren't in the faction any more (left, removed) go.
		f.members.entrySet().removeIf(e -> {
			Companion c = byUuid(m, e.getKey());
			return c != null && !Factions.idOf(c).equals(f.id);
		});
		String leaderId = leaderOf(f);
		Companion leader = leaderId == null ? null : byUuid(m, leaderId);
		// A player who leads keeps leading (players have a standing entry; companions don't).
		boolean playerLeads = leaderId != null && leader == null && f.players.containsKey(leaderId);
		if (leaderId != null && leader == null && !playerLeads) {
			f.members.remove(leaderId); // a companion that no longer exists
			f.manual.remove(leaderId);
			leaderId = null;
		}
		if (leaderId == null) {
			leader = succeed(f, members);
			if (leader == null) {
				return;
			}
			f.members.put(leader.data.uuid.toString(), Faction.Rank.LEADER);
			Factions.markDirty();
			leader.speech().event("faction.leader_chosen", Map.of("faction", f.name), 0, 1.0F);
			AiLog.log("faction", "{}: {} leads now", f.id, leader.name());
		}
		if (leader != null) {
			appointOfficers(f, leader, members);
		}
	}

	/** The best officer if there is one, else the member the others would follow. */
	@Nullable
	private static Companion succeed(Faction f, List<Companion> members) {
		Companion best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Companion c : members) {
			double s = leaderScore(c, members) + (f.rank(c.data.uuid.toString()) == Faction.Rank.OFFICER ? 1.0 : 0.0);
			if (s > bestScore) {
				bestScore = s;
				best = c;
			}
		}
		return best;
	}

	/**
	 * How fit a member is to lead: drive (ambition), nerve (bravery), steadiness (loyalty,
	 * diligence), a way with people (sociability), and above all how the others feel about it.
	 */
	public static double leaderScore(Companion c, List<Companion> members) {
		Personality p = c.brain().personality();
		double opinion = 0;
		int n = 0;
		for (Companion o : members) {
			if (o != c) {
				opinion += o.relations().affinity(c.name()) + 0.5 * o.relations().trust(c.name());
				n++;
			}
		}
		opinion = n == 0 ? 0 : opinion / n;
		return score(p.ambition(), p.bravery(), p.loyalty(), p.diligence(), p.sociability(), opinion);
	}

	static double score(float ambition, float bravery, float loyalty, float diligence, float sociability, double opinion) {
		return 0.35 * ambition + 0.15 * bravery + 0.15 * loyalty + 0.1 * diligence + 0.15 * sociability + 0.6 * opinion;
	}

	/**
	 * How the leader rates a member as an officer: its own feelings for it (liking and trust),
	 * the member's loyalty and diligence, and how well it can fight.
	 */
	public static double officerScore(Companion leader, Companion c) {
		Personality p = c.brain().personality();
		double weapon = c.player() == null ? 0 : Math.min(1.0, Inventories.bestWeaponDamage(c.player().getInventory()) / 8.0);
		return officer(leader.relations().affinity(c.name()), leader.relations().trust(c.name()), p.loyalty(), p.diligence(), p.bravery(), weapon);
	}

	static double officer(float affinity, float trust, float loyalty, float diligence, float bravery, double weapon) {
		return 0.35 * affinity + 0.25 * trust + 0.2 * loyalty + 0.1 * diligence + 0.1 * bravery + 0.15 * weapon;
	}

	/** Officers wanted for a faction this size. */
	static int officersFor(int members) {
		return members < 3 ? 0 : Math.max(1, Math.min(MAX_OFFICERS, members / 4));
	}

	private static void appointOfficers(Faction f, Companion leader, List<Companion> members) {
		int want = officersFor(members.size());
		List<Companion> current = new ArrayList<>();
		List<Companion> candidates = new ArrayList<>();
		for (Companion c : members) {
			if (c == leader) {
				continue;
			}
			if (f.rank(c.data.uuid.toString()) == Faction.Rank.OFFICER) {
				current.add(c);
			} else {
				candidates.add(c);
			}
		}
		candidates.sort(Comparator.comparingDouble((Companion c) -> officerScore(leader, c)).reversed());
		// Too many (the faction shrank): the leader's least favourite non-appointed officers step down.
		current.sort(Comparator.comparingDouble((Companion c) -> officerScore(leader, c)));
		while (current.size() > want) {
			Companion out = current.stream().filter(c -> !f.manual.contains(c.data.uuid.toString())).findFirst().orElse(null);
			if (out == null) {
				break;
			}
			current.remove(out);
			f.members.remove(out.data.uuid.toString());
			Factions.markDirty();
		}
		// Too few: the best candidates. Full: the worst officer gives way to a clearly better one.
		for (Companion c : candidates) {
			if (current.size() < want) {
				name(f, leader, c);
				current.add(c);
				continue;
			}
			Companion worst = current.stream().filter(o -> !f.manual.contains(o.data.uuid.toString()))
				.min(Comparator.comparingDouble(o -> officerScore(leader, o))).orElse(null);
			if (worst == null || officerScore(leader, c) < officerScore(leader, worst) + MARGIN) {
				break;
			}
			f.members.remove(worst.data.uuid.toString());
			current.remove(worst);
			name(f, leader, c);
			current.add(c);
		}
	}

	private static void name(Faction f, Companion leader, Companion c) {
		f.members.put(c.data.uuid.toString(), Faction.Rank.OFFICER);
		Factions.markDirty();
		leader.speech().event("faction.officer_named", Map.of("name", c.name(), "faction", f.name), 0, 1.0F);
		AiLog.log("faction", "{}: {} makes {} an officer", f.id, leader.name(), c.name());
	}

	@Nullable
	private static String leaderOf(Faction f) {
		for (Map.Entry<String, Faction.Rank> e : f.members.entrySet()) {
			if (e.getValue() == Faction.Rank.LEADER) {
				return e.getKey();
			}
		}
		return null;
	}

	// ---- giving orders --------------------------------------------------------------------------------

	/** How often leaders look over their people. */
	private static final long DIRECT_TICKS = 20 * 30;
	/** Chat about how the faction is doing at most this often. */
	private static final long RALLY_GAP = 20 * 60 * 8;
	private static final Map<String, Long> LAST_RALLY = new java.util.HashMap<>();

	/**
	 * The leader (or, while it's away, an officer) looks over the faction: how well stocked the
	 * base is against what it wants ({@link #performance}), and who's standing about. Idle members
	 * get the most important open request they can do (the board's work, given out by the leader
	 * rather than waited for), more of them when the faction is struggling, and everyone else is
	 * told to get on with something. A struggling faction hears a rallying call; a thriving one,
	 * praise.
	 */
	static void direct(Faction f, long now) {
		com.botcraft.colony.Colony colony = com.botcraft.colony.Colony.get();
		if (colony == null || !colony.hasBase()) {
			return; // the founder's busy finding a home
		}
		List<Companion> members = Factions.members(f.id);
		Companion boss = commander(f, members);
		if (boss == null || boss.player() == null || boss.perception().isNight()) {
			return;
		}
		if (Ceasefire.inParley(f.id)) {
			return; // everyone's called home while the leader talks
		}
		checkIn(f, boss, now);
		double perf = performance(colony);
		boolean young = now - f.founded < 20 * 60 * 20;
		int orders = perf < 0.4 || young ? 3 : perf < 0.75 ? 2 : 1;
		int given = 0;
		for (Companion c : members) {
			if (!idle(c)) {
				continue;
			}
			if (given < orders && com.botcraft.brain.Board.openCount() > 0) {
				com.botcraft.brain.Board.Assignment a = com.botcraft.brain.Board.direct(c, now);
				if (a != null && c.life().directed(c, a, now)) {
					given++;
					if (c != boss) {
						order(f, boss, c, a.request().describe(), now);
					}
					AiLog.log("faction", "{}: {} asks {} for {} (faction at {}%)", f.id, boss.name(), c.name(), a.request().describe(),
						Math.round(perf * 100));
					continue;
				}
			}
			c.life().hurry(now); // nothing on the board for them: get on with something of their own
		}
		if (now - LAST_RALLY.getOrDefault(f.id, -RALLY_GAP) >= RALLY_GAP && members.size() > 1) {
			LAST_RALLY.put(f.id, now);
			if (perf < 0.4) {
				boss.speech().event("faction.rally", Map.of("faction", f.name), 0, 1.0F);
			} else if (perf >= 0.85) {
				boss.speech().event("faction.praise", Map.of("faction", f.name), 0, 0.8F);
				for (Companion c : members) {
					if (c != boss) {
						c.mood().bump(com.botcraft.brain.Mood.Kind.JOY, 0.1F, now);
					}
				}
			}
		}
	}

	/** How well stocked the base is: the average share of each stock target it holds (0..1). */
	static double performance(com.botcraft.colony.Colony colony) {
		double sum = 0;
		int n = 0;
		for (var t : com.botcraft.colony.StockTargets.active(colony)) {
			int want = t.want(colony);
			if (want <= 0) {
				continue;
			}
			sum += Math.min(1.0, colony.count(t.counts()) / (double) want);
			n++;
		}
		return n == 0 ? 1.0 : sum / n;
	}

	/** Living on its own with nothing to do right now (not fighting, sleeping or on an errand). */
	static boolean idle(Companion c) {
		var p = c.player();
		var obj = c.brain().currentObjective();
		return c.isOnline() && p != null && !p.isDead() && !p.isSleeping() && "free".equals(c.data.mode) && c.jobs().isIdle()
			&& obj != com.botcraft.brain.Objective.FIGHT && obj != com.botcraft.brain.Objective.FLEE && obj != com.botcraft.brain.Objective.RETREAT;
	}

	/** Who gives the orders: the leader if it's about, else an officer. */
	@Nullable
	static Companion commander(Faction f, List<Companion> members) {
		Companion officer = null;
		for (Companion c : members) {
			if (!c.isOnline() || c.player() == null || c.player().isDead()) {
				continue;
			}
			Faction.Rank r = f.rank(c.data.uuid.toString());
			if (r == Faction.Rank.LEADER) {
				return c;
			}
			if (r == Faction.Rank.OFFICER && officer == null) {
				officer = c;
			}
		}
		return officer;
	}

	// ---- recruiting ------------------------------------------------------------------------------------

	/**
	 * Companions in no faction (outside single mode: just spawned, left over, or never placed) are
	 * taken in. Teams: the smallest team, to keep them even. Dynamic: the faction it likes best,
	 * nearer ones first.
	 */
	static void recruit(CompanionManager m) {
		if (!com.botcraft.config.CompanionConfig.get().recruit) {
			return;
		}
		// The factionless are individuals (see Factions.loners), each free to be taken in.
		List<Companion> loose = Factions.members(Factions.DEFAULT);
		if (loose.isEmpty()) {
			return;
		}
		List<Faction> open = new ArrayList<>();
		for (Faction f : Factions.all()) {
			if (!f.id.equals(Factions.DEFAULT) && !Factions.members(f.id).isEmpty()) {
				open.add(f);
			}
		}
		if (open.isEmpty()) {
			return;
		}
		for (Companion c : loose) {
			Faction best = null;
			double bestScore = Double.NEGATIVE_INFINITY;
			for (Faction f : open) {
				List<Companion> fm = Factions.members(f.id);
				double score;
				if (Factions.dynamic()) {
					double like = 0;
					double nearest = Double.MAX_VALUE;
					for (Companion o : fm) {
						like += c.relations().affinity(o.name());
						if (o.player() != null && c.player() != null && o.player().getWorld() == c.player().getWorld()) {
							nearest = Math.min(nearest, o.player().distanceTo(c.player()));
						}
					}
					score = like / fm.size() - Math.min(nearest, 256) / 128.0 - fm.size() * 0.02;
				} else {
					score = -fm.size(); // teams: keep them even
				}
				if (score > bestScore) {
					bestScore = score;
					best = f;
				}
			}
			if (best == null) {
				continue;
			}
			Factions.join(c, best.id);
			Companion boss = commander(best, Factions.members(best.id));
			if (boss != null && boss != c) {
				boss.speech().event("faction.recruit", Map.of("name", c.name(), "faction", best.name), 0, 1.0F);
			}
			c.speech().event("faction.joined", Map.of("faction", best.name), 0, 0.8F);
			AiLog.log("faction", "{} joins {}", c.name(), best.id);
		}
	}

	public static void clear() {
		LAST_RALLY.clear();
		ORDERS.clear();
	}

	// ---- orders and check-ins ------------------------------------------------------------------------

	/** An order out: who gave it, what for, and when the giver last asked how it's going. */
	private static final class Order {
		final String faction;
		final String boss;
		final String task;
		long lastCheck;

		Order(String faction, String boss, String task, long now) {
			this.faction = faction;
			this.boss = boss;
			this.task = task;
			this.lastCheck = now;
		}
	}

	/** Orders out, by the member carrying them out. */
	private static final Map<String, Order> ORDERS = new java.util.HashMap<>();
	/** How often the one in charge asks how an order's going. */
	static final long CHECKIN_TICKS = 20 * 60 * 2;
	/** A member answers this long after it's spoken to (so the two lines come in order). */
	private static final int ANSWER_DELAY = 50;

	/** The one in charge gives {@code c} an order out loud; {@code c} says it's on it. */
	private static void order(Faction f, Companion boss, Companion c, String task, long now) {
		ORDERS.put(c.name(), new Order(f.id, boss.name(), task, now));
		boss.speech().event("faction.order", Map.of("name", c.name(), "task", task), 0, 1.0F);
		c.speech().sayIn("faction.order_ack", null, Map.of("leader", boss.name(), "task", task), ANSWER_DELAY);
	}

	/** Every so often, the one in charge asks those still on its orders how it's going; they answer. */
	private static void checkIn(Faction f, Companion boss, long now) {
		CompanionManager m = CompanionManager.get();
		for (var it = ORDERS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Order o = e.getValue();
			if (!o.faction.equals(f.id)) {
				continue;
			}
			Companion c = m == null ? null : m.byName(e.getKey());
			if (c == null || !c.isOnline() || !c.life().onOrder() || !f.id.equals(Factions.idOf(c))) {
				it.remove(); // gone, or moved on without a word (it'll say when it finishes)
				continue;
			}
			if (now - o.lastCheck < CHECKIN_TICKS || c == boss) {
				continue;
			}
			o.lastCheck = now;
			boss.speech().event("faction.checkin", Map.of("name", c.name(), "task", o.task), 0, 1.0F);
			c.speech().sayIn("faction.checkin_reply", null, Map.of("leader", boss.name(), "task", o.task, "doing", c.describeActivity()), ANSWER_DELAY);
		}
	}

	/** {@code c} finished (or gave up on) an order: it reports back, and gets a word of thanks. */
	public static void reportBack(Companion c, boolean ok, long now) {
		Order o = ORDERS.remove(c.name());
		if (o == null) {
			return;
		}
		c.speech().event(ok ? "faction.order_done" : "faction.order_failed", Map.of("leader", o.boss, "task", o.task), 0, 1.0F);
		CompanionManager m = CompanionManager.get();
		Companion boss = m == null ? null : m.byName(o.boss);
		if (boss != null && boss != c && boss.isOnline()) {
			boss.speech().sayIn(ok ? "faction.order_thanks" : "faction.order_shrug", null, Map.of("name", c.name(), "task", o.task), ANSWER_DELAY);
		}
	}

	/** Is this companion an officer (or the leader) of its faction? */
	public static boolean ranked(Companion c) {
		Faction.Rank r = Factions.of(c).rank(c.data.uuid.toString());
		return r == Faction.Rank.OFFICER || r == Faction.Rank.LEADER;
	}

	@Nullable
	private static Companion byUuid(CompanionManager m, String uuid) {
		for (Companion c : m.all()) {
			if (c.data.uuid.toString().equals(uuid)) {
				return c;
			}
		}
		return null;
	}
}

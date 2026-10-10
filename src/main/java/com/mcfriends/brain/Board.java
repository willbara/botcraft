package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.plan.Planner;
import com.mcfriends.colony.Colony;
import com.mcfriends.colony.Kit;
import com.mcfriends.colony.StockTargets;
import com.mcfriends.colony.StockTargets.StockTarget;
import com.mcfriends.util.AiLog;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The colony's request board: what needs doing for everyone, and who's doing it.
 *
 * <ul>
 *   <li><b>Stock requests</b> come from the base's {@link StockTargets}: when the chests are short
 *       of something, a request for one batch of it is posted.</li>
 *   <li><b>Delivery requests</b> come from companions asking each other for things
 *       ({@link Sharing}); they're settled on the spot and kept here so they can be looked at.</li>
 * </ul>
 *
 * <p>A request goes to whoever bids best. A bid is a sum of simple terms, all kept so
 * {@code /companion colony} can show why someone won: how long the planner thinks it would take
 * them (with what they've learned), how far they are from the base, whether they're busy with
 * something of their own, how many requests they've done lately (turns), whether they failed at
 * this one recently, and how helpful they are. It's greedy and lazy: a free companion with nothing
 * to do asks the board, and gets the most important open request it's the best bidder for.
 */
public final class Board {
	public enum Kind {
		STOCK, DELIVER
	}

	public enum State {
		OPEN, TAKEN, DONE, FAILED
	}

	/** One companion's bid: the total, what it's made of, and how it would go about it. */
	public record Bid(String who, double score, Map<String, Double> terms, String method, double seconds, List<String> steps) {
		public String explain() {
			StringBuilder sb = new StringBuilder(String.format("%.2f", score));
			List<String> parts = new ArrayList<>();
			terms.forEach((k, v) -> parts.add(k + " " + String.format("%+.2f", v)));
			if (!parts.isEmpty()) {
				sb.append(" (").append(String.join(", ", parts)).append(")");
			}
			return sb.toString();
		}
	}

	public static final class Request {
		public final int id;
		public final Kind kind;
		/** Stock target key, or the delivery key. */
		public final String key;
		public final String label;
		public int count;
		/** Who it's for (deliveries). */
		@Nullable
		public final String forWhom;
		public final long posted;
		public long openedAt;
		public double priority;
		public State state = State.OPEN;
		@Nullable
		public String takenBy;
		public long takenAt;
		public long closedAt;
		long idleSince;
		public List<Bid> bids = List.of();
		/** Why the winner won, or how it ended. */
		public String note = "";

		Request(int id, Kind kind, String key, String label, int count, @Nullable String forWhom, long now, double priority) {
			this.id = id;
			this.kind = kind;
			this.key = key;
			this.label = label;
			this.count = count;
			this.forWhom = forWhom;
			this.posted = now;
			this.openedAt = now;
			this.priority = priority;
		}

		public String describe() {
			return count + " " + label + (kind == Kind.STOCK ? " for the base" : " for " + forWhom);
		}
	}

	/** What a companion gets from the board: the request and the jobs to do it. */
	public record Assignment(Request request, List<String> steps, String reason) {
	}

	/** A busy companion's bid counts this much less (it'd have to finish its own thing first). */
	private static final double BUSY = 0.5;
	/** After this long open, a request goes to whoever's free rather than waiting for someone busy. */
	private static final long BUSY_GRACE = 20 * 90;
	/** A taken request is given back after this long (it got forgotten somehow). */
	private static final long LEASE = 20 * 60 * 20;
	/** ...or when its holder has had nothing to do for this long. */
	private static final long IDLE_RELEASE = 20 * 60;
	private static final long TURN_WINDOW = 20 * 60 * 20;
	private static final long FAIL_WINDOW = 20 * 60 * 30;
	private static final long BID_TTL = 20 * 30;
	/** Finished requests stay on the board (for /companion colony) this long. */
	private static final long KEEP_CLOSED = 20 * 60 * 10;

	private static final List<Request> REQUESTS = new ArrayList<>();
	private static int nextId = 1;
	/** name -> when it finished requests (turns); "name|key" -> when it failed one. */
	private static final Map<String, List<Long>> DONE = new HashMap<>();
	private static final Map<String, List<Long>> FAILED = new HashMap<>();
	/** Planning is the expensive part of a bid: "name|id" -> plan, reused for a while. */
	private static final Map<String, Plan> PLANS = new HashMap<>();

	private record Plan(long at, String method, double seconds, List<String> steps) {
	}

	private Board() {
	}

	// ---- posting and housekeeping ------------------------------------------------------------------

	/** Every few seconds: post requests for what the base is short of, and tidy up. */
	public static void refresh(CompanionManager m, long now) {
		Colony colony = Colony.get();
		boolean base = colony != null && colony.hasBase();
		if (base) {
			for (StockTarget t : StockTargets.active(colony)) {
				int want = t.want(colony);
				int shortBy = want - colony.count(t.counts());
				Request r = live(Kind.STOCK, t.key());
				if (want <= 0 || shortBy <= 0) {
					if (r != null && r.state == State.OPEN) {
						close(r, State.DONE, "the base has enough", now);
					}
					continue;
				}
				double priority = t.weight() * Math.min(1.0, shortBy / (double) want);
				if (r == null) {
					r = new Request(nextId++, Kind.STOCK, t.key(), t.label(), Math.min(t.batch(), shortBy), null, now, priority);
					REQUESTS.add(r);
					AiLog.log("board", "posted #{}: {} (have {}/{})", r.id, r.describe(), want - shortBy, want);
					com.mcfriends.ops.SoakStats.board("posted " + r.key);
				} else {
					r.priority = priority;
					if (r.state == State.OPEN) {
						r.count = Math.min(t.batch(), shortBy);
					}
				}
			}
		}
		for (Request r : new ArrayList<>(REQUESTS)) {
			if (r.kind == Kind.STOCK && r.state == State.OPEN && !base) {
				close(r, State.DONE, "no base", now);
			}
			if (r.state == State.TAKEN) {
				release(m, r, now);
			}
		}
		REQUESTS.removeIf(r -> (r.state == State.DONE || r.state == State.FAILED) && now - r.closedAt > KEEP_CLOSED);
		PLANS.entrySet().removeIf(e -> now - e.getValue().at() > BID_TTL);
	}

	/** Give back a taken request whose holder is gone, ordered elsewhere, or stopped working on it. */
	private static void release(CompanionManager m, Request r, long now) {
		if (r.kind == Kind.DELIVER) {
			if (now - r.takenAt > 20 * 60 * 5) {
				close(r, State.DONE, "delivered (or given up)", now);
			}
			return;
		}
		Companion holder = r.takenBy == null ? null : m.byName(r.takenBy);
		String why = null;
		if (holder == null || !holder.isOnline()) {
			why = "holder left";
		} else if (!"free".equals(holder.data.mode)) {
			why = r.takenBy + " was given orders";
		} else if (now - r.takenAt > LEASE) {
			why = "took too long";
		} else if (holder.jobs().isIdle()) {
			if (r.idleSince == 0) {
				r.idleSince = now;
			} else if (now - r.idleSince > IDLE_RELEASE) {
				why = r.takenBy + " stopped working on it";
			}
		} else {
			r.idleSince = 0;
		}
		if (why != null) {
			reopen(r, now, why);
		}
	}

	// ---- taking requests ---------------------------------------------------------------------------

	/**
	 * A free companion with nothing to do: the most important open request it's the best bidder
	 * for, taken in its name; null if there's none (or someone else is better placed for each).
	 */
	@Nullable
	public static Assignment take(Companion c, long now) {
		Colony colony = Colony.get();
		if (colony == null || !colony.hasBase() || colony.world() != c.player().getServerWorld()
			|| !colony.near(c.player().getServerWorld(), c.player().getBlockPos(), 256) || holding(c.name()) != null) {
			return null;
		}
		List<Request> open = REQUESTS.stream().filter(r -> r.kind == Kind.STOCK && r.state == State.OPEN)
			.sorted(Comparator.comparingDouble((Request r) -> -r.priority)).toList();
		if (open.isEmpty()) {
			return null;
		}
		List<Companion> pool = pool(c, colony);
		for (Request r : open) {
			StockTarget t = target(colony, r.key);
			if (t == null) {
				continue;
			}
			boolean waitedLong = now - r.openedAt > BUSY_GRACE;
			List<Bid> bids = new ArrayList<>();
			for (Companion o : pool) {
				boolean busy = o != c && !o.jobs().isIdle();
				if (busy && waitedLong) {
					continue;
				}
				Bid b = bid(o, r, t, busy, now);
				if (b != null) {
					bids.add(b);
				}
			}
			bids.sort(Comparator.comparingDouble((Bid b) -> -b.score()));
			if (bids.isEmpty() || !bids.get(0).who().equals(c.name())) {
				continue;
			}
			Bid win = bids.get(0);
			String reason = reason(win, bids.size() > 1 ? bids.get(1) : null);
			r.state = State.TAKEN;
			r.takenBy = c.name();
			r.takenAt = now;
			r.idleSince = 0;
			r.bids = bids;
			r.note = reason;
			PLANS.remove(c.name() + "|" + r.id);
			AiLog.log(c.name(), "board: took #{} {} by {} ({}; bid {})", r.id, r.describe(), win.method(), reason, win.explain());
			com.mcfriends.ops.SoakStats.board("taken " + r.key + " by " + win.method());
			return new Assignment(r, win.steps(), reason);
		}
		return null;
	}

	/** Free companions that could take a stock request (and aren't holding one already). */
	private static List<Companion> pool(Companion asker, Colony colony) {
		List<Companion> out = new ArrayList<>();
		out.add(asker);
		CompanionManager m = CompanionManager.get();
		if (m == null) {
			return out;
		}
		for (Companion o : m.all()) {
			if (o == asker || !o.isOnline() || o.player() == null || o.player().isDead() || !"free".equals(o.data.mode) || Popbob.is(o)
				|| o.player().getServerWorld() != colony.world() || holding(o.name()) != null
				|| !colony.near(o.player().getServerWorld(), o.player().getBlockPos(), 256)) {
				continue;
			}
			out.add(o);
		}
		return out;
	}

	/** How well {@code o} would do this request, or null if it can't. */
	@Nullable
	private static Bid bid(Companion o, Request r, StockTarget t, boolean busy, long now) {
		Plan plan = plan(o, r, t, now);
		if (plan == null) {
			return null;
		}
		Map<String, Double> terms = new LinkedHashMap<>();
		// Everything in the same unit: two minutes of work (or walking back) costs one point.
		terms.put("time", -plan.seconds() / 120.0);
		terms.put("to base", -Planner.secondsToBase(o) / 120.0);
		if (busy) {
			terms.put("busy", -BUSY);
		}
		int turns = recent(DONE, o.name(), now, TURN_WINDOW);
		if (turns > 0) {
			terms.put("turns", -0.25 * turns);
		}
		int fails = recent(FAILED, o.name() + "|" + r.key, now, FAIL_WINDOW);
		if (fails > 0) {
			terms.put("failed", -0.75 * fails);
		}
		terms.put("helpful", 0.2 * o.data.helpfulness);
		double score = terms.values().stream().mapToDouble(Double::doubleValue).sum();
		return new Bid(o.name(), score, terms, plan.method(), plan.seconds(), plan.steps());
	}

	/** How {@code o} would get this batch: spare it already carries, else whatever the planner picks. */
	@Nullable
	private static Plan plan(Companion o, Request r, StockTarget t, long now) {
		String cacheKey = o.name() + "|" + r.id;
		Plan cached = PLANS.get(cacheKey);
		if (cached != null && now - cached.at() < BID_TTL) {
			return cached.method().equals("none") ? null : cached;
		}
		String deposit = "deposit " + r.key + " " + r.count;
		// Back to the base first (a walk sized to the distance), then put it away.
		Colony colony = Colony.get();
		BlockPos base = colony == null ? null : colony.base();
		String back = base == null ? null : "goto " + base.getX() + " " + base.getY() + " " + base.getZ() + " to the base";
		int carried = Inventories.count(o.player().getInventory(), t.counts());
		int spare = carried - Kit.keeps(new ItemStack(t.item()));
		Plan plan;
		if (spare >= r.count) {
			plan = new Plan(now, "have", 0, withReturn(List.of(), back, deposit));
		} else if (r.key.equals("food")) {
			var d = Planner.acquireForBase(o, Items.COOKED_BEEF, r.count, t.counts());
			double seconds = d.chosen().possible() ? d.chosen().cost() : 300;
			plan = new Plan(now, "hunt", seconds, withReturn(List.of("hunt meat " + r.count, "smelt food " + r.count), back, deposit));
		} else {
			var d = Planner.acquireForBase(o, t.item(), r.count, t.counts());
			if (!d.chosen().possible()) {
				plan = new Plan(now, "none", Double.POSITIVE_INFINITY, List.of());
			} else {
				plan = new Plan(now, d.chosen().method(), d.chosen().cost(), withReturn(d.chosen().steps(), back, deposit));
			}
		}
		PLANS.put(cacheKey, plan);
		return plan.method().equals("none") ? null : plan;
	}

	private static List<String> withReturn(List<String> steps, @Nullable String back, String deposit) {
		List<String> out = new ArrayList<>(steps);
		if (back != null) {
			out.add(back);
		}
		out.add(deposit);
		return out;
	}

	/** In words: where the winner beat the runner-up most. */
	static String reason(Bid win, @Nullable Bid runnerUp) {
		if (win.method().equals("have")) {
			return "I've got spare already";
		}
		if (runnerUp == null) {
			return "nobody else is free";
		}
		String best = null;
		double gap = 0;
		java.util.Set<String> keys = new java.util.LinkedHashSet<>(win.terms().keySet());
		keys.addAll(runnerUp.terms().keySet());
		for (String k : keys) {
			double g = win.terms().getOrDefault(k, 0.0) - runnerUp.terms().getOrDefault(k, 0.0);
			if (g > gap) {
				gap = g;
				best = k;
			}
		}
		if (best == null) {
			return "I'm as well placed as anyone";
		}
		return switch (best) {
			case "time" -> "it's quickest for me";
			case "to base" -> "I'm closest to the base";
			case "busy" -> runnerUp.who() + " is busy";
			case "turns" -> "it's my turn";
			case "failed" -> runnerUp.who() + " had trouble with it";
			case "helpful" -> "happy to help";
			default -> "I'm best placed";
		};
	}

	// ---- finishing ---------------------------------------------------------------------------------

	/** {@code who} finished (or gave up on) a request it holds. */
	public static void finished(int id, String who, boolean ok, long now) {
		Request r = byId(id);
		if (r == null || r.state != State.TAKEN || !who.equals(r.takenBy)) {
			return; // given back in the meantime (orders, night), maybe taken by someone else
		}
		if (ok) {
			DONE.computeIfAbsent(r.takenBy, k -> new ArrayList<>()).add(now);
			AiLog.log(r.takenBy, "board: finished #{} {}", r.id, r.describe());
			com.mcfriends.ops.SoakStats.board("done " + r.key + " by " + r.takenBy);
			close(r, State.DONE, "done by " + r.takenBy, now);
		} else {
			FAILED.computeIfAbsent(r.takenBy + "|" + r.key, k -> new ArrayList<>()).add(now);
			reopen(r, now, r.takenBy + " couldn't do it");
		}
	}

	private static void reopen(Request r, long now, String why) {
		AiLog.log("board", "#{} {} is open again: {}", r.id, r.describe(), why);
		com.mcfriends.ops.SoakStats.board("reopened " + r.key + ": " + why.replaceAll("^\\S+ (?=(was|stopped|couldn't))", "<holder> "));
		r.state = State.OPEN;
		r.takenBy = null;
		r.openedAt = now;
		r.idleSince = 0;
		r.note = why;
	}

	private static void close(Request r, State state, String why, long now) {
		r.state = state;
		r.closedAt = now;
		r.note = why;
	}

	// ---- deliveries (Sharing) ----------------------------------------------------------------------

	/** Note a friend-to-friend request that was settled on the spot (for /companion colony). */
	static void delivery(String asker, String key, String label, int count, List<Bid> bids, @Nullable String helper, long now) {
		Request r = new Request(nextId++, Kind.DELIVER, key, label, count, asker, now, 0);
		r.bids = bids;
		if (helper == null) {
			close(r, State.FAILED, "nobody could spare it", now);
		} else {
			r.state = State.TAKEN;
			r.takenBy = helper;
			r.takenAt = now;
			r.note = bids.size() > 1 ? reason(bids.get(0), bids.get(1)) : "nobody else could";
		}
		REQUESTS.add(r);
	}

	// ---- looking -----------------------------------------------------------------------------------

	/** Everything on the board, newest first. */
	public static List<Request> requests() {
		List<Request> out = new ArrayList<>(REQUESTS);
		out.sort(Comparator.comparingInt((Request r) -> -r.id));
		return out;
	}

	/** The request a companion is working on, if any. */
	@Nullable
	public static Request holding(String name) {
		for (Request r : REQUESTS) {
			if (r.state == State.TAKEN && r.kind == Kind.STOCK && name.equals(r.takenBy)) {
				return r;
			}
		}
		return null;
	}

	@Nullable
	private static Request byId(int id) {
		for (Request r : REQUESTS) {
			if (r.id == id) {
				return r;
			}
		}
		return null;
	}

	@Nullable
	private static Request live(Kind kind, String key) {
		for (Request r : REQUESTS) {
			if (r.kind == kind && r.key.equals(key) && (r.state == State.OPEN || r.state == State.TAKEN)) {
				return r;
			}
		}
		return null;
	}

	@Nullable
	private static StockTarget target(Colony colony, String key) {
		for (StockTarget t : StockTargets.active(colony)) {
			if (t.key().equals(key)) {
				return t;
			}
		}
		return null;
	}

	private static int recent(Map<String, List<Long>> log, String key, long now, long window) {
		List<Long> times = log.get(key);
		if (times == null) {
			return 0;
		}
		times.removeIf(t -> now - t > window);
		return times.size();
	}

	/** For tests and server stop: an empty board. */
	public static void clear() {
		REQUESTS.clear();
		DONE.clear();
		FAILED.clear();
		PLANS.clear();
	}
}

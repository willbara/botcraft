package com.botcraft.brain.goal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A companion's goals, grouped under roots (survive, progress, the faction, curiosity...), and
 * how it chooses between them. Each decision, free mode offers the goals that make sense right
 * now, each with a base priority (its place in the old fixed order); the tree scores them with
 * the companion's personality and picks the best, giving the goal it's already on a commitment
 * bonus so it doesn't flip-flop. A goal whose jobs fail is put aside for longer each time
 * (10, 20, 40 minutes... up to an hour) and is fine again once it works.
 *
 * <p>The tree keeps what was offered and why, so {@code /companion goals} can show it. Pure
 * Java, so it can be unit tested.
 */
public final class GoalTree {
	/** The top-level things a companion wants. */
	public enum Root {
		/** Orders from the owner or faction leaders (handled by the job queue; shown for completeness). */
		OBEY,
		/** Eating, sleeping, staying safe. */
		SURVIVE,
		/** Better tools and gear, the tech tree. */
		PROGRESS,
		/** What the base needs (the request board, storage, light). */
		FACTION,
		/** Helping friends. */
		SOCIAL,
		/** Exploring. */
		CURIOSITY,
		/** Heading home, idling. */
		REST;

		public String word() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum State {
		/** Offered this time but something else won. */
		WAITING,
		/** Chosen. */
		ACTIVE,
		/** Put aside after failing (until {@link Node#backoffUntil}). */
		BACKOFF,
		/** Not offered this time (nothing to do for it). */
		IDLE
	}

	/** One goal: the jobs that would do it, and its history. */
	public static final class Node {
		public final String key;
		public Root root;
		public String reason = "";
		public List<String> jobs = List.of();
		public float base;
		public float utility;
		public State state = State.IDLE;
		public int attempts;
		public int successes;
		public long backoffUntil;
		public long lastChosen = Long.MIN_VALUE;
		public String lastResult = "";

		Node(String key, Root root) {
			this.key = key;
			this.root = root;
		}
	}

	/** First backoff after a failure; doubles each time it fails again. */
	public static final long BACKOFF = 20 * 60 * 10;
	public static final long MAX_BACKOFF = 20 * 60 * 60;

	private final Map<String, Node> nodes = new LinkedHashMap<>();
	private final List<Node> offered = new ArrayList<>();
	private String active;
	private long decidedAt = Long.MIN_VALUE;

	/** Start a new decision: forget what was offered last time. */
	public void begin(long now) {
		for (Node n : offered) {
			if (n.state != State.BACKOFF) {
				n.state = State.IDLE;
			}
		}
		offered.clear();
		decidedAt = now;
	}

	/** A goal that makes sense right now. {@code base} is its priority before personality (0..1). */
	public Node offer(Root root, String key, String reason, List<String> jobs, float base) {
		Node n = nodes.computeIfAbsent(key, k -> new Node(k, root));
		n.root = root;
		n.reason = reason;
		n.jobs = jobs;
		n.base = base;
		if (!offered.contains(n)) {
			offered.add(n);
		}
		n.state = State.WAITING;
		return n;
	}

	/** May this goal be tried now (not put aside after failing)? */
	public boolean allowed(String key, long now) {
		Node n = nodes.get(key);
		return n == null || now >= n.backoffUntil;
	}

	/**
	 * The best goal on offer: base priority times how much this companion cares about its root,
	 * plus {@code commitment} for the goal it's already on; goals put aside are skipped.
	 * With {@code strict}, the first allowed goal in offer order wins (the old fixed order).
	 */
	public Node pick(long now, Map<Root, Float> weights, float commitment, boolean strict) {
		Node best = null;
		for (Node n : offered) {
			if (now < n.backoffUntil) {
				n.state = State.BACKOFF;
				n.utility = 0;
				continue;
			}
			n.utility = n.base * weights.getOrDefault(n.root, 1.0F) + (n.key.equals(active) ? commitment : 0.0F);
			if (strict) {
				if (best == null) {
					best = n;
				}
			} else if (best == null || n.utility > best.utility) {
				best = n;
			}
		}
		if (best != null) {
			chose(best, now);
		}
		return best;
	}

	/** A goal was decided on outside {@link #pick} (night routines, the board). */
	public Node chose(Root root, String key, String reason, List<String> jobs, long now) {
		Node n = offer(root, key, reason, jobs, 1.0F);
		chose(n, now);
		return n;
	}

	private void chose(Node n, long now) {
		for (Node o : offered) {
			if (o != n && o.state == State.ACTIVE) {
				o.state = State.WAITING;
			}
		}
		n.state = State.ACTIVE;
		n.lastChosen = now;
		active = n.key;
	}

	/** The goal being worked on, if any. */
	public String active() {
		return active;
	}

	/** Its jobs failed: put it aside for a while (longer each time). */
	public void failed(String key, long now, String why) {
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(key, Root.REST);
			nodes.put(key, n);
		}
		n.attempts++;
		long wait = Math.min(MAX_BACKOFF, BACKOFF << Math.min(6, n.attempts - 1));
		n.backoffUntil = now + wait;
		n.state = State.BACKOFF;
		n.lastResult = "failed" + (why == null || why.isEmpty() ? "" : ": " + why);
		if (key.equals(active)) {
			active = null;
		}
	}

	/** Put a goal aside for a set time without counting it as a failure (e.g. exploring, which shouldn't repeat every few seconds). */
	public void rest(String key, long until) {
		Node n = nodes.computeIfAbsent(key, k -> new Node(k, Root.CURIOSITY));
		n.backoffUntil = Math.max(n.backoffUntil, until);
	}

	/** Its jobs worked: it's fine to try again whenever it's needed. */
	public void succeeded(String key) {
		Node n = nodes.get(key);
		if (n == null) {
			return;
		}
		n.attempts = 0;
		n.successes++;
		n.backoffUntil = 0;
		n.lastResult = "done";
		if (key.equals(active)) {
			active = null;
		}
	}

	/** Forget everything (orders changed things completely). */
	public void clear() {
		nodes.clear();
		offered.clear();
		active = null;
	}

	public List<Node> offered() {
		return List.copyOf(offered);
	}

	public Map<String, Node> nodes() {
		return java.util.Collections.unmodifiableMap(nodes);
	}

	/** The tree in words, a line per goal, grouped by root (for /companion goals). */
	public List<String> render(long now) {
		List<String> out = new ArrayList<>();
		Map<Root, List<Node>> byRoot = new EnumMap<>(Root.class);
		for (Node n : nodes.values()) {
			byRoot.computeIfAbsent(n.root, r -> new ArrayList<>()).add(n);
		}
		for (Map.Entry<Root, List<Node>> e : byRoot.entrySet()) {
			out.add(e.getKey().word() + ":");
			List<Node> list = e.getValue();
			list.sort((a, b) -> Float.compare(b.utility, a.utility));
			for (Node n : list) {
				StringBuilder sb = new StringBuilder("  ");
				sb.append(n.state == State.ACTIVE ? "> " : "  ").append(n.key);
				if (n.state == State.WAITING || n.state == State.ACTIVE) {
					sb.append(String.format(Locale.ROOT, " %.2f", n.utility));
				}
				if (now < n.backoffUntil) {
					sb.append(" (put aside ").append((n.backoffUntil - now) / 1200 + 1).append(" min)");
				} else if (n.state == State.IDLE) {
					sb.append(" (not needed now)");
				}
				if (!n.reason.isEmpty() && n.state != State.IDLE) {
					sb.append(": ").append(n.reason);
				}
				if (n.attempts > 0) {
					sb.append(" [failed ").append(n.attempts).append("x]");
				}
				if (n.state == State.ACTIVE && !n.jobs.isEmpty()) {
					sb.append(" -> ").append(String.join(", ", n.jobs));
				}
				out.add(sb.toString());
			}
		}
		if (decidedAt != Long.MIN_VALUE) {
			out.add("(decided " + Math.max(0, (now - decidedAt) / 20) + "s ago)");
		}
		return out;
	}

	/** Which root a free-mode goal belongs to, by its key. */
	public static Root rootOf(String key) {
		if (key.startsWith("target")) {
			return Root.PROGRESS;
		}
		return switch (key) {
			case "sleep", "shelter", "cook", "hunt", "fetch_food", "stock_food", "place_bed", "bed", "night_chores", "farm", "bread", "sword" -> Root.SURVIVE;
			case "store", "light_home", "board", "found_base", "stock_logs", "stock_coal", "stock_iron" -> Root.FACTION;
			case "explore", "fish" -> Root.CURIOSITY;
			case "home" -> Root.REST;
			default -> Root.PROGRESS;
		};
	}
}

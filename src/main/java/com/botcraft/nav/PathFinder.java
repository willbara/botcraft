package com.botcraft.nav;

import com.botcraft.util.TickBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Time-sliced A* over feet positions with a Baritone-style movement set and costs in game
 * ticks. Call {@link #step(int)} once per tick until it stops returning SEARCHING. Never loads
 * chunks (see {@link Terrain}).
 *
 * <p>Movements from each node:
 * <ul>
 *   <li>traverse (4 dirs), optionally mining what's in the way or placing a block to bridge;</li>
 *   <li>diagonal (4 dirs), only through open space;</li>
 *   <li>ascend one block (4 dirs), mining head room / the target if allowed;</li>
 *   <li>descend / fall (4 dirs) up to the safe fall height, deeper only into water;</li>
 *   <li>downward: mine the block underneath and drop into it;</li>
 *   <li>pillar: jump and place a block underneath (needs throwaway blocks);</li>
 *   <li>ladder/vine climbing up and down; swimming;</li>
 *   <li>parkour: sprint-jump a 1-2 block gap on the same level.</li>
 * </ul>
 * Breaking is gated by {@link BreakPolicy} (via Terrain), placing by a budget of blocks we carry.
 */
public class PathFinder {
	public enum Status { SEARCHING, FOUND, PARTIAL, FAILED }

	// Costs in ticks (Baritone ActionCosts).
	public static final double WALK = 4.633;
	public static final double SPRINT = 3.564;
	public static final double WATER_COST = 9.091;
	public static final double JUMP = 6.0;
	public static final double JUMP_PENALTY = 2.0;
	public static final double LADDER_UP = 8.511;
	public static final double LADDER_DOWN = 6.667;
	public static final double PLACE = 20.0;
	public static final double WALK_OFF = 3.706;
	public static final double CENTER = 0.927;
	public static final double DOOR_COST = 8.0;
	/** Heuristic ticks per block: slightly above sprint cost, i.e. a little greedy. */
	private static final double H_PER_BLOCK = com.botcraft.util.Experiments.num("hPerBlock", 4.0);

	private static final double[] FALL_TICKS = new double[64];

	static {
		// Ticks to fall n blocks from rest: vanilla gravity 0.08/tick, drag 0.98.
		double y = 0;
		double v = 0;
		int n = 1;
		for (int tick = 1; n < FALL_TICKS.length && tick < 400; tick++) {
			v = (v - 0.08) * 0.98;
			y += v;
			while (n < FALL_TICKS.length && -y >= n) {
				FALL_TICKS[n++] = tick;
			}
		}
	}

	private static final Direction[] CARDINALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final int MAX_WATER_FALL = 20;

	/**
	 * @param allowBreak   mine blocks in the way (still subject to BreakPolicy)
	 * @param allowPlace   place throwaway blocks (bridges, pillars)
	 * @param allowParkour jump gaps
	 * @param placeBudget  how many blocks we carry to place
	 * @param maxFall      highest safe fall (3 = no damage)
	 */
	public record Options(boolean allowBreak, boolean allowPlace, boolean allowParkour, int placeBudget, int maxNodes, int maxRange, int maxFall) {
		public static Options basic(int maxNodes, int maxRange) {
			return new Options(false, false, true, 0, maxNodes, maxRange, 3);
		}
	}

	private static final class Node {
		final BlockPos pos;
		double g;
		final double h;
		double f;
		Node parent;
		MoveType type;
		@Nullable
		BlockPos place;
		List<BlockPos> breaks = List.of();
		int placed;
		/** Steps in a row with the head under water (0 = can breathe here). */
		int under;
		boolean closed;

		Node(BlockPos pos, double g, double h) {
			this.pos = pos;
			this.g = g;
			this.h = h;
			this.f = g + h;
		}
	}

	private final Terrain terrain;
	private final Goal goal;
	private final Options options;
	private final BlockPos start;
	private final LongOpenHashSet avoid;
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final Node startNode;
	private Node best;
	private int expanded;
	private Status status = Status.SEARCHING;
	@Nullable
	private List<PathStep> result;

	public PathFinder(Terrain terrain, BlockPos start, Goal goal, Options options, LongOpenHashSet avoid) {
		this.terrain = terrain;
		this.goal = goal;
		this.options = options;
		this.start = start.toImmutable();
		this.avoid = avoid;
		this.startNode = new Node(this.start, 0, h(this.start));
		startNode.type = MoveType.START;
		nodes.put(this.start.asLong(), startNode);
		open.add(startNode);
		best = startNode;
	}

	/**
	 * Most steps a path may stay with the head under water, on a full air bar: about half of what
	 * a breath lasts swimming, so there's slack for slow swimming, a wrong turn or a fight.
	 */
	public static final int MAX_UNDER = 14;

	/**
	 * How much air we start with (0-300 as {@code PlayerEntity#getAir}). Under water with a half
	 * empty air bar, a path may only stay under for what's left of it.
	 */
	public PathFinder startAir(int air, boolean submerged) {
		if (submerged) {
			startNode.under = MAX_UNDER - (int) Math.floor(MAX_UNDER * Math.max(0, Math.min(300, air)) / 300.0);
		}
		return this;
	}

	public static double fallTicks(int blocks) {
		return FALL_TICKS[Math.min(Math.max(blocks, 1), FALL_TICKS.length - 1)];
	}

	private double h(BlockPos pos) {
		return goal.heuristic(pos) * H_PER_BLOCK;
	}

	public Status status() {
		return status;
	}

	public int expanded() {
		return expanded;
	}

	@Nullable
	public List<PathStep> result() {
		return result;
	}

	/** Expand up to {@code maxExpansions} nodes (also stops when the tick budget runs out). */
	public Status step(int maxExpansions) {
		int n = 0;
		while (status == Status.SEARCHING && n < maxExpansions) {
			if (n > 0 && (n & 15) == 0 && !TickBudget.hasTime()) {
				break;
			}
			Node cur = open.poll();
			if (cur == null) {
				finish();
				break;
			}
			if (cur.closed) {
				continue;
			}
			cur.closed = true;
			n++;
			expanded++;
			if (goal.isGoal(cur.pos)) {
				result = build(cur);
				status = Status.FOUND;
				break;
			}
			if (cur.h < best.h || (cur.h == best.h && cur.g < best.g)) {
				best = cur;
			}
			if (expanded >= options.maxNodes()) {
				finish();
				break;
			}

			expand(cur);
		}
		return status;
	}

	/** Out of nodes: fall back to the path toward the node closest to the goal. */
	private void finish() {
		if (best != startNode && best.h < startNode.h - H_PER_BLOCK) {
			result = build(best);
			status = Status.PARTIAL;
		} else {
			status = Status.FAILED;
		}
	}

	/**
	 * Stop searching and take the path to the best node so far, if it gets at least
	 * {@code minProgress} blocks closer (Baritone's primary timeout).
	 * @return true if the search is now PARTIAL
	 */
	public boolean settle(double minProgress) {
		if (status != Status.SEARCHING || progress() < minProgress) {
			return false;
		}
		result = build(best);
		status = Status.PARTIAL;
		return true;
	}

	/** How much closer the best node got to the goal (blocks), for deciding whether to escape. */
	public double progress() {
		return (startNode.h - best.h) / H_PER_BLOCK;
	}

	private List<PathStep> build(Node end) {
		List<PathStep> steps = new ArrayList<>();
		for (Node n = end; n != null && n != startNode; n = n.parent) {
			double floor = n.place != null || !n.breaks.isEmpty() || n.type == MoveType.PILLAR ? n.pos.getY() : terrain.floorY(n.pos);
			if (terrain.isClimbable(n.pos) || terrain.isWater(n.pos)) {
				floor = n.pos.getY();
			}
			steps.add(new PathStep(n.pos, n.type, floor, n.place, n.breaks, n.parent == null ? 0 : n.g - n.parent.g));
		}
		Collections.reverse(steps);
		return steps;
	}

	// ---- movements -------------------------------------------------------------------------------

	private void expand(Node cur) {
		BlockPos p = cur.pos;
		boolean inWater = terrain.isWater(p);
		for (Direction d : CARDINALS) {
			traverse(cur, p, d, inWater);
			ascend(cur, p, d);
			descend(cur, p, d);
			parkour(cur, p, d, inWater);
		}
		for (int[] d : DIAGONALS) {
			diagonal(cur, p, d[0], d[1]);
		}
		up(cur, p, inWater);
		down(cur, p, inWater);
	}

	/** Solid under this node to stand on / place against, counting a block we planned to place there. */
	private boolean solidBelow(Node cur) {
		BlockPos below = cur.pos.down();
		return (cur.place != null && cur.place.equals(below)) || terrain.placeableAgainst(below);
	}

	private boolean usable(BlockPos t) {
		return inRange(t) && terrain.kind(t) != Terrain.UNLOADED;
	}

	private boolean deadly(BlockPos t) {
		byte k = terrain.kind(t);
		return k == Terrain.LAVA || k == Terrain.DANGER;
	}

	/** Cost of clearing the given cells; +inf if not allowed. Adds what must be mined to breaks. */
	private double clearCost(List<BlockPos> breaks, BlockPos... cells) {
		double c = 0;
		for (BlockPos b : cells) {
			if (terrain.passable(b)) {
				continue;
			}
			if (!options.allowBreak() || deadly(b) || terrain.isDoor(b)) {
				return Double.POSITIVE_INFINITY;
			}
			double bc = terrain.breakCost(b);
			if (bc == Double.POSITIVE_INFINITY) {
				return bc;
			}
			c += bc;
			breaks.add(b.toImmutable());
		}
		return c;
	}

	private void traverse(Node cur, BlockPos p, Direction d, boolean inWater) {
		BlockPos t = p.offset(d);
		if (!usable(t) || deadly(t) || deadly(t.up())) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(2);
		double cost = clearCost(breaks, t.up(), t);
		if (cost == Double.POSITIVE_INFINITY) {
			return;
		}
		double door = terrain.isDoor(t) || terrain.isDoor(t.up()) ? DOOR_COST : 0;
		boolean water = terrain.isWater(t);
		BlockPos place = null;
		if (!terrain.support(t.down()) && !water && !terrain.isClimbable(t)) {
			// Nothing to stand on: bridge it if we can, otherwise this is a descend.
			if (!options.allowPlace() || cur.placed >= options.placeBudget() || inWater || !terrain.replaceable(t.down())
				|| !solidBelow(cur) || !breaks.isEmpty()) {
				return;
			}
			place = t.down().toImmutable();
			cost += PLACE;
		}
		double rise = place != null || !breaks.isEmpty() || cur.place != null ? 0 : terrain.floorY(t) - terrain.floorY(p);
		MoveType type = place != null ? MoveType.BRIDGE : water ? MoveType.SWIM : MoveType.WALK;
		if (rise > 0.6) {
			if (rise > 1.25 || !terrain.passable(p.up(2))) {
				return;
			}
			type = MoveType.JUMP;
			cost += JUMP;
		}
		cost += (water || inWater ? WATER_COST : WALK) + door + terrain.hazard(t);
		add(cur, t, cost, type, place, breaks);
	}

	private void diagonal(Node cur, BlockPos p, int dx, int dz) {
		BlockPos t = p.add(dx, 0, dz);
		if (!usable(t) || !terrain.standable(t) || terrain.isDoor(t) || terrain.isDoor(t.up()) || terrain.isClimbable(t)) {
			return;
		}
		if (!terrain.clearBody(p.add(dx, 0, 0)) || !terrain.clearBody(p.add(0, 0, dz))) {
			return; // would clip a corner
		}
		double rise = terrain.floorY(t) - terrain.floorY(p);
		if (rise > 0.6 || rise < -0.6) {
			return;
		}
		double base = terrain.isWater(t) ? WATER_COST : WALK;
		add(cur, t, base * Math.sqrt(2) + terrain.hazard(t), terrain.isWater(t) ? MoveType.SWIM : MoveType.WALK, null, List.of());
	}

	private void ascend(Node cur, BlockPos p, Direction d) {
		BlockPos t = p.offset(d);
		BlockPos u = t.up();
		if (!usable(u) || !terrain.support(t) || deadly(u) || deadly(u.up()) || terrain.isClimbable(p)) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(3);
		double cost = clearCost(breaks, p.up(2), u.up(), u);
		if (cost == Double.POSITIVE_INFINITY || terrain.isDoor(u) || terrain.isDoor(u.up())) {
			return;
		}
		if (breaks.contains(u.up()) && terrain.isFalling(u.up(2))) {
			return; // sand would fall on our head
		}
		if (breaks.isEmpty() && terrain.floorY(u) - terrain.floorY(p) > 1.25) {
			return;
		}
		add(cur, u, cost + JUMP + JUMP_PENALTY + WALK + terrain.hazard(u), MoveType.JUMP, null, breaks);
	}

	private void descend(Node cur, BlockPos p, Direction d) {
		BlockPos t = p.offset(d);
		if (!usable(t) || deadly(t) || deadly(t.up()) || terrain.support(t.down()) || terrain.isWater(t) || terrain.isClimbable(t)) {
			return; // standable (traverse) or not a ledge
		}
		List<BlockPos> breaks = new ArrayList<>(2);
		double cost = clearCost(breaks, t.up(), t);
		if (cost == Double.POSITIVE_INFINITY) {
			return;
		}
		for (int k = 1; k <= MAX_WATER_FALL; k++) {
			BlockPos below = t.down(k);
			byte kind = terrain.kind(below);
			if (kind == Terrain.UNLOADED || kind == Terrain.LAVA || kind == Terrain.DANGER) {
				return;
			}
			if (terrain.standable(below)) {
				boolean soft = terrain.isWater(below) || terrain.isClimbable(below);
				if (k > options.maxFall() && !soft) {
					return;
				}
				if (k > 1 && !breaks.isEmpty()) {
					return; // only dig into a one-block step down
				}
				add(cur, below, cost + WALK_OFF + fallTicks(k) + CENTER + terrain.hazard(below), MoveType.FALL, null, breaks);
				return;
			}
			if (!terrain.passable(below)) {
				return;
			}
		}
	}

	private void parkour(Node cur, BlockPos p, Direction d, boolean inWater) {
		if (!options.allowParkour() || inWater || !(terrain.support(p.down()) || solidBelow(cur)) || !terrain.passable(p.up(2))) {
			return;
		}
		BlockPos first = p.offset(d);
		if (!usable(first) || terrain.support(first.down()) || !terrain.clearBody(first) || terrain.isWater(first.down())) {
			return; // not a gap
		}
		if (missDrop(first) > PARKOUR_MAX_MISS_DROP) {
			return; // a missed jump here would be a bad fall (cliffs, ravines): go round
		}
		for (int dist = 2; dist <= 3; dist++) {
			BlockPos gap = p.offset(d, dist - 1);
			if (!terrain.clearBody(gap) || !terrain.passable(gap.up(2)) || terrain.support(gap.down()) || missDrop(gap) > PARKOUR_MAX_MISS_DROP) {
				return;
			}
			BlockPos land = p.offset(d, dist);
			if (!usable(land) || deadly(land)) {
				return;
			}
			if (terrain.standable(land) && !terrain.isWater(land) && Math.abs(terrain.floorY(land) - terrain.floorY(p)) < 0.1
				&& terrain.passable(land.up(2))) {
				add(cur, land, SPRINT * dist + JUMP + JUMP_PENALTY + terrain.hazard(land), MoveType.PARKOUR, null, List.of());
				return;
			}
		}
	}

	/**
	 * Deepest gap worth jumping: a miss hurts (7 hearts at most) but isn't fatal. Over deeper
	 * drops (cliff edges, ravines) we go round. Baritone keeps parkour off altogether by default.
	 */
	private static final int PARKOUR_MAX_MISS_DROP = 10;

	/** How far a missed jump over this gap column would fall (water at the bottom is a soft landing). */
	private int missDrop(BlockPos gap) {
		for (int i = 1; i <= 24; i++) {
			BlockPos b = gap.down(i);
			if (terrain.isWater(b)) {
				return 0;
			}
			if (terrain.support(b)) {
				return i - 1;
			}
		}
		return Integer.MAX_VALUE;
	}

	private void up(Node cur, BlockPos p, boolean inWater) {
		BlockPos u = p.up();
		if (!usable(u) || deadly(u) || deadly(u.up())) {
			return;
		}
		if (terrain.nearFallingWater(p) || terrain.nearFallingWater(u)) {
			return; // a waterfall drags us down: no climbing (vines, ladders) or swimming up through it
		}
		if (terrain.isClimbable(p) && (terrain.isClimbable(u) || terrain.passable(u))) {
			if (terrain.passable(u.up()) || terrain.isClimbable(u.up())) {
				add(cur, u, LADDER_UP, MoveType.CLIMB, null, List.of());
			}
			return;
		}
		if (inWater) {
			if (terrain.isWater(u) || terrain.standable(u)) {
				add(cur, u, WATER_COST, MoveType.SWIM, null, List.of());
			}
			return;
		}
		// Pillar: jump and place a block where our feet were.
		if (!options.allowPlace() || cur.placed >= options.placeBudget() || !solidBelow(cur) || !terrain.replaceable(p)) {
			return;
		}
		List<BlockPos> breaks = new ArrayList<>(1);
		double cost = clearCost(breaks, u.up());
		if (cost == Double.POSITIVE_INFINITY || (!breaks.isEmpty() && terrain.isFalling(u.up(2)))) {
			return;
		}
		add(cur, u, cost + JUMP + JUMP_PENALTY + PLACE, MoveType.PILLAR, p.toImmutable(), breaks);
	}

	private void down(Node cur, BlockPos p, boolean inWater) {
		BlockPos d = p.down();
		if (!usable(d) || deadly(d)) {
			return;
		}
		if (terrain.isClimbable(d)) {
			add(cur, d, LADDER_DOWN, MoveType.CLIMB, null, List.of());
			return;
		}
		if (inWater) {
			if (terrain.isWater(d)) {
				add(cur, d, WATER_COST, MoveType.SWIM, null, List.of());
			}
			return;
		}
		if (!options.allowBreak() || terrain.kind(d) != Terrain.SOLID || terrain.isClimbable(p)) {
			return;
		}
		BlockPos under = d.down();
		boolean landing = terrain.support(under) || terrain.isWater(under) || terrain.isClimbable(under);
		if (!landing || terrain.kind(under) == Terrain.LAVA) {
			return; // never dig straight down into the unknown
		}
		double bc = terrain.breakCost(d);
		if (bc == Double.POSITIVE_INFINITY) {
			return;
		}
		add(cur, d, bc + fallTicks(1) + CENTER, MoveType.DOWNWARD, null, List.of(d.toImmutable()));
	}

	private boolean inRange(BlockPos t) {
		int r = options.maxRange();
		return Math.abs(t.getX() - start.getX()) <= r && Math.abs(t.getZ() - start.getZ()) <= r && Math.abs(t.getY() - start.getY()) <= r;
	}

	private void add(Node from, BlockPos pos, double cost, MoveType type, @Nullable BlockPos place, List<BlockPos> breaks) {
		long key = pos.asLong();
		if (avoid.contains(key)) {
			return;
		}
		// Breath: a run of steps with the head under water must end before the air runs out, and the
		// longer the run, the more it costs (so a dry way round wins when there is one).
		int under = terrain.isWater(pos.up()) ? from.under + 1 : 0;
		if (under > MAX_UNDER) {
			return;
		}
		if (under > 0) {
			cost += 0.5 * under;
		}
		double g = from.g + cost;
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(pos.toImmutable(), g, h(pos));
			nodes.put(key, n);
		} else if (n.closed || g >= n.g - 1e-6) {
			return;
		} else {
			n.g = g;
			n.f = g + n.h;
		}
		n.parent = from;
		n.type = type;
		n.place = place;
		n.breaks = breaks;
		n.placed = from.placed + (place != null ? 1 : 0);
		n.under = under;
		open.add(n); // duplicates are skipped later via the closed flag
	}
}

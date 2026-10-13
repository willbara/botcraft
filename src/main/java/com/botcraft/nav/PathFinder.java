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
 * Time-sliced A* over feet positions with Baritone's movement set and costs (in game ticks).
 * Call {@link #step(int)} once per tick until it stops returning SEARCHING. Never loads chunks
 * (see {@link Terrain}).
 *
 * <p>Movements from each node, each costed as in Baritone's {@code pathing.movement}:
 * <ul>
 *   <li>traverse (4 dirs): walk or swim, mining the two cells in the way, or bridging onto a
 *       block we place (against a side, or sneaking and placing behind us);</li>
 *   <li>diagonal (4 dirs): through open space, or edging round one blocked corner;</li>
 *   <li>ascend (4 dirs): jump up one, mining the head room and the cell above the step, or
 *       placing the step if there is none;</li>
 *   <li>descend / fall (4 dirs): step down one, mining the three cells of the step (that is how a
 *       staircase is dug), or walk off a drop up to the safe fall height, further into water;</li>
 *   <li>downward: mine the block underneath and drop into it;</li>
 *   <li>pillar: jump and place a block underneath; ladder and vine climbing; swimming up and down;</li>
 *   <li>parkour: jump a gap of 1 or 2 blocks, landing level.</li>
 * </ul>
 * Breaking is gated by {@link BreakPolicy} (via Terrain), placing by a budget of blocks we carry.
 *
 * <p>When the search runs out: Baritone's two limits. Once some node is more than
 * {@link #MIN_PARTIAL} blocks from the start the search may stop at the primary node limit and
 * hand over a partial path to the best node by {@code h + g / k} (the most cost-aware coefficient
 * that gets that far). Until then it runs to the failure limit, and fails if still nothing got
 * that far.
 */
public class PathFinder {
	public enum Status { SEARCHING, FOUND, PARTIAL, FAILED }

	// Costs in ticks (Baritone ActionCosts).
	public static final double WALK = 20 / 4.317;
	public static final double SPRINT = 20 / 5.612;
	public static final double WATER_COST = 20 / 2.2;
	public static final double SWIM = 20 / 2.86;
	public static final double SNEAK = 20 / 1.3;
	public static final double LADDER_UP = 20 / 2.35;
	public static final double LADDER_DOWN = 20 / 3.0;
	public static final double PLACE = 20.0;
	public static final double JUMP_PENALTY = 2.0;
	public static final double WALK_OFF = WALK * 0.8;
	public static final double CENTER = WALK - WALK_OFF;
	public static final double DOOR_COST = 8.0;
	public static final double SQRT2 = Math.sqrt(2);
	/** Heuristic ticks per block: the sprint cost (Baritone's costHeuristic). */
	public static final double H_PER_BLOCK = 3.563;
	/**
	 * Extra cost of standing on leaves. Without it a forest canopy is cheaper than the forest
	 * floor (no leaves at head height to break), and companions walked over the treetops.
	 */
	public static final double LEAF_FLOOR = 10.0;
	/** Cost factor for cells on the path we were walking before the goal moved (Baritone: backtrackCostFavoringCoefficient). */
	public static final double FAVOR = 0.5;
	/** Extra cost of a cell another companion stands in or is about to step into. */
	public static final double BUSY_COST = 12.0;
	/**
	 * Extra cost of putting a block down at home (a bridge or pillar block left behind is litter
	 * someone has to clear up): walk round unless that's much longer.
	 */
	public static final double LITTER_COST = 100.0;
	/** Baritone's partial-path coefficients: the best node by h + g / k, most cost-aware first. */
	private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	/** A partial path that doesn't get this far from the start isn't worth taking (Baritone's MIN_DIST_PATH). */
	private static final double MIN_PARTIAL = 5;
	/** Cost improvements below this aren't worth re-propagating (Baritone's MIN_IMPROVEMENT). */
	private static final double MIN_IMPROVEMENT = 0.01;

	private static final double[] FALL_TICKS = new double[64];
	/** Ticks a jump takes to rise one block (Baritone: the 1.25 block leap less the 0.25 back down). */
	public static final double JUMP_ONE_BLOCK;

	static {
		for (int i = 0; i < FALL_TICKS.length; i++) {
			FALL_TICKS[i] = distanceToTicks(i);
		}
		JUMP_ONE_BLOCK = distanceToTicks(1.25) - distanceToTicks(0.25);
	}

	/** Baritone's ActionCosts.distanceToTicks: ticks to fall this far from rest. */
	private static double distanceToTicks(double distance) {
		if (distance == 0) {
			return 0;
		}
		double left = distance;
		int tick = 0;
		while (true) {
			double v = (Math.pow(0.98, tick) - 1) * -3.92;
			if (left <= v) {
				return tick + left / v;
			}
			left -= v;
			tick++;
		}
	}

	public static double fallTicks(int blocks) {
		return FALL_TICKS[Math.min(Math.max(blocks, 0), FALL_TICKS.length - 1)];
	}

	private static final Direction[] CARDINALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final int MAX_WATER_FALL = 24;

	/**
	 * @param allowBreak   mine blocks in the way (still subject to BreakPolicy)
	 * @param allowPlace   place throwaway blocks (bridges, pillars, missing steps)
	 * @param allowParkour jump gaps
	 * @param placeBudget  how many blocks we carry to place
	 * @param primaryNodes stop here once a partial path worth taking exists (Baritone's primary timeout)
	 * @param maxNodes     never expand more than this (Baritone's failure timeout)
	 * @param maxFall      highest safe fall (3 = no damage)
	 */
	public record Options(boolean allowBreak, boolean allowPlace, boolean allowParkour, int placeBudget, int primaryNodes, int maxNodes, int maxRange,
						  int maxFall) {
		public static Options basic(int maxNodes, int maxRange) {
			return new Options(false, false, true, 0, maxNodes, maxNodes, maxRange, 3);
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
		/** Fall damage taken on the way here (big drops when escaping), in health points. */
		int damage;
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
	/** Fall damage of the move being added (set around the add() call of a big drop). */
	private int damageNext;
	/** Where the partial path we handed over ends. */
	@Nullable
	private Node chosen;
	private final Node[] bestByCoefficient = new Node[COEFFICIENTS.length];
	private final double[] bestScore = new double[COEFFICIENTS.length];
	/** No node worth a partial path yet (nothing more than MIN_PARTIAL from the start). */
	private boolean failing = true;
	private final boolean leafFloor = com.botcraft.util.Experiments.on("leafFloor");
	/** Limit and cost runs of steps with the head under water (off: Baritone has no breath rule). */
	private static final boolean BREATH = com.botcraft.util.Experiments.on("breathLimit");
	private final boolean edging = com.botcraft.util.Experiments.on("diagonalEdging");
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
		java.util.Arrays.fill(bestByCoefficient, startNode);
		java.util.Arrays.fill(bestScore, startNode.h);
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

	private it.unimi.dsi.fastutil.longs.LongSet busy = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

	/** Cells others are in or about to walk through (see Blackboard): passable, but better avoided. */
	public PathFinder busy(it.unimi.dsi.fastutil.longs.LongSet cells) {
		this.busy = cells;
		return this;
	}

	/**
	 * Health points the whole path may lose to falls (0: no fall damage at all). Each drop past 3
	 * blocks spends from it, so two big drops in a row can't add up to a deadly one.
	 */
	private int fallBudget;

	/** Flat, clear walking is costed at sprint speed (we'll sprint it), like Baritone's canSprint. */
	private boolean sprint;

	public PathFinder sprint(boolean sprint) {
		this.sprint = sprint;
		return this;
	}

	private it.unimi.dsi.fastutil.longs.LongSet favored = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

	/**
	 * Cells of the path we were on before the goal moved: cheaper, so the new path keeps to the old
	 * route where it can instead of flipping to the other side of a tree each time.
	 */
	public PathFinder favor(it.unimi.dsi.fastutil.longs.LongSet cells) {
		this.favored = cells;
		return this;
	}

	public PathFinder fallBudget(int hp) {
		this.fallBudget = Math.max(0, hp);
		return this;
	}

	private double h(BlockPos pos) {
		return goal.heuristic(pos);
	}

	public Status status() {
		return status;
	}

	/** Where the search started (the cell the path's first step leaves from). */
	public BlockPos start() {
		return start;
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
			if (expanded >= options.maxNodes() || (!failing && expanded >= options.primaryNodes()) || borderFetches >= MAX_BORDER_FETCH) {
				finish();
				break;
			}
			expand(cur);
		}
		return status;
	}

	/** Out of nodes: the partial path to the best node so far, if one got far enough from the start. */
	private void finish() {
		Node end = partialEnd();
		if (end != null) {
			chosen = end;
			result = build(end);
			status = Status.PARTIAL;
		} else {
			status = Status.FAILED;
		}
	}

	/** Baritone's bestSoFar: the first coefficient whose best node is more than MIN_PARTIAL blocks out. */
	@Nullable
	private Node partialEnd() {
		for (Node n : bestByCoefficient) {
			if (n.pos.getSquaredDistance(start) > MIN_PARTIAL * MIN_PARTIAL) {
				return n;
			}
		}
		return null;
	}

	/** How much closer (in heuristic ticks) the path handed over gets to the goal. */
	public double progress() {
		Node n = chosen != null ? chosen : startNode;
		return startNode.h - n.h;
	}

	private List<PathStep> build(Node end) {
		List<PathStep> steps = new ArrayList<>();
		for (Node n = end; n != null && n != startNode; n = n.parent) {
			double floor = n.place != null || !n.breaks.isEmpty() || n.type == MoveType.PILLAR ? n.pos.getY() : terrain.floorY(n.pos);
			if (n.type == MoveType.CLIMB || terrain.isClimbable(n.pos) || terrain.isWater(n.pos)) {
				floor = n.pos.getY(); // (the cell above a ladder's top rung stands on nothing solid)
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
			ascend(cur, p, d, inWater);
			descend(cur, p, d, inWater);
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
		if (!inRange(t)) {
			return false;
		}
		if (terrain.kind(t) == Terrain.UNLOADED) {
			borderFetches++;
			return false;
		}
		return true;
	}

	/**
	 * Moves that ran into unloaded chunks. After {@link #MAX_BORDER_FETCH} the search has reached
	 * the edge of the known world and stops with what it has (Baritone's pathingMaxChunkBorderFetch);
	 * the rest is planned on the way, as more chunks load.
	 */
	private int borderFetches;
	private static final int MAX_BORDER_FETCH = 50;

	/** Something that hurts to walk into (Baritone's avoidWalkingInto, less water). */
	private boolean deadly(BlockPos t) {
		byte k = terrain.kind(t);
		return k == Terrain.LAVA || k == Terrain.DANGER;
	}

	/** Baritone's fullyPassable: air we can jump through, not a door, ladder, vine or fluid. */
	private boolean fullyPassable(BlockPos t) {
		return terrain.kind(t) == Terrain.AIR && terrain.passable(t);
	}

	/**
	 * Cost of clearing the given cells; +inf if not allowed. Adds what must be mined to breaks
	 * (made on first use, so clear moves allocate nothing).
	 */
	private double clearCost(List<BlockPos>[] breaks, BlockPos... cells) {
		double c = 0;
		for (BlockPos b : cells) {
			if (terrain.passable(b)) {
				continue;
			}
			if (!options.allowBreak() || deadly(b)) {
				return Double.POSITIVE_INFINITY;
			}
			double bc = terrain.breakCost(b);
			if (bc == Double.POSITIVE_INFINITY) {
				return bc;
			}
			c += bc;
			if (breaks[0] == null) {
				breaks[0] = new ArrayList<>(3);
			}
			breaks[0].add(b.toImmutable());
		}
		return c;
	}

	private static List<BlockPos> list(List<BlockPos>[] breaks) {
		return breaks[0] == null ? List.of() : breaks[0];
	}

	/** Is there a solid face next to {@code at} to place a block against, other than from the {@code from} column? */
	private boolean placeableBeside(BlockPos at, @Nullable BlockPos from) {
		if (terrain.placeableAgainst(at.down())) {
			return true;
		}
		for (Direction d : CARDINALS) {
			BlockPos s = at.offset(d);
			if ((from == null || s.getX() != from.getX() || s.getZ() != from.getZ()) && terrain.placeableAgainst(s)) {
				return true;
			}
		}
		return false;
	}

	private double placeCost(BlockPos at) {
		return PLACE + (terrain.inHome(at) ? LITTER_COST : 0);
	}

	private boolean canPlace(Node cur, BlockPos at) {
		return options.allowPlace() && cur.placed < options.placeBudget() && terrain.replaceable(at);
	}

	private void traverse(Node cur, BlockPos p, Direction d, boolean inWater) {
		BlockPos t = p.offset(d);
		if (!usable(t) || deadly(t) || deadly(t.up())) {
			return;
		}
		BlockPos destOn = t.down();
		boolean water = terrain.isWater(t);
		boolean walkable = terrain.support(destOn) || terrain.isClimbable(t) || (water && terrain.standable(t));
		@SuppressWarnings("unchecked")
		List<BlockPos>[] breaks = new List[1];
		double clear = clearCost(breaks, t.up(), t); // top first, as Baritone mines them
		if (clear == Double.POSITIVE_INFINITY) {
			return;
		}
		if (terrain.isClimbable(p)) {
			clear *= 5; // mining from a ladder is slow
		}
		double door = terrain.isDoor(t) || terrain.isDoor(t.up()) ? DOOR_COST : 0;
		if (walkable) {
			if (breaks[0] == null && cur.place == null && terrain.floorY(t) - terrain.floorY(p) > 0.6) {
				return; // a step up: that's an ascend
			}
			double move = water || inWater ? WATER_COST : sprint && clear == 0 && door == 0 ? SPRINT : WALK;
			add(cur, t, move + clear + door + terrain.hazard(t), water ? MoveType.SWIM : MoveType.WALK, null, list(breaks));
			return;
		}
		// Nothing to stand on: bridge it, placing against a side if there is one, else sneaking out
		// and placing behind us against the block we stand on.
		if (inWater || water || terrain.isClimbable(p) || !canPlace(cur, destOn)) {
			return;
		}
		double walk = WALK;
		if (!placeableBeside(destOn, p)) {
			if (!solidBelow(cur)) {
				return;
			}
			walk = SNEAK;
		}
		add(cur, t, walk + placeCost(destOn) + clear + door + terrain.hazard(t), MoveType.BRIDGE, destOn.toImmutable(), list(breaks));
	}

	private void diagonal(Node cur, BlockPos p, int dx, int dz) {
		BlockPos t = p.add(dx, 0, dz);
		if (!usable(t) || !terrain.clearBody(t) || terrain.isDoor(t) || terrain.isDoor(t.up()) || terrain.isClimbable(t) || deadly(t) || deadly(t.up())) {
			return;
		}
		boolean water = terrain.isWater(t) || terrain.isWater(p);
		if (!terrain.support(t.down()) && !(terrain.isWater(t) && terrain.standable(t))) {
			return; // no diagonal descend or ascend (off in Baritone too)
		}
		BlockPos a = p.add(dx, 0, 0);
		BlockPos b = p.add(0, 0, dz);
		if (terrain.isLava(a.down()) || terrain.isLava(b.down())) {
			return; // cutting across a lava corner
		}
		boolean aClear = sideClear(a);
		boolean bClear = sideClear(b);
		if (!aClear && !bClear) {
			return;
		}
		double mult;
		if (!aClear || !bClear) {
			// Like Baritone: one side clear is enough, we slide round the corner of the other. Not
			// past anything that hurts to brush against, and not in water or off a ladder.
			BlockPos blocked = aClear ? b : a;
			BlockPos open = aClear ? a : b;
			if (!edging || water || terrain.isClimbable(p) || deadly(blocked) || deadly(blocked.up()) || deadly(open) || deadly(open.up())) {
				return;
			}
			// Sliding round the corner puts the whole body over the open side for a moment: it
			// needs a floor there too, or we step off into whatever drop is beside the corner.
			if (!terrain.support(open.down())) {
				return;
			}
			mult = WALK * (SQRT2 - 0.001);
		} else {
			mult = water ? WATER_COST : sprint ? SPRINT : WALK;
		}
		double rise = terrain.floorY(t) - terrain.floorY(p);
		if (rise > 0.6 || rise < -0.6) {
			return;
		}
		add(cur, t, mult * SQRT2 + terrain.hazard(t), terrain.isWater(t) ? MoveType.SWIM : MoveType.WALK, null, List.of());
	}

	/** A diagonal's side cell the body can pass through without opening or bumping anything. */
	private boolean sideClear(BlockPos s) {
		return terrain.clearBody(s) && !terrain.isDoor(s) && !terrain.isDoor(s.up());
	}

	private void ascend(Node cur, BlockPos p, Direction d, boolean inWater) {
		BlockPos t = p.offset(d);
		BlockPos u = t.up();
		if (!usable(u) || deadly(u) || deadly(u.up()) || terrain.isClimbable(p)) {
			return;
		}
		BlockPos place = null;
		double placing = 0;
		if (!terrain.support(t)) {
			// No step to jump onto: put one there (Baritone's MovementAscend placement).
			if (!canPlace(cur, t) || !placeableBeside(t, p)) {
				return;
			}
			place = t.toImmutable();
			placing = placeCost(t);
		}
		if (terrain.isFalling(p.up(3)) && (terrain.passable(p.up()) || !terrain.isFalling(p.up(2)))) {
			return; // sand would fall on our head once we've broken the head room
		}
		@SuppressWarnings("unchecked")
		List<BlockPos>[] breaks = new List[1];
		double clear = clearCost(breaks, u, p.up(2), u.up());
		if (clear == Double.POSITIVE_INFINITY || terrain.isDoor(u) || terrain.isDoor(u.up())) {
			return;
		}
		if (breaks[0] == null && place == null && terrain.floorY(u) - terrain.floorY(p) > 1.25) {
			return;
		}
		add(cur, u, Math.max(JUMP_ONE_BLOCK, WALK) + JUMP_PENALTY + placing + clear + terrain.hazard(u), MoveType.JUMP, place, list(breaks));
	}

	private void descend(Node cur, BlockPos p, Direction d, boolean inWater) {
		BlockPos t = p.offset(d);
		if (!usable(t) || deadly(t) || deadly(t.up()) || terrain.isClimbable(p)) {
			return;
		}
		BlockPos dest = t.down();
		if (deadly(dest) || terrain.isClimbable(dest) || terrain.isFlowingWater(t) || terrain.isFlowingWater(t.up()) || terrain.isFlowingWater(dest)) {
			return; // (Baritone: no walking off into, or falling through, moving water)
		}
		// The three cells of the step: where we land, the head room there, and the cell our head
		// passes as we step off. Mining them is how a staircase gets dug.
		@SuppressWarnings("unchecked")
		List<BlockPos>[] breaks = new List[1];
		double clear = clearCost(breaks, t.up(), t, dest);
		if (clear == Double.POSITIVE_INFINITY || terrain.isDoor(dest) || terrain.isDoor(t) || terrain.isDoor(t.up())) {
			return;
		}
		BlockPos below = dest.down();
		if (terrain.isWater(dest) && terrain.standable(dest) && terrain.support(dest.down())) {
			add(cur, dest, clear + WALK_OFF + Math.max(fallTicks(1), CENTER) + terrain.hazard(dest), MoveType.FALL, null, list(breaks));
			return;
		}
		if (terrain.support(below)) {
			add(cur, dest, clear + WALK_OFF + Math.max(fallTicks(1), CENTER) + terrain.hazard(dest), MoveType.FALL, null, list(breaks));
			return;
		}
		// Further down: only through what is already open, into still water with ground under it, onto solid
		// ground within the safe fall height (further only on a fall budget).
		if (breaks[0] != null && terrain.isFalling(t.up(2))) {
			return;
		}
		if (!terrain.passable(below) && !terrain.isWater(below)) {
			return;
		}
		for (int k = 2; k <= MAX_WATER_FALL; k++) {
			BlockPos c = t.down(k);
			byte kind = terrain.kind(c);
			if (kind == Terrain.UNLOADED || kind == Terrain.LAVA || kind == Terrain.DANGER) {
				return;
			}
			if (kind == Terrain.WATER) {
				// Baritone: still water with ground right under it (deeper, we'd sink past the landing).
				if (terrain.isFlowingWater(c) || !terrain.standable(c) || !terrain.support(c.down())) {
					return;
				}
				add(cur, c, clear + WALK_OFF + fallTicks(k) + terrain.hazard(c), MoveType.FALL, null, list(breaks));
				return;
			}
			if (kind == Terrain.CLIMBABLE) {
				if (k - 1 > 11) {
					return; // falling past 11 blocks we don't catch the ladder
				}
				add(cur, c, clear + WALK_OFF + fallTicks(k) + LADDER_DOWN + terrain.hazard(c), MoveType.FALL, null, list(breaks));
				return;
			}
			if (terrain.passable(c)) {
				continue;
			}
			if (!terrain.support(c) || terrain.top(c) < 1.0) {
				return; // a fence top, or a half slab (landing on one is glitchy)
			}
			int drop = k - 1;
			int dmg = Math.max(0, drop - 3);
			if (drop > options.maxFall() || (dmg > 0 && cur.damage + dmg > fallBudget)) {
				return;
			}
			damageNext = dmg;
			add(cur, c.up(), clear + WALK_OFF + fallTicks(k) + terrain.hazard(c.up()) + dmg * 10, MoveType.FALL, null, list(breaks));
			damageNext = 0;
			return;
		}
	}

	private void parkour(Node cur, BlockPos p, Direction d, boolean inWater) {
		if (!options.allowParkour() || inWater || !(terrain.support(p.down()) || solidBelow(cur)) || !fullyPassable(p.up(2))) {
			return;
		}
		if (terrain.isClimbable(p.down()) || (terrain.kind(p.down()) == Terrain.SOLID && terrain.top(p.down()) < 1.0)) {
			return; // no jumping off a ladder, stairs or a half slab
		}
		if (leafFloor && terrain.isLeaves(p.down())) {
			return; // no jumping from treetop to treetop
		}
		BlockPos first = p.offset(d);
		if (!usable(first) || !fullyPassable(first) || terrain.support(first.down()) || !fullyPassable(first.up()) || !fullyPassable(first.up(2))) {
			return; // not a gap, or no room to jump
		}
		if (deadly(first.down())) {
			return;
		}
		// Gaps of 1 or 2 blocks, landing level. Baritone also jumps 3-block gaps and lands one block
		// up, both only at a full sprint with a run-up; on the nav bench every one of those missed
		// (12 of 12 missed jumps were those two kinds), so they're left out until there's a run-up.
		int maxJump = 3;
		for (int i = 2; i <= maxJump; i++) {
			BlockPos dest = p.offset(d, i);
			if (!usable(dest)) {
				return;
			}
			if (!fullyPassable(dest.up()) || !fullyPassable(dest.up(2)) || !fullyPassable(dest)) {
				return;
			}
			if (terrain.support(dest.down())) {
				if (overshootSafe(dest.offset(d)) && !(leafFloor && terrain.isLeaves(dest.down()))) {
					add(cur, dest, WALK * i + JUMP_PENALTY + terrain.hazard(dest), MoveType.PARKOUR, null, List.of());
				}
				return;
			}
			if (!fullyPassable(dest.up(3))) {
				return;
			}
		}
	}

	/** We overshoot a landing by a little: the next cell along must not hurt to walk into. */
	private boolean overshootSafe(BlockPos c) {
		return !deadly(c) && !deadly(c.up()) && !terrain.isWater(c) && !terrain.isWater(c.up());
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
		if (!canPlace(cur, p) || !(solidBelow(cur) || placeableBeside(p, null))) {
			return;
		}
		@SuppressWarnings("unchecked")
		List<BlockPos>[] breaks = new List[1];
		double clear = clearCost(breaks, u.up());
		if (clear == Double.POSITIVE_INFINITY || (breaks[0] != null && terrain.isFalling(u.up(2)))) {
			return;
		}
		add(cur, u, clear + JUMP_ONE_BLOCK + JUMP_PENALTY + placeCost(p), MoveType.PILLAR, p.toImmutable(), list(breaks));
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
		add(cur, d, bc + fallTicks(1), MoveType.DOWNWARD, null, List.of(d.toImmutable()));
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
		int under = terrain.isWater(pos.up()) && BREATH ? from.under + 1 : 0;
		if (under > MAX_UNDER) {
			return;
		}
		if (under > 0) {
			cost += 0.5 * under;
		}
		if (leafFloor && terrain.isLeaves(pos.down()) && !pos.down().equals(place) && !terrain.isWater(pos) && !terrain.isClimbable(pos)) {
			cost += LEAF_FLOOR;
		}
		if (!favored.isEmpty() && favored.contains(key)) {
			cost *= FAVOR;
		}
		if (!busy.isEmpty() && busy.contains(key)) {
			cost += BUSY_COST;
		}
		double g = from.g + cost;
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(pos.toImmutable(), g, h(pos));
			nodes.put(key, n);
		} else if (n.closed || n.g - g < MIN_IMPROVEMENT) {
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
		n.damage = from.damage + damageNext;
		open.add(n); // duplicates are skipped later via the closed flag
		for (int i = 0; i < COEFFICIENTS.length; i++) {
			double score = n.h + n.g / COEFFICIENTS[i];
			if (bestScore[i] - score > MIN_IMPROVEMENT) {
				bestScore[i] = score;
				bestByCoefficient[i] = n;
				if (failing && n.pos.getSquaredDistance(start) > MIN_PARTIAL * MIN_PARTIAL) {
					failing = false;
				}
			}
		}
	}
}

package com.mcfriends.nav;

import com.mcfriends.util.TickBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Time-sliced A* over feet positions. Call {@link #step(int)} once per tick until it stops
 * returning SEARCHING. Never loads chunks (see {@link Terrain}).
 *
 * <p>Moves: walk (8 directions, no corner cutting), jump up 1, drop up to 3 (deeper only
 * into water), swim, walk through wooden doors/gates, and optionally break the feet/head
 * blocks or place a block to bridge a gap. Costs are roughly "ticks / 5" so that a
 * 1-block walk costs 1.
 */
public class PathFinder {
	public enum Status { SEARCHING, FOUND, PARTIAL, FAILED }

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final int MAX_SAFE_FALL = 3;
	private static final int MAX_WATER_FALL = 12;
	private static final double HEURISTIC_WEIGHT = 1.15; // slightly greedy: faster, near-optimal

	public record Options(boolean allowBreak, boolean allowBridge, int maxNodes, int maxRange) {
	}

	private static final class Node {
		final BlockPos pos;
		double g;
		final double h;
		double f;
		Node parent;
		MoveType type;
		BlockPos aux;
		boolean closed;

		Node(BlockPos pos, double g, double h) {
			this.pos = pos;
			this.g = g;
			this.h = h;
			this.f = g + h * HEURISTIC_WEIGHT;
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
		this.startNode = new Node(this.start, 0, goal.heuristic(this.start));
		startNode.type = MoveType.START;
		nodes.put(this.start.asLong(), startNode);
		open.add(startNode);
		best = startNode;
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
		if (best != startNode && best.h < startNode.h - 1.0) {
			result = build(best);
			status = Status.PARTIAL;
		} else {
			status = Status.FAILED;
		}
	}

	private List<PathStep> build(Node end) {
		List<PathStep> steps = new ArrayList<>();
		for (Node n = end; n != null && n != startNode; n = n.parent) {
			double floor = n.type == MoveType.BRIDGE || n.type == MoveType.BREAK ? n.pos.getY() : terrain.floorY(n.pos);
			steps.add(new PathStep(n.pos, n.type, floor, n.aux));
		}
		Collections.reverse(steps);
		return steps;
	}

	private void expand(Node cur) {
		BlockPos p = cur.pos;
		double curFloor = terrain.floorY(p);
		boolean inWater = terrain.isWater(p);

		for (int[] d : DIRS) {
			boolean diag = d[0] != 0 && d[1] != 0;
			double base = diag ? 1.414 : 1.0;
			BlockPos t = p.add(d[0], 0, d[1]);
			if (!inRange(t)) {
				continue;
			}
			if (diag && !(terrain.clearBody(p.add(d[0], 0, 0)) && terrain.clearBody(p.add(0, 0, d[1])))) {
				continue; // would clip a corner
			}
			if (terrain.kind(t) == Terrain.UNLOADED) {
				continue;
			}

			// Same level.
			if (terrain.standable(t)) {
				double rise = terrain.floorY(t) - curFloor;
				double door = terrain.isDoor(t) || terrain.isDoor(t.up()) ? (diag ? Double.NaN : 1.5) : 0;
				if (Double.isNaN(door)) {
					continue;
				}
				if (rise <= 0.6) {
					add(cur, t, base + door + terrain.hazard(t), terrain.isWater(t) ? MoveType.SWIM : MoveType.WALK, null);
				} else if (rise <= 1.25 && terrain.passable(p.up(2))) {
					add(cur, t, base + 1.5 + door + terrain.hazard(t), MoveType.JUMP, null);
				}
				continue;
			}

			// Jump up one block.
			BlockPos up = t.up();
			if (!diag && terrain.standable(up) && terrain.passable(p.up(2)) && !terrain.isDoor(up) && !terrain.isDoor(up.up())
				&& terrain.floorY(up) - curFloor <= 1.25) {
				add(cur, up, base + 1.5 + terrain.hazard(up), MoveType.JUMP, null);
				continue;
			}

			// Walk off an edge.
			if (terrain.clearBody(t)) {
				boolean landed = false;
				for (int k = 1; k <= MAX_WATER_FALL; k++) {
					BlockPos down = t.down(k);
					byte kind = terrain.kind(down);
					if (kind == Terrain.UNLOADED || kind == Terrain.LAVA || kind == Terrain.DANGER) {
						break;
					}
					if (terrain.standable(down)) {
						boolean water = terrain.isWater(down);
						if (k <= MAX_SAFE_FALL || water) {
							add(cur, down, base + k * 0.5 + terrain.hazard(down), MoveType.FALL, null);
						}
						landed = true;
						break;
					}
					if (!terrain.passable(down)) {
						break;
					}
				}
				if (!landed && options.allowBridge() && !diag && !inWater && terrain.placeableAgainst(p.down())
					&& (terrain.kind(t.down()) == Terrain.AIR || terrain.kind(t.down()) == Terrain.WATER)) {
					add(cur, t, base + 6.0, MoveType.BRIDGE, t.down());
				}
				continue;
			}

			// Blocked: dig through (cardinal only, needs a floor).
			if (options.allowBreak() && !diag && terrain.support(t.down())) {
				double c = terrain.breakCost(t) + terrain.breakCost(t.up());
				if (terrain.kind(t) == Terrain.DANGER || terrain.kind(t.up()) == Terrain.DANGER) {
					c = Double.POSITIVE_INFINITY;
				}
				if (c < Double.POSITIVE_INFINITY) {
					add(cur, t, base + c, MoveType.BREAK, null);
				}
			}
		}

		// Vertical swimming.
		if (inWater) {
			BlockPos up = p.up();
			if (terrain.standable(up) || (terrain.isWater(up) && inRange(up))) {
				add(cur, up, 1.5, MoveType.SWIM, null);
			}
			BlockPos down = p.down();
			if (terrain.isWater(down)) {
				add(cur, down, 1.5, MoveType.SWIM, null);
			}
		}
	}

	private boolean inRange(BlockPos t) {
		int r = options.maxRange();
		return Math.abs(t.getX() - start.getX()) <= r && Math.abs(t.getZ() - start.getZ()) <= r && Math.abs(t.getY() - start.getY()) <= r;
	}

	private void add(Node from, BlockPos pos, double cost, MoveType type, @Nullable BlockPos aux) {
		long key = pos.asLong();
		if (avoid.contains(key)) {
			return;
		}
		double g = from.g + cost;
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(pos.toImmutable(), g, goal.heuristic(pos));
			nodes.put(key, n);
		} else if (n.closed || g >= n.g - 1e-6) {
			return;
		} else {
			n.g = g;
			n.f = g + n.h * HEURISTIC_WEIGHT;
		}
		n.parent = from;
		n.type = type;
		n.aux = aux == null ? null : aux.toImmutable();
		open.add(n); // duplicates are skipped later via the closed flag
	}
}

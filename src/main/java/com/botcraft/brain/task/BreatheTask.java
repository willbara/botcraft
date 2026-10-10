package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Running out of air: drop everything and get to air. Finds the nearest air the water actually
 * leads to (a breadth-first search through the water, so never air behind a wall or above a
 * solid ceiling) and swims that route itself, block by block: up, down, along. No pathfinding
 * (that's what gets lost in flooded tunnels). With no air within reach it swims straight up.
 * Done once the head is out and the air bar is back.
 */
public class BreatheTask extends Task {
	/** How far (in swimming distance) to look for air. */
	private static final int RANGE = 32;
	private static final int MAX_SEARCH = 12000;

	/** The way to the air: water blocks (where the head goes), ending in the air block. */
	private List<BlockPos> route = List.of();
	private int next;
	private int outTicks;
	private int stuckTicks;
	private double lastDist = Double.MAX_VALUE;

	public BreatheTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "breathe";
	}

	@Override
	public String describe() {
		return "coming up for air";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 30;
	}

	@Override
	protected void onStart(boolean resumed) {
		c.nav().stop();
		plan();
	}

	private void plan() {
		CompanionPlayer p = c.player();
		route = routeToAir(p);
		next = 0;
		stuckTicks = 0;
		lastDist = Double.MAX_VALUE;
		AiLog.log(c.name(), "out of air ({}), {}", p.getAir(),
			route.isEmpty() ? "no air in reach, straight up" : "swimming " + route.size() + " blocks to air at " + route.get(route.size() - 1).toShortString());
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (!p.isSubmergedInWater()) {
			// Head out: hold position a moment while the air bar refills.
			if (++outTicks > 20 && p.getAir() >= p.getMaxAir() - 20) {
				return Status.SUCCESS;
			}
			c.actions().holdJump(); // stay afloat
			return Status.RUNNING;
		}
		outTicks = 0;
		if (route.isEmpty()) {
			c.actions().holdJump();
			if (ticks() % 40 == 0) {
				plan();
			}
			return Status.RUNNING;
		}
		// Steer the head along the route: the waypoint is where the head should be next.
		BlockPos head = BlockPos.ofFloored(p.getEyePos());
		while (next < route.size() - 1 && (head.equals(route.get(next)) || reached(p, route.get(next)))) {
			next++;
		}
		BlockPos wp = route.get(next);
		Vec3d target = Vec3d.ofCenter(wp);
		double dy = target.y - p.getEyeY();
		c.actions().moveTo(new Vec3d(target.x, p.getY(), target.z));
		c.actions().sprintSwim(true); // sprint-swimming is much faster
		if (dy > -0.3) {
			c.actions().holdJump();
		} else if (dy < -0.8) {
			p.swimDown();
		}
		// Not getting closer (a current, a corner): plan again from here.
		double dist = p.getEyePos().squaredDistanceTo(target);
		if (dist < lastDist - 0.01) {
			lastDist = dist;
			stuckTicks = 0;
		} else if (++stuckTicks > 30) {
			plan();
		}
		return Status.RUNNING;
	}

	private static boolean reached(CompanionPlayer p, BlockPos wp) {
		Vec3d eye = p.getEyePos();
		return Math.abs(eye.x - (wp.getX() + 0.5)) < 0.6 && Math.abs(eye.z - (wp.getZ() + 0.5)) < 0.6 && Math.abs(eye.y - (wp.getY() + 0.5)) < 0.9;
	}

	/**
	 * Breadth-first from the head through water to the nearest air block, by swimming distance.
	 * Upward moves are tried first, so of equally near ways out the one going up wins. Empty if
	 * there's no air within {@link #RANGE}.
	 */
	static List<BlockPos> routeToAir(CompanionPlayer p) {
		var world = p.getServerWorld();
		BlockPos start = BlockPos.ofFloored(p.getEyePos());
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		queue.add(start);
		parent.put(start, start);
		Direction[] order = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
		while (!queue.isEmpty() && parent.size() < MAX_SEARCH) {
			BlockPos cur = queue.poll();
			for (Direction d : order) {
				BlockPos n = cur.offset(d);
				if (n.getSquaredDistance(start) > RANGE * RANGE || parent.containsKey(n)) {
					continue;
				}
				var state = world.getBlockState(n);
				if (state.isAir()) {
					parent.put(n, cur);
					return trace(parent, start, n);
				}
				if (state.getFluidState().isIn(FluidTags.WATER) && state.getCollisionShape(world, n).isEmpty()) {
					parent.put(n, cur);
					queue.add(n);
				}
			}
		}
		return List.of();
	}

	private static List<BlockPos> trace(Map<BlockPos, BlockPos> parent, BlockPos start, BlockPos end) {
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos at = end; !at.equals(start); at = parent.get(at)) {
			out.add(at);
		}
		java.util.Collections.reverse(out);
		return out;
	}
}

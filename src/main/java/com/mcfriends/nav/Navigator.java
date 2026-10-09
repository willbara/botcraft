package com.mcfriends.nav;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Plans (time-sliced) and follows a path to a {@link Goal}. Tasks call {@link #moveTo} every
 * tick or whenever their goal changes and read {@link #state()}.
 *
 * <p>Stuck handling: if we make no progress toward the current step for ~1.5 s we first try
 * a jump + random strafe, then re-plan, and give up (FAILED) after repeated failures or when
 * the path's time limit runs out. Positions where we got stuck are avoided by re-plans.
 */
public class Navigator {
	public enum State { IDLE, SEARCHING, MOVING, ARRIVED, FAILED }

	private static final int NO_PROGRESS_TICKS = 30;
	private static final int MAX_STUCK = 4;
	private static final int SEARCH_RANGE = 96;

	private final Companion companion;
	private final ActionController actions;
	private final Random random = new Random();

	private State state = State.IDLE;
	@Nullable
	private Goal goal;
	@Nullable
	private PathFinder finder;
	@Nullable
	private List<PathStep> path;
	private boolean partial;
	private int index;
	private boolean sprint;
	private boolean allowBridge = true;
	private String failReason = "";

	private double bestDist;
	private int noProgress;
	private int stuckCount;
	private int recoveryTicks;
	private Vec3d recoveryDir = Vec3d.ZERO;
	private int pathTicks;
	private int pathTimeLimit;
	private int doorWait;
	private final LongOpenHashSet avoid = new LongOpenHashSet();
	private final List<BlockPos> openedDoors = new ArrayList<>();

	public Navigator(Companion companion, ActionController actions) {
		this.companion = companion;
		this.actions = actions;
	}

	public State state() {
		return state;
	}

	public String failReason() {
		return failReason;
	}

	@Nullable
	public Goal goal() {
		return goal;
	}

	public boolean isBusy() {
		return state == State.SEARCHING || state == State.MOVING;
	}

	/** Start (or keep) navigating to the goal. Re-issuing an equal goal keeps the current path. */
	public void moveTo(Goal newGoal, boolean sprint) {
		this.sprint = sprint;
		if (newGoal.equals(goal) && (isBusy() || state == State.ARRIVED)) {
			return;
		}
		this.goal = newGoal;
		this.stuckCount = 0;
		this.avoid.clear();
		this.allowBridge = CompanionConfig.get().allowBridging;
		plan();
	}

	public void stop() {
		state = State.IDLE;
		goal = null;
		finder = null;
		path = null;
		actions.abortMining();
	}

	private void plan() {
		CompanionPlayer p = companion.player();
		if (p == null || goal == null) {
			return;
		}
		BlockPos start = feet(p);
		Terrain terrain = new Terrain(p.getServerWorld());
		// If we're standing somewhere odd (half in a block, mid-air) start from the nearest standable spot.
		if (!terrain.standable(start) && terrain.standable(start.up())) {
			start = start.up();
		}
		boolean canBridge = allowBridge && Inventories.count(p.getInventory(), Inventories::isThrowaway) > 0;
		PathFinder.Options opts = new PathFinder.Options(true, canBridge, CompanionConfig.get().maxPathNodes, SEARCH_RANGE);
		finder = new PathFinder(terrain, start, goal, opts, avoid);
		path = null;
		state = State.SEARCHING;
	}

	/** Block the feet are in, robust to slabs/carpets (see PathStep#floorY). */
	public static BlockPos feet(CompanionPlayer p) {
		return BlockPos.ofFloored(p.getX(), p.getY() + 0.95, p.getZ());
	}

	public void tick() {
		CompanionPlayer p = companion.player();
		if (p == null) {
			return;
		}
		closeDoorsBehind(p);
		switch (state) {
			case SEARCHING -> tickSearch(p);
			case MOVING -> tickFollow(p);
			default -> {
			}
		}
	}

	private void tickSearch(CompanionPlayer p) {
		if (finder == null) {
			state = State.FAILED;
			return;
		}
		PathFinder.Status s = finder.step(CompanionConfig.get().pathNodesPerTick);
		switch (s) {
			case FOUND, PARTIAL -> {
				path = finder.result();
				partial = s == PathFinder.Status.PARTIAL;
				AiLog.log(companion.name(), "path {} with {} steps ({} nodes expanded)", partial ? "partial" : "found",
					path.size(), finder.expanded());
				finder = null;
				if (path.isEmpty()) {
					state = goal != null && goal.isGoal(feet(p)) ? State.ARRIVED : State.FAILED;
					failReason = "empty path";
					return;
				}
				index = 0;
				pathTicks = 0;
				pathTimeLimit = 100 + path.size() * 25;
				resetProgress();
				state = State.MOVING;
			}
			case FAILED -> {
				AiLog.log(companion.name(), "no path to goal ({} nodes expanded)", finder.expanded());
				finder = null;
				failReason = "no path";
				state = State.FAILED;
			}
			default -> {
			}
		}
	}

	private void resetProgress() {
		bestDist = Double.MAX_VALUE;
		noProgress = 0;
	}

	private void tickFollow(CompanionPlayer p) {
		if (path == null || goal == null) {
			state = State.IDLE;
			return;
		}
		BlockPos here = feet(p);
		if (goal.isGoal(here) && p.isOnGround() || goal.isGoal(here) && p.isTouchingWater()) {
			state = State.ARRIVED;
			return;
		}
		if (++pathTicks > pathTimeLimit) {
			stuck(p, "path timeout");
			return;
		}

		// Skip ahead if we're already at (or past) upcoming steps.
		for (int i = Math.min(path.size() - 1, index + 4); i >= index; i--) {
			if (isAt(p, path.get(i))) {
				index = i + 1;
				resetProgress();
				break;
			}
		}
		if (index >= path.size()) {
			if (partial) {
				AiLog.log(companion.name(), "end of partial path, replanning");
				plan();
			} else {
				state = goal.isGoal(here) ? State.ARRIVED : State.FAILED;
				failReason = "path ended short of goal";
			}
			return;
		}

		PathStep step = path.get(index);
		if (recoveryTicks > 0) {
			recoveryTicks--;
			actions.moveTo(p.getPos().add(recoveryDir));
			actions.jump();
			return;
		}

		if (handleObstacles(p, step)) {
			return; // busy mining / placing / opening
		}

		Vec3d target = step.center();
		actions.moveTo(target);
		int remaining = path.size() - index;
		actions.sprint(sprint && remaining > 4 && step.type() != MoveType.BRIDGE);

		double rise = step.floorY() - p.getY();
		double hd = Math.sqrt(sq(target.x - p.getX()) + sq(target.z - p.getZ()));
		if (p.isTouchingWater()) {
			if (rise > -0.3 || p.isSubmergedInWater()) {
				actions.holdJump();
			}
		} else if (p.isOnGround() && ((rise > 0.6 && hd < 1.6) || (p.horizontalCollision && rise > -0.5))) {
			actions.jump();
		}

		// Progress / stuck detection.
		double dist = hd + Math.abs(rise) * 0.5;
		if (dist < bestDist - 0.1) {
			bestDist = dist;
			noProgress = 0;
		} else if (++noProgress > NO_PROGRESS_TICKS) {
			stuck(p, "no progress toward " + step.pos().toShortString());
		}
	}

	private boolean isAt(CompanionPlayer p, PathStep s) {
		double dx = p.getX() - (s.pos().getX() + 0.5);
		double dz = p.getZ() - (s.pos().getZ() + 0.5);
		double dy = p.getY() - s.floorY();
		return dx * dx + dz * dz < 0.45 * 0.45 && dy > -0.6 && dy < 0.9;
	}

	/** Doors, blocks to break and gaps to bridge. Returns true while busy with them. */
	private boolean handleObstacles(CompanionPlayer p, PathStep step) {
		var world = p.getServerWorld();
		// Open wooden doors / gates in the way (feet or head level).
		for (BlockPos pos : new BlockPos[]{step.pos(), step.pos().up()}) {
			BlockState s = world.getBlockState(pos);
			if ((s.getBlock() instanceof DoorBlock && DoorBlock.canOpenByHand(s)) || s.getBlock() instanceof FenceGateBlock) {
				if (!s.get(Properties.OPEN)) {
					if (doorWait > 0) {
						doorWait--;
						return true;
					}
					if (actions.canReachBlock(pos)) {
						actions.lookAt(Vec3d.ofCenter(pos));
						if (actions.useBlock(pos)) {
							BlockPos lower = s.getBlock() instanceof DoorBlock && s.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? pos.down() : pos;
							if (!openedDoors.contains(lower)) {
								openedDoors.add(lower);
							}
							doorWait = 4; // brief pause at the door, like a player would
							AiLog.log(companion.name(), "opened door at {}", pos.toShortString());
						}
						return true;
					}
				}
				break;
			}
		}

		if (step.type() == MoveType.BREAK) {
			for (BlockPos pos : new BlockPos[]{step.pos().up(), step.pos()}) {
				BlockState s = world.getBlockState(pos);
				if (s.getCollisionShape(world, pos).isEmpty()) {
					continue;
				}
				if (!BreakPolicy.canBreakForNav(world, pos, s)) {
					avoid.add(step.pos().asLong());
					stuck(p, "may not break " + s.getBlock().getName().getString());
					return true;
				}
				if (!actions.canReachBlock(pos)) {
					actions.moveTo(Vec3d.ofBottomCenter(pos));
					return true;
				}
				ActionController.MineResult r = actions.mineBlock(pos);
				if (r == ActionController.MineResult.FAILED) {
					avoid.add(step.pos().asLong());
					stuck(p, "could not mine " + pos.toShortString());
				}
				resetProgress();
				return true;
			}
		}

		if (step.type() == MoveType.BRIDGE && step.aux() != null) {
			BlockPos under = step.aux();
			if (world.getBlockState(under).isReplaceable()) {
				// Creep to the edge while sneaking (sneaking stops us walking off), look down, place.
				actions.sneak(true);
				Vec3d edge = Vec3d.ofBottomCenter(step.pos()).add(p.getPos()).multiply(0.5);
				actions.moveTo(edge, 0.6);
				actions.lookAt(Vec3d.ofCenter(under));
				if (actions.canReachBlock(under) && p.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(step.pos())) < 1.6 * 1.6) {
					if (!actions.placeBlock(under, Inventories::isThrowaway)) {
						if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0) {
							allowBridge = false;
						}
						stuck(p, "could not place bridge block");
					}
				}
				resetProgress();
				return true;
			}
			actions.sneak(false);
		}
		return false;
	}

	private void closeDoorsBehind(CompanionPlayer p) {
		if (openedDoors.isEmpty()) {
			return;
		}
		Iterator<BlockPos> it = openedDoors.iterator();
		while (it.hasNext()) {
			BlockPos door = it.next();
			double d = p.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(door));
			if (d > 6 * 6) {
				it.remove(); // too far to bother
				continue;
			}
			BlockState s = p.getWorld().getBlockState(door);
			if (!s.contains(Properties.OPEN) || !s.get(Properties.OPEN)) {
				it.remove();
				continue;
			}
			// Close it once we're clear of the doorway (and not about to walk back through).
			boolean doorAhead = path != null && index < path.size() && path.subList(index, Math.min(path.size(), index + 3)).stream()
				.anyMatch(st -> st.pos().equals(door) || st.pos().equals(door.up()));
			if (d > 2.2 * 2.2 && !doorAhead && actions.canReachBlock(door)) {
				actions.useBlock(door);
				it.remove();
				AiLog.log(companion.name(), "closed door at {}", door.toShortString());
				return;
			}
		}
	}

	private void stuck(CompanionPlayer p, String why) {
		stuckCount++;
		AiLog.log(companion.name(), "stuck ({}/{}): {}", stuckCount, MAX_STUCK, why);
		actions.abortMining();
		actions.sneak(false);
		resetProgress();
		if (stuckCount >= MAX_STUCK) {
			failReason = why;
			state = State.FAILED;
			return;
		}
		if (stuckCount == 1 && !why.startsWith("may not") && !why.startsWith("could not")) {
			// Wiggle: jump and strafe a little in a random direction, then carry on.
			float angle = random.nextFloat() * MathHelper.TAU;
			recoveryDir = new Vec3d(MathHelper.cos(angle), 0, MathHelper.sin(angle));
			recoveryTicks = 8;
			return;
		}
		if (path != null && index < path.size()) {
			avoid.add(path.get(index).pos().asLong());
		}
		plan();
	}

	private static double sq(double v) {
		return v * v;
	}
}

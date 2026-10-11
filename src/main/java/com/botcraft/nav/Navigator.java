package com.botcraft.nav;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import com.botcraft.util.Experiments;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.server.world.ServerWorld;
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
 * <p>Executes every {@link MoveType}: walking (with string-pulling so it cuts straight across
 * open ground instead of zig-zagging node to node), jumps, falls, swimming, doors, mining
 * blocks in the way, bridging, pillaring, ladder climbing and parkour jumps.
 *
 * <p><b>Escape mode:</b> if no (good) path is found, e.g. the classic "dug myself into a hole",
 * the search is retried allowing us to dig through natural terrain and pillar up with
 * throwaway blocks, like a player would. Built blocks are still never broken.
 *
 * <p>Stuck handling: no progress for ~1.5 s -> wiggle (jump + strafe) -> re-plan avoiding the
 * spot -> give up (FAILED) after repeated failures or when the path's time limit runs out.
 */
public class Navigator {
	public enum State { IDLE, SEARCHING, MOVING, ARRIVED, FAILED }

	private static final int NO_PROGRESS_TICKS = 30;
	private static final int MAX_STUCK = 5;
	private static final int SEARCH_RANGE = 96;
	/**
	 * Baritone's primary timeout: a search still going after this long (or this many nodes)
	 * hands over the best partial path if it gets at least EARLY_PROGRESS blocks closer, and we
	 * walk it while planning on, instead of standing still until the search finishes.
	 */
	private static final int EARLY_TICKS = (int) Experiments.num("earlyTicks", 20);
	private static final int EARLY_NODES = (int) Experiments.num("earlyNodes", 4000);
	private static final double EARLY_PROGRESS = 8.0;

	private final Companion companion;
	private final ActionController actions;
	private final Random random = new Random();

	private State state = State.IDLE;
	@Nullable
	private Goal goal;
	@Nullable
	private PathFinder finder;
	/** Planning on from the end of the partial path we're walking, so there's no stop there. */
	@Nullable
	private PathFinder ahead;
	@Nullable
	private BlockPos aheadFrom;
	@Nullable
	private List<PathStep> path;
	private boolean partial;
	private int index;
	private boolean sprint;
	private boolean allowPlace = true;
	private String failReason = "";
	private boolean digMode;
	private boolean escape;
	/** We started the current block-break (so stop() may abort it; tasks' own mining is left alone). */
	private boolean miningForPath;
	private int breaksThisStep;
	private int breakStepIndex = -1;

	private double bestDist;
	private int noProgress;
	private int stuckCount;
	/** Replans after a full path ended just short of the goal (swimming, sliding off the last step). */
	private int endReplans;
	/** Quick retries after a search that failed almost immediately; when to retry (0 = not waiting). */
	private int quickRetries;
	private long retryAt;
	private int recoveryTicks;
	private Vec3d recoveryDir = Vec3d.ZERO;
	private int pathTicks;
	private int pathTimeLimit;
	/** Ticks on the current step, against what the planner expected it to take. */
	private int stepTicks;
	private int stepIndex = -1;
	/** Replans because the world changed ahead of us, and bridges we couldn't place, since moveTo. */
	private int changedReplans;
	private int bridgeFails;
	private int doorWait;
	private int pillarStage;
	private int placeRefusals;
	/** When the current search started (for the soak report's search latency). */
	private long searchStartedAt;
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

	public boolean escaping() {
		return escape;
	}

	/**
	 * Where the current plan ends (the spot the goal is reached at), once a path is found.
	 * With a composite goal this tells which target the planner picked.
	 */
	@Nullable
	public BlockPos plannedEnd() {
		CompanionPlayer p = companion.player();
		if (state == State.ARRIVED && p != null) {
			return feet(p);
		}
		if (state == State.MOVING && path != null && !path.isEmpty() && !partial) {
			return path.get(path.size() - 1).pos();
		}
		return null;
	}

	/** Where the next {@code n} steps of the path we're walking are (empty when not moving). */
	public List<BlockPos> upcoming(int n) {
		if (state != State.MOVING || path == null) {
			return List.of();
		}
		List<BlockPos> out = new ArrayList<>(n);
		for (int i = index; i < Math.min(path.size(), index + n); i++) {
			out.add(path.get(i).pos());
		}
		return out;
	}

	/** Cells other companions stand in or are about to walk through: our searches steer round them. */
	private it.unimi.dsi.fastutil.longs.LongOpenHashSet busyCells(BlockPos from) {
		return Experiments.on("avoidOthers") ? com.botcraft.brain.Blackboard.busyCells(companion, from, 32)
			: new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	}

	/** Start (or keep) navigating to the goal. Re-issuing an equal goal keeps the current path. */
	public void moveTo(Goal newGoal, boolean sprint) {
		this.sprint = sprint;
		if (newGoal.equals(goal) && (isBusy() || state == State.ARRIVED)) {
			return;
		}
		this.goal = newGoal;
		this.stuckCount = 0;
		this.endReplans = 0;
		this.quickRetries = 0;
		this.retryAt = 0;
		this.escape = false;
		this.avoid.clear();
		this.allowPlace = CompanionConfig.get().allowBridging;
		this.changedReplans = 0;
		this.bridgeFails = 0;
		plan();
	}

	/** Allow digging through natural terrain (set by mining jobs, cleared by them on stop). */
	public void setDigMode(boolean dig) {
		if (dig != digMode) {
			digMode = dig;
			if (isBusy() && goal != null) {
				plan();
			}
		}
	}

	public boolean digMode() {
		return digMode;
	}

	public void stop() {
		state = State.IDLE;
		goal = null;
		finder = null;
		ahead = null;
		path = null;
		escape = false;
		actions.sneak(false);
		if (miningForPath) {
			actions.abortMining();
			miningForPath = false;
		}
	}

	private void plan() {
		CompanionPlayer p = companion.player();
		if (p == null || goal == null) {
			return;
		}
		ahead = null;
		BlockPos start = feet(p);
		boolean dig = digMode || escape;
		Terrain terrain = new Terrain(p.getServerWorld(), dig, p.getInventory());
		if (digMode && !escape) {
			terrain.spareBase();
		}
		// If we're standing somewhere odd (half in a block, mid-air) start from the nearest standable spot.
		if (!terrain.standable(start) && terrain.standable(start.up())) {
			start = start.up();
		} else if (!terrain.standable(start) && !p.isTouchingWater() && !p.isClimbing()) {
			// Mid-air (falling off the end of a path, jumping): plan from where we'll land, or the
			// search starts in thin air with almost nowhere to go.
			for (int i = 1; i <= 6; i++) {
				BlockPos below = start.down(i);
				if (terrain.standable(below)) {
					start = below;
					break;
				}
			}
		}
		int blocks = Inventories.count(p.getInventory(), Inventories::isThrowaway);
		boolean place = (allowPlace || escape) && blocks > 0;
		PathFinder.Options opts = new PathFinder.Options(true, place, true, blocks, CompanionConfig.get().maxPathNodes, SEARCH_RANGE, maxFall(p));
		finder = new PathFinder(terrain, start, goal, opts, avoid).startAir(p.getAir(), p.isSubmergedInWater()).busy(busyCells(start))
			.fallBudget(fallBudget(p), p.getHealth());
		searchStartedAt = companion.now();
		path = null;
		state = State.SEARCHING;
	}

	/**
	 * Highest drop a path may take. Normally 3 (no damage). Escaping, like a player stuck on a
	 * pillar would, it may jump down further as long as the landing leaves at least 3 hearts
	 * (fall damage is half a heart per block past 3).
	 */
	private int maxFall(CompanionPlayer p) {
		if (!escape || !Experiments.on("escapeFall")) {
			return 3;
		}
		return Math.max(3, Math.min(12, 3 + (int) Math.floor(p.getHealth() - 6)));
	}

	/** Health points a path may lose to falls: none normally; escaping, all but 3 hearts. */
	private int fallBudget(CompanionPlayer p) {
		return escape && Experiments.on("escapeFall") ? (int) Math.floor(p.getHealth() - 6) : 0;
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

	/** Underground or walled in: a player would dig/tower out rather than give up. */
	private boolean boxedIn(CompanionPlayer p) {
		ServerWorld w = p.getServerWorld();
		BlockPos f = feet(p);
		return !w.isSkyVisible(f.up()) || !w.isSkyVisible(f.up(3));
	}

	private void tickSearch(CompanionPlayer p) {
		if (finder == null && retryAt > 0) {
			if (companion.now() >= retryAt) {
				retryAt = 0;
				plan();
			}
			return;
		}
		if (finder == null) {
			state = State.FAILED;
			return;
		}
		int before = finder.expanded();
		long t0 = System.nanoTime();
		PathFinder.Status s = finder.step(CompanionConfig.get().pathNodesPerTick);
		String outcome = s.name().toLowerCase(java.util.Locale.ROOT);
		if (s == PathFinder.Status.SEARCHING && Experiments.on("earlyPartial")
			&& (companion.now() - searchStartedAt >= EARLY_TICKS || finder.expanded() >= EARLY_NODES) && finder.settle(EARLY_PROGRESS)) {
			s = PathFinder.Status.PARTIAL;
			outcome = "partial, taken early";
		}
		com.botcraft.ops.SoakStats.searchTick(finder.expanded() - before, System.nanoTime() - t0);
		if (s != PathFinder.Status.SEARCHING) {
			com.botcraft.ops.SoakStats.search(outcome, finder.expanded(), companion.now() - searchStartedAt, escape);
		}
		boolean poor = s == PathFinder.Status.FAILED || (s == PathFinder.Status.PARTIAL && finder.progress() < 3);
		if (poor && !escape && !digMode) {
			boolean under = boxedIn(p);
			AiLog.log(companion.name(), "no good path{}: escape mode (dig natural terrain, pillar)", under ? " (underground)" : "");
			escape = true;
			if (under && shaftUp(p)) {
				return;
			}
			plan();
			return;
		}
		if (escape && s == PathFinder.Status.PARTIAL && finder.progress() < 3 && boxedIn(p) && shaftUp(p)) {
			return; // escape search is crawling: just dig straight up
		}
		switch (s) {
			case FOUND, PARTIAL -> {
				path = finder.result();
				partial = s == PathFinder.Status.PARTIAL;
				AiLog.log(companion.name(), "path {} with {} steps ({} nodes expanded){}", partial ? "partial" : "found",
					path.size(), finder.expanded(), escape ? " [escape]" : "");
				finder = null;
				if (path.isEmpty()) {
					state = goal != null && goal.isGoal(feet(p)) ? State.ARRIVED : State.FAILED;
					failReason = "empty path";
					return;
				}
				index = 0;
				stepIndex = -1;
				pathTicks = 0;
				pathTimeLimit = 100;
				for (PathStep st : path) {
					pathTimeLimit += 25 + st.breaks().size() * 60 + (st.place() != null ? 20 : 0);
				}
				pillarStage = 0;
				resetProgress();
				state = State.MOVING;
			}
			case FAILED -> {
				AiLog.log(companion.name(), "no path to goal ({} nodes expanded)", finder.expanded());
				if (finder.expanded() < 16 && quickRetries++ < 3) {
					// Hardly searched at all: probably planned from an awkward spot (mid-step, mid-fall).
					// Forget the places we marked as bad and try again once we've settled.
					finder = null;
					avoid.clear();
					retryAt = companion.now() + 10;
					return;
				}
				finder = null;
				failReason = "no path";
				state = State.FAILED;
				com.botcraft.ops.SoakStats.navFailed("no path");
			}
			default -> {
			}
		}
	}

	/**
	 * Underground with the goal somewhere above: dig straight up like a player would (break the
	 * block over our head, jump, put a block under us), rather than search a huge volume of rock.
	 * The shaft stops before anything risky (fluids, falling blocks, unbreakable or built blocks)
	 * or where the sky opens up, then we plan normally again from there.
	 * @return true if a shaft was started
	 */
	private boolean shaftUp(CompanionPlayer p) {
		BlockPos start = feet(p);
		if (goal == null || goal.heuristic(start.up(8)) > goal.heuristic(start) - 2.5
			|| Inventories.count(p.getInventory(), Inventories::isThrowaway) < 1 || p.isTouchingWater()) {
			return false; // going up doesn't help (or nothing to stand on: blocks can't go down in water)
		}
		ServerWorld world = p.getServerWorld();
		Terrain t = new Terrain(world, true, p.getInventory());
		java.util.List<PathStep> steps = new java.util.ArrayList<>();
		for (int i = 1; i <= 32; i++) {
			BlockPos feetAt = start.up(i);
			BlockPos head = feetAt.up();
			if (world.isSkyVisible(feetAt) || goal.isGoal(feetAt)) {
				break;
			}
			java.util.List<BlockPos> breaks = new java.util.ArrayList<>(1);
			BlockState above = world.getBlockState(head);
			if (!t.passable(head)) {
				if (!BreakPolicy.canBreakForNav(world, head, above, true) || t.isFalling(head.up())) {
					break;
				}
				breaks.add(head.toImmutable());
			}
			if (t.kind(head) == Terrain.LAVA || t.kind(head) == Terrain.WATER || t.kind(head.up()) == Terrain.LAVA) {
				break;
			}
			steps.add(new PathStep(feetAt.toImmutable(), MoveType.PILLAR, feetAt.getY(), feetAt.down().toImmutable(), breaks,
				PathFinder.JUMP + PathFinder.PLACE + (breaks.isEmpty() ? 0 : t.breakCost(head))));
		}
		if (steps.size() < 3) {
			return false;
		}
		AiLog.log(companion.name(), "digging straight up ({} blocks) to get out", steps.size());
		com.botcraft.ops.SoakStats.search("shaft up", 0, 0, true);
		finder = null;
		path = steps;
		partial = true;
		index = 0;
		stepIndex = -1;
		pathTicks = 0;
		pathTimeLimit = 100 + steps.size() * 110;
		pillarStage = 0;
		resetProgress();
		state = State.MOVING;
		return true;
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
		if (goal.isGoal(here) && (p.isOnGround() || p.isTouchingWater() || p.isClimbing())) {
			state = State.ARRIVED;
			actions.sneak(false);
			return;
		}
		if (++pathTicks > pathTimeLimit) {
			stuck(p, "path timeout");
			return;
		}
		if (stepIndex != index) {
			stepIndex = index;
			stepTicks = 0;
		}
		if (index < path.size() && Experiments.on("stepTimeout")) {
			// Like Baritone: a step that takes far longer than planned has gone wrong (mining
			// something we can't reach, pushing against a mob); plan again rather than keep at it.
			PathStep cur = path.get(index);
			if (++stepTicks > cur.cost() * 2 + 100 + (cur.type() == MoveType.PILLAR ? 40 : 0)) {
				stuck(p, "step took too long (" + cur.type() + ")");
				return;
			}
		}
		if (pathTicks % 5 == 0 && changedReplans < 8 && Experiments.on("checkAhead")) {
			String changed = blockedAhead(p);
			if (changed != null) {
				// Someone dug out the floor, placed a block, or lava flowed in: plan again now
				// instead of walking up to it and getting stuck.
				changedReplans++;
				AiLog.log(companion.name(), "path blocked ahead ({}), replanning", changed);
				com.botcraft.ops.SoakStats.navEvent("replanned: " + changed.replaceAll(" at .*", ""));
				plan();
				return;
			}
		}

		// Skip ahead if we're already at (or past) upcoming steps.
		for (int i = Math.min(path.size() - 1, index + 4); i >= index; i--) {
			if (isAt(p, path.get(i), i == path.size() - 1)) {
				index = i + 1;
				pillarStage = 0;
				resetProgress();
				break;
			}
		}
		if (partial && Experiments.on("planAhead")) {
			planAhead(p);
		}
		if (index >= path.size()) {
			if (partial && ahead != null && feet(p).equals(aheadFrom) && ahead.status() != PathFinder.Status.FAILED) {
				// Carry on with what was planned while we walked (finished or not).
				AiLog.log(companion.name(), "end of partial path, carrying on with the path planned ahead ({} nodes so far)", ahead.expanded());
				com.botcraft.ops.SoakStats.navEvent(ahead.status() == PathFinder.Status.SEARCHING
					? "partial path ended, path planned ahead still searching" : "partial path ended, path planned ahead ready");
				finder = ahead;
				ahead = null;
				searchStartedAt = companion.now();
				state = State.SEARCHING;
				tickSearch(p);
			} else if (partial) {
				AiLog.log(companion.name(), "end of partial path, replanning");
				plan();
			} else if (goal.isGoal(here)) {
				state = State.ARRIVED;
			} else if (endReplans++ < 2) {
				PathStep last = path.get(path.size() - 1);
				AiLog.log(companion.name(), "path ended short of the goal at {} (y {}; last step {} {}), replanning",
					here.toShortString(), String.format("%.2f", p.getY()), last.type(), last.pos().toShortString());
				plan();
			} else {
				state = State.FAILED;
				failReason = "path ended short of goal";
				com.botcraft.ops.SoakStats.navFailed("path ended short of goal");
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

		if (handleDoors(p, step) || handleBreaks(p, step) || handleBridge(p, step)) {
			return; // busy opening / mining / placing
		}

		double rise = step.floorY() - p.getY();
		Vec3d target = step.center();
		double hd = Math.sqrt(sq(target.x - p.getX()) + sq(target.z - p.getZ()));
		switch (step.type()) {
			case PILLAR -> {
				pillar(p, step);
				progress(p, Math.abs(rise) + hd, step);
				return;
			}
			case CLIMB -> {
				actions.moveTo(target, 0.6);
				if (rise > 0) {
					actions.holdJump();
				}
				progress(p, Math.abs(rise) + hd, step);
				return;
			}
			case PARKOUR -> {
				actions.moveTo(target);
				actions.sprint(true);
				PathStep prev = index > 0 ? path.get(index - 1) : null;
				Vec3d from = prev != null ? prev.center() : Vec3d.ofBottomCenter(feet(p));
				double run = Math.sqrt(sq(p.getX() - from.x) + sq(p.getZ() - from.z));
				if (p.isOnGround() && run > 0.25) {
					actions.jump(); // take off from the edge of the block
				}
				progress(p, hd, step);
				return;
			}
			default -> {
			}
		}

		// Walking: aim at the farthest of the next few steps reachable in a straight line.
		actions.moveTo(lookahead(p, target));
		int remaining = path.size() - index;
		// In water, sprinting means the swimming pose, which keeps the head under: only do that
		// where the path itself goes under. Across the surface, swim upright so we can breathe.
		boolean underwaterStep = p.getWorld().getFluidState(step.pos().up()).isIn(net.minecraft.registry.tag.FluidTags.WATER);
		boolean run = sprint && remaining > 4 && step.type() != MoveType.BRIDGE;
		if (underwaterStep) {
			actions.sprintSwim(run);
		} else {
			actions.sprint(run);
		}
		if (p.isTouchingWater()) {
			boolean lastStep = index == path.size() - 1;
			if ((rise < -0.3 || (lastStep && step.type() == MoveType.SWIM && rise < -0.05)) && p.getAir() > 80) {
				p.swimDown(); // heading down: dive instead of bobbing at the surface
			} else if (rise > -0.3 || p.isSubmergedInWater()) {
				actions.holdJump();
			}
		} else if (p.isOnGround() && ((rise > 0.6 && hd < 1.6) || (p.horizontalCollision && rise > -0.5))) {
			actions.jump();
		}
		progress(p, hd + Math.abs(rise) * 0.5, step);
	}

	/** Ticks before the end of a partial path to start planning the next stretch (Baritone: planningTickLookahead). */
	private static final double PLAN_AHEAD_TICKS = 150;

	/**
	 * Walking a partial path: once its end is close, start searching on from there, a share of
	 * the node budget each tick, so the next stretch is (mostly) ready when we get there.
	 */
	private void planAhead(CompanionPlayer p) {
		if (path == null || goal == null || path.isEmpty()) {
			return;
		}
		if (ahead != null) {
			if (ahead.status() == PathFinder.Status.SEARCHING) {
				long t0 = System.nanoTime();
				ahead.step(CompanionConfig.get().pathNodesPerTick);
				com.botcraft.ops.SoakStats.searchCpu(System.nanoTime() - t0);
			}
			return;
		}
		double left = 0;
		for (int i = index; i < path.size(); i++) {
			left += path.get(i).cost();
		}
		if (left > PLAN_AHEAD_TICKS) {
			return;
		}
		PathStep last = path.get(path.size() - 1);
		if (goal.isGoal(last.pos())) {
			return;
		}
		int blocks = Inventories.count(p.getInventory(), Inventories::isThrowaway);
		for (int i = index; i < path.size(); i++) {
			if (path.get(i).place() != null) {
				blocks--; // still to be placed on the way there
			}
		}
		blocks = Math.max(0, blocks);
		boolean place = (allowPlace || escape) && blocks > 0;
		Terrain terrain = new Terrain(p.getServerWorld(), digMode || escape, p.getInventory());
		if (digMode && !escape) {
			terrain.spareBase();
		}
		PathFinder.Options opts = new PathFinder.Options(true, place, true, blocks, CompanionConfig.get().maxPathNodes, SEARCH_RANGE, maxFall(p));
		aheadFrom = last.pos();
		ahead = new PathFinder(terrain, aheadFrom, goal, opts, avoid).busy(busyCells(aheadFrom)).fallBudget(fallBudget(p), p.getHealth());
	}

	/**
	 * Re-check the next few steps against the world as it is now. Returns what changed, or null
	 * if they still look walkable. Steps that mine or place are checked for fluids only.
	 */
	@Nullable
	private String blockedAhead(CompanionPlayer p) {
		if (path == null) {
			return null;
		}
		Terrain t = new Terrain(p.getServerWorld(), digMode || escape, p.getInventory());
		for (int i = index + 1; i < Math.min(path.size(), index + 6); i++) {
			PathStep s = path.get(i);
			BlockPos at = s.pos();
			if (t.isLava(at) || t.isLava(at.up())) {
				return "lava at " + at.toShortString();
			}
			if (!s.breaks().isEmpty() || s.place() != null || s.type() == MoveType.PILLAR) {
				for (BlockPos b : s.breaks()) {
					if (t.isLava(b) || t.isWater(b)) {
						return "fluid in a block to mine at " + b.toShortString();
					}
				}
				continue;
			}
			if (!t.passable(at) || !t.passable(at.up())) {
				return "blocked at " + at.toShortString();
			}
			if (!t.standable(at) && s.type() != MoveType.PARKOUR && s.type() != MoveType.FALL) {
				return "floor gone at " + at.toShortString();
			}
		}
		return null;
	}

	private void progress(CompanionPlayer p, double dist, PathStep step) {
		if (dist < bestDist - 0.1) {
			bestDist = dist;
			noProgress = 0;
		} else if (++noProgress > NO_PROGRESS_TICKS + (step.type() == MoveType.PILLAR ? 30 : 0)) {
			stuck(p, "no progress toward " + step.pos().toShortString() + " (" + step.type() + ")");
		}
	}

	/**
	 * String pulling: if the next steps are plain walking on the same level and the straight line
	 * to them crosses only standable ground, head for the farthest one directly.
	 */
	private Vec3d lookahead(CompanionPlayer p, Vec3d target) {
		if (path == null || p.isTouchingWater() || !p.isOnGround()) {
			return target;
		}
		Terrain t = new Terrain(p.getServerWorld());
		Vec3d best = target;
		for (int i = index; i < Math.min(path.size(), index + 5); i++) {
			PathStep s = path.get(i);
			if (s.type() != MoveType.WALK || !s.breaks().isEmpty() || s.place() != null || Math.abs(s.floorY() - p.getY()) > 0.3) {
				break;
			}
			if (i > index && !straightClear(t, p.getPos(), s.center())) {
				break;
			}
			best = s.center();
		}
		return best;
	}

	private static boolean straightClear(Terrain t, Vec3d from, Vec3d to) {
		Vec3d d = to.subtract(from);
		int n = (int) Math.ceil(d.horizontalLength() / 0.3);
		for (int i = 1; i <= n; i++) {
			Vec3d pt = from.add(d.multiply((double) i / n));
			// Check the body's footprint corners, not just the centre line.
			for (double ox : new double[]{-0.3, 0.3}) {
				for (double oz : new double[]{-0.3, 0.3}) {
					BlockPos b = BlockPos.ofFloored(pt.x + ox, from.y + 0.5, pt.z + oz);
					if (!t.standable(b) || t.isWater(b) || t.hazard(b) > 0 || Math.abs(t.floorY(b) - from.y) > 0.3) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private boolean isAt(CompanionPlayer p, PathStep s, boolean last) {
		double dx = p.getX() - (s.pos().getX() + 0.5);
		double dz = p.getZ() - (s.pos().getZ() + 0.5);
		double dy = p.getY() - s.floorY();
		if (s.type() == MoveType.PILLAR || s.type() == MoveType.CLIMB) {
			return dx * dx + dz * dz < 0.5 * 0.5 && dy > -0.2 && dy < 0.9 && (p.isOnGround() || p.isClimbing());
		}
		if (last && (s.type() == MoveType.FALL || s.type() == MoveType.DOWNWARD) && !p.isOnGround() && !p.isTouchingWater() && !p.isClimbing()) {
			return false; // still dropping: the path isn't done until we land
		}
		if (s.type() == MoveType.SWIM && last) {
			// The end of a path under water: floating feet sit well above the node, so only count it
			// once we've sunk into it (matching how feet() judges arrival).
			return dx * dx + dz * dz < 0.45 * 0.45 && dy > -0.6 && dy < 0.05;
		}
		return dx * dx + dz * dz < 0.45 * 0.45 && dy > -0.6 && dy < 0.9;
	}

	// ---- step parts ------------------------------------------------------------------------------

	/** Open wooden doors / gates in the way (feet or head level). */
	private boolean handleDoors(CompanionPlayer p, PathStep step) {
		var world = p.getServerWorld();
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
							BlockPos lower = s.getBlock() instanceof DoorBlock
								&& s.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? pos.down() : pos;
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
		return false;
	}

	/** Mine the blocks this step needs cleared, in order. */
	private boolean handleBreaks(CompanionPlayer p, PathStep step) {
		if (step.breaks().isEmpty()) {
			return false;
		}
		var world = p.getServerWorld();
		if (breakStepIndex != index) {
			breakStepIndex = index;
			breaksThisStep = 0;
		}
		for (BlockPos pos : step.breaks()) {
			BlockState s = world.getBlockState(pos);
			if (s.getCollisionShape(world, pos).isEmpty() && s.getFluidState().isEmpty()) {
				continue;
			}
			if (!s.getFluidState().isEmpty()) {
				avoid.add(step.pos().asLong());
				stuck(p, "fluid in the way at " + pos.toShortString());
				return true;
			}
			if (!BreakPolicy.canBreakForNav(world, pos, s, digMode || escape)) {
				avoid.add(step.pos().asLong());
				stuck(p, "may not break " + s.getBlock().getName().getString());
				return true;
			}
			if (!actions.canReachBlock(pos)) {
				BlockPos block = actions.obstruction(pos);
				if (block != null && BreakPolicy.canBreakForNav(world, block, world.getBlockState(block), digMode || escape)) {
					actions.mineBlock(block); // something else hides it: dig that first
					miningForPath = true;
				} else {
					actions.moveTo(Vec3d.ofBottomCenter(step.pos()), 0.5);
				}
				return true;
			}
			ActionController.MineResult r = actions.mineBlock(pos);
			miningForPath = r == ActionController.MineResult.IN_PROGRESS;
			if (r == ActionController.MineResult.DONE && ++breaksThisStep > 12) {
				avoid.add(step.pos().asLong());
				stuck(p, "blocks keep falling in at " + pos.toShortString()); // e.g. a gravel column
				return true;
			}
			if (r == ActionController.MineResult.FAILED) {
				avoid.add(step.pos().asLong());
				stuck(p, "could not mine " + pos.toShortString());
			}
			resetProgress();
			return true;
		}
		return false;
	}

	/** Bridge: creep to the edge sneaking, look down, place the block under the next step. */
	private boolean handleBridge(CompanionPlayer p, PathStep step) {
		if (step.type() != MoveType.BRIDGE || step.place() == null) {
			return false;
		}
		var world = p.getServerWorld();
		BlockPos under = step.place();
		if (!world.getBlockState(under).isReplaceable()) {
			actions.sneak(false);
			return false;
		}
		actions.sneak(true);
		Vec3d edge = Vec3d.ofBottomCenter(step.pos()).add(p.getPos()).multiply(0.5);
		actions.moveTo(edge, 0.6);
		actions.lookAt(Vec3d.ofCenter(under));
		if (someoneElseIn(p, under) || someoneElseIn(p, step.pos())) {
			avoid.add(step.pos().asLong());
			stuck(p, "someone is in the way at " + under.toShortString());
			return true;
		}
		if (actions.canReachBlock(under) && p.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(step.pos())) < 1.6 * 1.6) {
			if (actions.placeBlock(under, Inventories::isThrowaway)) {
				BreakPolicy.recordPlaced(world, under);
			} else {
				// Twice on one trip is enough: something about where we stand stops us placing (it
				// was once seven minutes of retrying). Find a way round without bridges.
				if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0
					|| (++bridgeFails >= 2 && Experiments.on("bridgeGiveUp"))) {
					allowPlace = false;
				}
				avoid.add(step.pos().asLong());
				stuck(p, "could not place bridge block (" + actions.lastPlaceProblem() + ")");
			}
		}
		resetProgress();
		return true;
	}

	/** Tower up one block: centre, look down, jump, place under our feet at the top of the jump. */
	private void pillar(CompanionPlayer p, PathStep step) {
		BlockPos place = step.place();
		if (place == null) {
			return;
		}
		Vec3d center = Vec3d.ofBottomCenter(place);
		actions.moveTo(center, 0.3);
		actions.lookAt(p.getPos().add(0, -2, 0));
		if (!p.getWorld().getBlockState(place).isReplaceable()) {
			return; // placed: we're standing on it, isAt() will advance
		}
		if (someoneElseIn(p, place) || someoneElseIn(p, place.up()) || someoneElseIn(p, place.up(2))) {
			// Another companion (or a mob) is in the column: we'd never be able to place. Go around.
			avoid.add(step.pos().asLong());
			stuck(p, "someone is in the way at " + place.toShortString());
			return;
		}
		if (pillarStage == 0) {
			if (p.isOnGround()) {
				actions.jump();
				pillarStage = 1;
			}
		} else if (p.getY() >= place.getY() + 1.02) {
			if (actions.placeBlock(place, Inventories::isThrowaway)) {
				BreakPolicy.recordPlaced(p.getServerWorld(), place);
				pillarStage = 0;
				placeRefusals = 0;
			} else if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0) {
				stuck(p, "out of blocks to pillar with");
			} else if (++placeRefusals > 6) {
				placeRefusals = 0;
				avoid.add(step.pos().asLong());
				stuck(p, "could not place pillar block at " + place.toShortString());
			}
		} else if (p.isOnGround() && p.getY() < place.getY() + 0.5) {
			pillarStage = 0; // landed without placing: jump again
		}
	}

	private static boolean someoneElseIn(CompanionPlayer p, BlockPos cell) {
		return !p.getWorld().getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new net.minecraft.util.math.Box(cell),
			e -> e != p && e.isAlive()).isEmpty();
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
		com.botcraft.ops.SoakStats.stuck(why);
		actions.abortMining();
		miningForPath = false;
		actions.sneak(false);
		pillarStage = 0;
		resetProgress();
		if (stuckCount >= MAX_STUCK) {
			failReason = why;
			state = State.FAILED;
			com.botcraft.ops.SoakStats.navFailed("stuck: " + why);
			return;
		}
		if (stuckCount == 1 && !why.startsWith("may not") && !why.startsWith("could not") && !why.startsWith("out of")
			&& !why.startsWith("someone") && !why.startsWith("path timeout") && !why.startsWith("step took")) {
			// Wiggle: jump and strafe a little in a random direction, then carry on.
			float angle = random.nextFloat() * MathHelper.TAU;
			recoveryDir = new Vec3d(MathHelper.cos(angle), 0, MathHelper.sin(angle));
			recoveryTicks = 8;
			return;
		}
		if (path != null && index < path.size()) {
			avoid.add(path.get(index).pos().asLong());
		}
		if (stuckCount >= 3 && !escape && !digMode) {
			escape = true;
		}
		plan();
	}

	private static double sq(double v) {
		return v * v;
	}
}

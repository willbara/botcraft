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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plans (time-sliced) and follows a path to a {@link Goal}. Tasks call {@link #moveTo} every
 * tick or whenever their goal changes and read {@link #state()}.
 *
 * <p>Follows a path the way Baritone's PathExecutor does: each step knows which cells the body
 * may be in while it runs; if we're back on an earlier step we pick up from there, if we're off
 * the path altogether, or a step takes 100 ticks longer than it was costed, the path is dropped
 * and planned again. Each kind of step has its own rules (when to jump, when to place, when a
 * jump has been missed); nothing is mined that the planner didn't list, and nothing is jumped
 * that the planner didn't call a jump.
 *
 * <p>Searches may dig through natural terrain from the start (the mining time is in the cost,
 * so a path goes round when that's quicker); what may be broken is {@link BreakPolicy}'s
 * business. When a search finds nothing at all there is one last resort: search again allowing
 * a fall that costs health and digging the base's ground, and if that fails too while we're
 * underground with the goal above, dig straight up.
 */
public class Navigator {
	public enum State { IDLE, SEARCHING, MOVING, ARRIVED, FAILED }

	private static final int NO_PROGRESS_TICKS = 30;
	private static final int MAX_STUCK = 5;
	/** A step that takes this much longer than it was costed has gone wrong (Baritone: movementTimeoutTicks). */
	private static final int MOVE_TIMEOUT = 100;
	/** How far from the path we may be and carry on (Baritone: MAX_DIST_FROM_PATH, MAX_MAX_DIST_FROM_PATH, MAX_TICKS_AWAY). */
	private static final double OFF_PATH = 2.0;
	private static final double OFF_PATH_MAX = 3.0;
	private static final int OFF_PATH_TICKS = 200;
	/** Ticks before the end of a partial path to start planning the next stretch (Baritone: planningTickLookahead). */
	private static final double PLAN_AHEAD_TICKS = 150;

	private final Companion companion;
	private final ActionController actions;

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
	/** The cell the path starts from, and the height of its floor (what the first step leaves from). */
	@Nullable
	private BlockPos origin;
	private double originFloor;
	private int index;
	private boolean sprint;
	private boolean allowPlace = true;
	private String failReason = "";
	/** The search found nothing: searching once more allowing a fall that costs health, and the base's ground. */
	private boolean lastResort;
	/** Quick retries after a search that failed almost at once; when to retry (0 = not waiting). */
	private int quickRetries;
	private long retryAt;
	/** We started the current block-break (so stop() may abort it; tasks' own mining is left alone). */
	private boolean miningForPath;
	private int breaksThisStep;
	private int breakStepIndex = -1;
	private int unreachableTicks;

	private double bestDist;
	private int noProgress;
	private int stuckCount;
	/** Replans after a full path ended just short of the goal (swimming, sliding off the last step). */
	private int endReplans;
	/** Ticks on the current step, against what the planner expected it to take. */
	private int stepTicks;
	private int stepIndex = -1;
	private int offPathTicks;
	/** Replans because the world changed ahead of us, and bridges we couldn't place, since moveTo. */
	private int changedReplans;
	private int bridgeFails;
	private int doorWait;
	private int pillarStage;
	private int placeRefusals;
	private int pathTicks;
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

	/** How the last search ended: the outcome word, nodes expanded, and the steps in the path taken (0 if none). */
	public record Plan(String outcome, int nodes, int steps) {
	}

	@Nullable
	private Plan lastPlan;

	@Nullable
	public Plan lastPlan() {
		return lastPlan;
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

	/** The step we're on mines something (for the failsafe: digging out by hand gets nowhere). */
	public boolean digging() {
		return state == State.MOVING && path != null && index < path.size() && !path.get(index).breaks().isEmpty();
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
	private LongOpenHashSet busyCells(BlockPos from) {
		return Experiments.on("avoidOthers") ? com.botcraft.brain.Blackboard.busyCells(companion, from, 32) : new LongOpenHashSet();
	}

	/**
	 * Start (or keep) navigating to the goal. Re-issuing an equal goal keeps the current path, and
	 * so does a new goal the path still serves (see {@link #keepPath}).
	 */
	public void moveTo(Goal newGoal, boolean sprint) {
		this.sprint = sprint;
		if (newGoal.equals(goal) && (isBusy() || state == State.ARRIVED)) {
			return;
		}
		if (keepPath(newGoal)) {
			goal = newGoal;
			ahead = null; // it was planning toward the old goal
			com.botcraft.ops.SoakStats.navEvent("kept path on goal change");
			return;
		}
		favored = state == State.MOVING && path != null && Experiments.on("favorPrevious") ? cells(path, index) : null;
		this.goal = newGoal;
		this.stuckCount = 0;
		this.endReplans = 0;
		this.lastResort = false;
		this.quickRetries = 0;
		this.retryAt = 0;
		this.avoid.clear();
		this.allowPlace = CompanionConfig.get().allowBridging;
		this.changedReplans = 0;
		this.bridgeFails = 0;
		plan();
	}

	/** The rest of the path we were walking, favored by the next search (cleared once it starts). */
	@Nullable
	private LongOpenHashSet favored;

	/**
	 * Like Baritone's goal revalidation: when the goal moves (the owner walked on), carry on with
	 * the path we have if it still ends in the new goal, or, for a partial path, still takes us a
	 * good way closer. Replanning every few blocks stops us each time and can pick a different
	 * way round each time.
	 */
	private boolean keepPath(Goal newGoal) {
		CompanionPlayer p = companion.player();
		if (state != State.MOVING || path == null || index >= path.size() || p == null) {
			return false;
		}
		BlockPos end = path.get(path.size() - 1).pos();
		if (!partial) {
			return newGoal.isGoal(end);
		}
		return newGoal.heuristic(end) < newGoal.heuristic(feet(p)) - 2 * PathFinder.H_PER_BLOCK;
	}

	private static LongOpenHashSet cells(List<PathStep> steps, int from) {
		LongOpenHashSet out = new LongOpenHashSet();
		for (int i = Math.max(0, from); i < steps.size(); i++) {
			out.add(steps.get(i).pos().asLong());
		}
		return out;
	}

	public void stop() {
		state = State.IDLE;
		goal = null;
		finder = null;
		ahead = null;
		path = null;
		actions.sneak(false);
		if (miningForPath) {
			actions.abortMining();
			miningForPath = false;
		}
	}

	private Terrain terrain(CompanionPlayer p) {
		Terrain t = new Terrain(p.getServerWorld(), true, p.getInventory());
		if (!lastResort) {
			t.spareBase().homeRules();
		}
		return t;
	}

	private PathFinder.Options options(CompanionPlayer p, int blocks, boolean planningAhead) {
		CompanionConfig cfg = CompanionConfig.get();
		boolean place = allowPlace && blocks > 0;
		// Planning ahead nobody waits on the result, so it may look further (Baritone's planAhead timeouts).
		// -Dbotcraft.primaryNodes / -Dbotcraft.maxNodes override the config, for bench comparisons.
		int max = (int) Experiments.num("maxNodes", cfg.maxPathNodes);
		int first = (int) Math.min(max, Experiments.num("primaryNodes", cfg.pathPrimaryNodes));
		int primary = planningAhead ? Math.min(max, first * 3) : first;
		return new PathFinder.Options(true, place, true, blocks, primary, max, com.botcraft.perception.Sight.blocks(companion.server), maxFall(p));
	}

	private void plan() {
		CompanionPlayer p = companion.player();
		if (p == null || goal == null) {
			return;
		}
		ahead = null;
		Terrain terrain = terrain(p);
		BlockPos start = pathStart(p, terrain);
		int blocks = Inventories.count(p.getInventory(), Inventories::isThrowaway);
		finder = new PathFinder(terrain, start, goal, options(p, blocks, false), avoid).startAir(p.getAir(), p.isSubmergedInWater())
			.busy(busyCells(start)).fallBudget(fallBudget(p)).sprint(canSprint(p));
		if (favored != null) {
			finder.favor(favored);
			favored = null;
		}
		searchStartedAt = companion.now();
		path = null;
		state = State.SEARCHING;
	}

	/**
	 * Where a search starts (Baritone's pathStart): the feet, unless they're over a drop (we're
	 * on the edge of a block: then that block, if it's close enough to be the one we stand on) or
	 * in mid-air (then where we'll land).
	 */
	private static BlockPos pathStart(CompanionPlayer p, Terrain terrain) {
		BlockPos feet = feet(p);
		if (terrain.standable(feet)) {
			return feet;
		}
		if (p.isOnGround()) {
			BlockPos on = p.getSteppingPos().up();
			if (terrain.standable(on) && Math.abs(on.getX() + 0.5 - p.getX()) <= 0.8 && Math.abs(on.getZ() + 0.5 - p.getZ()) <= 0.8) {
				return on;
			}
		}
		if (terrain.standable(feet.up())) {
			return feet.up(); // half in a block
		}
		if (!p.isTouchingWater() && !p.isClimbing()) {
			for (int i = 1; i <= 6; i++) {
				if (terrain.standable(feet.down(i))) {
					return feet.down(i);
				}
			}
		}
		return feet;
	}

	/** We'll sprint this path (see walk), so the planner may cost flat ground at sprint speed. */
	private boolean canSprint(CompanionPlayer p) {
		return sprint && p.getHungerManager().getFoodLevel() > 6;
	}

	/**
	 * Highest drop a path may take. Normally 3 (no damage). As a last resort, like a player stuck
	 * on a pillar would, it may jump down further as long as the landing leaves at least 3 hearts
	 * (fall damage is half a heart per block past 3).
	 */
	private int maxFall(CompanionPlayer p) {
		if (!lastResort || !Experiments.on("escapeFall")) {
			return 3;
		}
		return Math.max(3, Math.min(12, 3 + (int) Math.floor(p.getHealth() - 6)));
	}

	/** Health points a path may lose to falls: none normally; as a last resort, all but 3 hearts. */
	private int fallBudget(CompanionPlayer p) {
		return lastResort && Experiments.on("escapeFall") ? (int) Math.floor(p.getHealth() - 6) : 0;
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

	/** Underground: rock between us and the sky. */
	private boolean boxedIn(CompanionPlayer p) {
		ServerWorld w = p.getServerWorld();
		BlockPos f = feet(p);
		return !Terrain.openSky(w, f.up()) || !Terrain.openSky(w, f.up(3));
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
		com.botcraft.ops.SoakStats.searchTick(finder.expanded() - before, System.nanoTime() - t0);
		if (s == PathFinder.Status.SEARCHING) {
			return;
		}
		String outcome = s.name().toLowerCase(java.util.Locale.ROOT);
		com.botcraft.ops.SoakStats.search(outcome, finder.expanded(), companion.now() - searchStartedAt, lastResort);
		switch (s) {
			case FOUND, PARTIAL -> {
				path = finder.result();
				partial = s == PathFinder.Status.PARTIAL;
				setOrigin(p, finder.start());
				lastPlan = new Plan(outcome, finder.expanded(), path.size());
				AiLog.log(companion.name(), "path {} with {} steps ({} nodes expanded){}", partial ? "partial" : "found", path.size(), finder.expanded(),
					lastResort ? " [last resort]" : "");
				finder = null;
				if (path.isEmpty()) {
					state = goal != null && goal.isGoal(feet(p)) ? State.ARRIVED : State.FAILED;
					failReason = "empty path";
					return;
				}
				startPath();
			}
			case FAILED -> {
				AiLog.log(companion.name(), "no path to goal ({} nodes expanded)", finder.expanded());
				lastPlan = new Plan("failed", finder.expanded(), 0);
				if (finder.expanded() < 16 && quickRetries++ < 3) {
					// Hardly searched at all: planned from an awkward spot (mid-step, mid-fall, mid-swim).
					// Forget the places marked bad and try again once we've settled (soaks: 63 of 70
					// "no path" results in one run were searches of a single node).
					finder = null;
					avoid.clear();
					retryAt = companion.now() + 10;
					return;
				}
				finder = null;
				if (!lastResort && Experiments.on("lastResort")) {
					lastResort = true;
					AiLog.log(companion.name(), "no path: trying again allowing a fall and the base's ground");
					plan();
					return;
				}
				if (Experiments.on("shaftUp") && boxedIn(p) && shaftUp(p)) {
					return;
				}
				failReason = "no path";
				state = State.FAILED;
				com.botcraft.ops.SoakStats.navFailed("no path");
			}
			default -> {
			}
		}
	}

	private void setOrigin(CompanionPlayer p, BlockPos start) {
		origin = start.toImmutable();
		originFloor = new Terrain(p.getServerWorld()).floorY(origin);
	}

	/** The cell a step leaves from: the previous step's, or the path's start for the first. */
	private BlockPos src(@Nullable PathStep prev, CompanionPlayer p) {
		return prev != null ? prev.pos() : origin != null ? origin : feet(p);
	}

	/**
	 * Dropped below the cell this step leaves from (Baritone's "playerFeet.y < src.y", against the
	 * floor's exact height so slabs and paths count right): the jump or step up was missed.
	 */
	private boolean belowSrc(CompanionPlayer p, @Nullable PathStep prev) {
		if (p.isTouchingWater()) {
			return false; // a swimmer floats about half a block under its water cell
		}
		double floor = prev != null ? prev.floorY() : origin != null ? originFloor : p.getY();
		return p.getY() < floor - 0.5;
	}

	private void startPath() {
		index = 0;
		stepIndex = -1;
		pathTicks = 0;
		pillarStage = 0;
		offPathTicks = 0;
		unreachableTicks = 0;
		resetProgress();
		state = State.MOVING;
	}

	/**
	 * Underground with the goal somewhere above and no path found: dig straight up like a player
	 * would (break the block over our head, jump, put a block under us). The shaft stops before
	 * anything risky (fluids, falling blocks, unbreakable or built blocks) or where the sky opens
	 * up, then we plan normally again from there.
	 * @return true if a shaft was started
	 */
	private boolean shaftUp(CompanionPlayer p) {
		BlockPos start = feet(p);
		if (goal == null || goal.heuristic(start.up(8)) > goal.heuristic(start) - 2.5 * PathFinder.H_PER_BLOCK
			|| Inventories.count(p.getInventory(), Inventories::isThrowaway) < 1 || p.isTouchingWater()) {
			return false; // going up doesn't help (or nothing to stand on: blocks can't go down in water)
		}
		ServerWorld world = p.getServerWorld();
		Terrain t = new Terrain(world, true, p.getInventory());
		List<PathStep> steps = new ArrayList<>();
		for (int i = 1; i <= 32; i++) {
			BlockPos feetAt = start.up(i);
			BlockPos head = feetAt.up();
			if (Terrain.openSky(world, feetAt) || goal.isGoal(feetAt)) {
				break;
			}
			List<BlockPos> breaks = new ArrayList<>(1);
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
				PathFinder.JUMP_ONE_BLOCK + PathFinder.JUMP_PENALTY + PathFinder.PLACE + (breaks.isEmpty() ? 0 : t.breakCost(head))));
		}
		if (steps.size() < 3) {
			return false;
		}
		AiLog.log(companion.name(), "digging straight up ({} blocks) to get out", steps.size());
		com.botcraft.ops.SoakStats.search("shaft up", 0, 0, true);
		finder = null;
		path = steps;
		partial = true;
		setOrigin(p, start);
		startPath();
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
		pathTicks++;
		// Skip ahead if we're already at (or past) upcoming steps.
		for (int i = Math.min(path.size() - 1, index + 4); i >= index; i--) {
			if (isAt(p, path.get(i), i == path.size() - 1)) {
				index = i + 1;
				pillarStage = 0;
				unreachableTicks = 0;
				resetProgress();
				break;
			}
		}
		if (partial) {
			planAhead(p);
		}
		if (index >= path.size()) {
			endOfPath(p, here);
			return;
		}
		if (stepIndex != index) {
			stepIndex = index;
			stepTicks = 0;
			offPathTicks = 0;
		}
		PathStep step = path.get(index);
		PathStep prev = index > 0 ? path.get(index - 1) : null;
		if (!validFor(step, prev, here)) {
			// Somewhere this step doesn't expect us. Back on an earlier step (fell back, pushed
			// back)? Pick up from there. Otherwise, how far off the path are we?
			for (int i = index - 1; i >= 0; i--) {
				if (path.get(i).pos().equals(here)) {
					index = i + 1;
					resetProgress();
					return;
				}
			}
			double off = distanceFromPath(p);
			if (off > OFF_PATH_MAX) {
				stuck(p, "off the path (" + String.format("%.1f", off) + " blocks)");
				return;
			}
			if (off > OFF_PATH && ++offPathTicks > OFF_PATH_TICKS) {
				stuck(p, "off the path for too long");
				return;
			}
		} else {
			offPathTicks = 0;
		}
		if (++stepTicks > step.cost() + MOVE_TIMEOUT) {
			// Like Baritone: a step that takes far longer than planned has gone wrong (mining
			// something we can't reach, pushing against a mob); plan again rather than keep at it.
			stuck(p, "step took too long (" + step.type() + ")");
			return;
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
		if (handleDoors(p, step) || handleBreaks(p, step, prev) || handleBridge(p, step)) {
			return; // busy opening / mining / placing
		}
		switch (step.type()) {
			case PILLAR -> pillar(p, step, prev);
			case CLIMB -> climb(p, step);
			case PARKOUR -> parkour(p, step, prev);
			case JUMP -> ascend(p, step, prev);
			case FALL, DOWNWARD -> descend(p, step);
			default -> walk(p, step);
		}
	}

	private void endOfPath(CompanionPlayer p, BlockPos here) {
		if (partial && ahead != null && here.equals(aheadFrom) && ahead.status() != PathFinder.Status.FAILED) {
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
			AiLog.log(companion.name(), "path ended short of the goal at {} (y {}; last step {} {}), replanning", here.toShortString(),
				String.format("%.2f", p.getY()), last.type(), last.pos().toShortString());
			plan();
		} else {
			state = State.FAILED;
			failReason = "path ended short of goal";
			com.botcraft.ops.SoakStats.navFailed("path ended short of goal");
		}
	}

	/**
	 * The cells the body may be in while this step runs (Baritone's valid positions): its start
	 * and end, the corners of a diagonal, the column of a fall, the run-up and gap of a jump.
	 */
	private boolean validFor(PathStep step, @Nullable PathStep prev, BlockPos here) {
		BlockPos dest = step.pos();
		if (here.equals(dest) || here.equals(dest.up())) {
			return true;
		}
		if (prev == null) {
			return true; // the first step starts wherever we were
		}
		BlockPos src = prev.pos();
		if (here.equals(src) || here.equals(src.up())) {
			return true;
		}
		int dx = dest.getX() - src.getX();
		int dz = dest.getZ() - src.getZ();
		switch (step.type()) {
			case WALK, SWIM -> {
				if (dx != 0 && dz != 0) { // a diagonal: either corner
					return here.getY() == src.getY() && ((here.getX() == src.getX() + dx && here.getZ() == src.getZ())
						|| (here.getX() == src.getX() && here.getZ() == src.getZ() + dz));
				}
				return here.getY() == dest.getY() && here.getX() == dest.getX() + dx && here.getZ() == dest.getZ() + dz; // overshot a little
			}
			case FALL, DOWNWARD -> {
				return here.getX() == dest.getX() && here.getZ() == dest.getZ() && here.getY() >= dest.getY() && here.getY() <= src.getY() + 1;
			}
			case PARKOUR -> {
				if (here.getY() < src.getY() || here.getY() > src.getY() + 1) {
					return false;
				}
				int n = Math.max(Math.abs(dx), Math.abs(dz));
				for (int i = 0; i <= n; i++) {
					if (here.getX() == src.getX() + Integer.signum(dx) * i && here.getZ() == src.getZ() + Integer.signum(dz) * i) {
						return true;
					}
				}
				return false;
			}
			case JUMP -> {
				// Backing up a step to place the missing block, or a run-up from the step before.
				return here.getY() == src.getY() && here.getX() == src.getX() - dx && here.getZ() == src.getZ() - dz;
			}
			default -> {
				return false;
			}
		}
	}

	/** Distance from the body to the nearest cell of the steps around the current one. */
	private double distanceFromPath(CompanionPlayer p) {
		double best = Double.MAX_VALUE;
		for (int i = Math.max(0, index - 1); i < Math.min(path.size(), index + 4); i++) {
			PathStep s = path.get(i);
			double dx = p.getX() - (s.pos().getX() + 0.5);
			double dy = p.getY() - s.floorY();
			double dz = p.getZ() - (s.pos().getZ() + 0.5);
			best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return best;
	}

	// ---- step kinds -----------------------------------------------------------------------------

	private void walk(CompanionPlayer p, PathStep step) {
		Vec3d target = step.center();
		double rise = step.floorY() - p.getY();
		double hd = flat(p, target);
		// Walking: aim at the farthest of the next few steps reachable in a straight line.
		actions.moveTo(Experiments.on("stringPull") ? lookahead(p, target) : target);
		int remaining = path.size() - index;
		// In water, sprinting means the swimming pose, which keeps the head under: only do that
		// where the path itself goes under. Across the surface, swim upright so we can breathe.
		boolean underwaterStep = p.getWorld().getFluidState(step.pos().up()).isIn(net.minecraft.registry.tag.FluidTags.WATER);
		boolean run = sprint && remaining > 4 && step.type() != MoveType.BRIDGE && step.breaks().isEmpty();
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
		} else if (p.isOnGround() && rise > 0.6 && hd < 1.2) {
			actions.jump(); // below the step we're meant to be on (slid off it): hop back up
		}
		progress(p, hd + Math.abs(rise) * 0.5, step);
	}

	/** Baritone's MovementAscend: jump at once when nothing overhead could bonk us, else when lined up and close. */
	private void ascend(CompanionPlayer p, PathStep step, @Nullable PathStep prev) {
		BlockPos src = src(prev, p);
		if (belowSrc(p, prev)) {
			stuck(p, "fell below the step (" + step.type() + ")" + where(p, step, prev));
			return;
		}
		if (step.place() != null && p.getWorld().getBlockState(step.place()).isReplaceable()) {
			placeStep(p, step, src);
			return;
		}
		Vec3d target = step.center();
		actions.moveTo(target);
		double hd = flat(p, target);
		if (p.isOnGround()) {
			int dx = step.pos().getX() - src.getX();
			int dz = step.pos().getZ() - src.getZ();
			double along = Math.abs(dx) * Math.abs(target.x - p.getX()) + Math.abs(dz) * Math.abs(target.z - p.getZ());
			double side = Math.abs(dz) * Math.abs(target.x - p.getX()) + Math.abs(dx) * Math.abs(target.z - p.getZ());
			double lateral = Math.abs(dx) * p.getVelocity().z + Math.abs(dz) * p.getVelocity().x;
			if (Math.abs(lateral) <= 0.1 && (headRoomClear(p, src) || (along <= 1.2 && side <= 0.2))) {
				actions.jump();
			}
		}
		progress(p, hd + Math.abs(step.floorY() - p.getY()), step);
	}

	/** Nothing beside the cell two above the start that a jump could bonk against. */
	private static boolean headRoomClear(CompanionPlayer p, BlockPos src) {
		Terrain t = new Terrain(p.getServerWorld());
		BlockPos up2 = src.up(2);
		for (Direction d : Direction.Type.HORIZONTAL) {
			if (!t.passable(up2.offset(d))) {
				return false;
			}
		}
		return true;
	}

	/** An ascend onto a missing step: stand at the start cell, place the step, then climb it. */
	private void placeStep(CompanionPlayer p, PathStep step, BlockPos src) {
		BlockPos at = step.place();
		actions.moveTo(Vec3d.ofBottomCenter(src), 0.4);
		actions.lookAt(Vec3d.ofCenter(at));
		if (someoneElseIn(p, at)) {
			avoid.add(step.pos().asLong());
			stuck(p, "someone is in the way at " + at.toShortString());
			return;
		}
		if (actions.canReachBlock(at) && actions.placeBlock(at, Inventories::isThrowaway)) {
			BreakPolicy.recordPlaced(p.getServerWorld(), at);
			placeRefusals = 0;
		} else if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0) {
			stuck(p, "out of blocks to place a step with");
		} else if (++placeRefusals > 20) {
			placeRefusals = 0;
			avoid.add(step.pos().asLong());
			stuck(p, "could not place a step at " + at.toShortString() + " (" + actions.lastPlaceProblem() + ")");
		}
		resetProgress();
	}

	/** A descend or a fall: walk off toward the landing (a downward has had its floor mined already). */
	private void descend(CompanionPlayer p, PathStep step) {
		Vec3d target = step.center();
		// Baritone's safe mode: if the ground past the landing drops away, come in slowly so the
		// momentum doesn't carry us over that edge too.
		double speed = step.type() == MoveType.DOWNWARD ? 0.3 : 1.0;
		PathStep before = index > 0 ? path.get(index - 1) : null;
		if (speed == 1.0 && before != null) {
			BlockPos beyond = step.pos().add(step.pos().getX() - before.pos().getX(), 0, step.pos().getZ() - before.pos().getZ());
			if (Math.abs(beyond.getX() - step.pos().getX()) <= 1 && Math.abs(beyond.getZ() - step.pos().getZ()) <= 1
				&& !new Terrain(p.getServerWorld()).support(beyond.down())) {
				speed = 0.5;
			}
		}
		actions.moveTo(target, speed);
		actions.sprint(false);
		if (p.isTouchingWater() && target.y < p.getY() - 0.3 && p.getAir() > 80) {
			p.swimDown();
		}
		progress(p, flat(p, target) + Math.abs(step.floorY() - p.getY()) * 0.5, step);
	}

	private void climb(CompanionPlayer p, PathStep step) {
		Vec3d target = step.center();
		double rise = step.floorY() - p.getY();
		actions.moveTo(target, 0.6);
		if (rise > 0) {
			actions.holdJump();
		}
		progress(p, Math.abs(rise) + flat(p, target), step);
	}

	/** Baritone's MovementParkour: run at the gap, jump as the body crosses the edge. */
	private void parkour(CompanionPlayer p, PathStep step, @Nullable PathStep prev) {
		BlockPos src = src(prev, p);
		if (belowSrc(p, prev)) {
			stuck(p, "missed the jump" + where(p, step, prev));
			return;
		}
		Vec3d target = step.center();
		int dx = Integer.signum(step.pos().getX() - src.getX());
		int dz = Integer.signum(step.pos().getZ() - src.getZ());
		int dist = Math.max(Math.abs(step.pos().getX() - src.getX()), Math.abs(step.pos().getZ() - src.getZ()));
		boolean up = step.pos().getY() > src.getY();
		actions.moveTo(target);
		actions.sprint(dist >= 3 || up);
		BlockPos here = feet(p);
		boolean overTheEdge = here.getX() == src.getX() + dx && here.getZ() == src.getZ() + dz;
		if (p.isOnGround() && overTheEdge) {
			double fromStart = Math.max(Math.abs(src.getX() + 0.5 - p.getX()), Math.abs(src.getZ() + 0.5 - p.getZ()));
			if (dist != 3 || up || fromStart >= 0.7) {
				actions.jump();
			}
		}
		progress(p, flat(p, target), step);
	}

	/** Where we are against the step, for the log: " at x y z, step from a to b (index i of n)". */
	private String where(CompanionPlayer p, PathStep step, @Nullable PathStep prev) {
		return String.format(" at %.2f %.2f %.2f, step %s from %s to %s (%d of %d)", p.getX(), p.getY(), p.getZ(), step.type(),
			prev == null ? "start " + (origin == null ? "?" : origin.toShortString()) : prev.pos().toShortString() + " " + prev.type(), step.pos().toShortString(), index, path == null ? 0 : path.size());
	}

	private static double flat(CompanionPlayer p, Vec3d target) {
		return Math.sqrt(sq(target.x - p.getX()) + sq(target.z - p.getZ()));
	}

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
		aheadFrom = last.pos();
		ahead = new PathFinder(terrain(p), aheadFrom, goal, options(p, blocks, true), avoid).busy(busyCells(aheadFrom)).fallBudget(fallBudget(p))
			.sprint(canSprint(p));
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
		Terrain t = terrain(p);
		for (int i = index + 1; i < Math.min(path.size(), index + 6); i++) {
			PathStep s = path.get(i);
			BlockPos at = s.pos();
			if (t.isLava(at) || t.isLava(at.up())) {
				return "lava at " + at.toShortString();
			}
			// Like Baritone: a block in the way only matters if it can't be mined (the step mines
			// whatever is in its cells when it runs), and a floor only if the step stands on it.
			for (BlockPos b : body(s, path.get(i - 1), p)) {
				if (!t.passable(b) && t.breakCost(b) == Double.POSITIVE_INFINITY) {
					return "blocked at " + b.toShortString();
				}
			}
			boolean needsFloor = (s.type() == MoveType.WALK || s.type() == MoveType.JUMP) && s.place() == null && !t.isWater(at) && !t.isClimbable(at);
			if (needsFloor && !t.support(at.down())) {
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
			stuck(p, "no progress toward " + step.pos().toShortString() + " (" + step.type() + ")"
				+ String.format(" at %.2f %.2f %.2f%s%s", p.getX(), p.getY(), p.getZ(), p.isSneaking() ? ", sneaking" : "", p.isTouchingWater() ? ", in water" : "")
				+ ahead(p, step));
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
		if (closedDoor(p, s.pos()) || closedDoor(p, s.pos().up())) {
			return false; // pressed against a closed gate is not through it (handleDoors opens it)
		}
		double dx = p.getX() - (s.pos().getX() + 0.5);
		double dz = p.getZ() - (s.pos().getZ() + 0.5);
		double dy = p.getY() - s.floorY();
		if (s.type() == MoveType.PILLAR || s.type() == MoveType.CLIMB) {
			return dx * dx + dz * dz < 0.5 * 0.5 && dy > -0.2 && dy < 0.9 && (p.isOnGround() || p.isClimbing());
		}
		if (s.type() == MoveType.JUMP && s.place() != null && p.getWorld().getBlockState(s.place()).isReplaceable()) {
			return false; // the step isn't there yet: standing in its cell doesn't count
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

	/**
	 * The cells the body passes through on this step (Baritone's positionsToBreak), top first:
	 * whatever is in them when the step runs gets mined, not just what was there when it was planned.
	 */
	private List<BlockPos> body(PathStep step, @Nullable PathStep prev, CompanionPlayer p) {
		BlockPos dest = step.pos();
		BlockPos src = src(prev, p);
		return switch (step.type()) {
			case WALK, SWIM, BRIDGE -> List.of(dest.up(), dest);
			case JUMP -> List.of(dest, src.up(2), dest.up());
			case FALL -> {
				BlockPos top = new BlockPos(dest.getX(), src.getY(), dest.getZ());
				// Baritone's MovementFall: the column from the start height down, including the floor
				// the planner may mine to drop through (leaving it out left companions standing on it).
				yield List.of(top.up(), top, top.down());
			}
			case DOWNWARD -> List.of(dest);
			case PILLAR -> List.of(dest.up());
			default -> List.of();
		};
	}

	/**
	 * Mine the blocks this step needs cleared, in order. Only those: a block that hides one of
	 * them is not dug out in its place (that used to leave holes all over); we move back to the
	 * step's start and wait for the line of sight instead.
	 */
	private boolean handleBreaks(CompanionPlayer p, PathStep step, @Nullable PathStep prev) {
		List<BlockPos> cells = body(step, prev, p);
		if (cells.isEmpty()) {
			return false;
		}
		var world = p.getServerWorld();
		if (breakStepIndex != index) {
			breakStepIndex = index;
			breaksThisStep = 0;
		}
		boolean blocked = false;
		Terrain t = new Terrain(world);
		for (BlockPos pos : cells) {
			BlockState s = world.getBlockState(pos);
			if (t.passable(pos) || t.isLava(pos) || s.getCollisionShape(world, pos).isEmpty()) {
				continue; // open: air, plants, water, ladders, doors (handleDoors), lava (blockedAhead replans)
			}
			if (!BreakPolicy.canBreakForNav(world, pos, s, true)) {
				avoid.add(step.pos().asLong());
				stuck(p, "may not break " + s.getBlock().getName().getString());
				return true;
			}
			if (!actions.canReachBlock(pos)) {
				// Out of sight. Like Baritone, mine what hides it, but only if that is right next to
				// our body (mining whatever the line of sight hit, wherever, left holes everywhere).
				BlockPos in = actions.obstruction(pos);
				BlockPos feet = feet(p);
				if (in != null && Math.abs(in.getX() - feet.getX()) <= 1 && Math.abs(in.getZ() - feet.getZ()) <= 1
					&& in.getY() - feet.getY() >= -1 && in.getY() - feet.getY() <= 2
					&& BreakPolicy.canBreakForNav(world, in, world.getBlockState(in), true)) {
					ActionController.MineResult r = actions.mineBlock(in);
					miningForPath = r == ActionController.MineResult.IN_PROGRESS;
					resetProgress();
					return true;
				}
				blocked = true; // mine one of the others first, if any can be seen
				continue;
			}
			unreachableTicks = 0;
			ActionController.MineResult r = actions.mineBlock(pos);
			miningForPath = r == ActionController.MineResult.IN_PROGRESS;
			if (miningForPath) {
				walkWhileBreaking(p, step);
			}
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
		if (!blocked) {
			return false;
		}
		// None of what's left can be seen from here: back to the step's start and look again. What
		// hides them is not dug out in their place (that used to leave holes all over).
		BlockPos from = src(prev, p);
		actions.moveTo(Vec3d.ofBottomCenter(from), 0.5);
		if (++unreachableTicks > 40) {
			unreachableTicks = 0;
			avoid.add(step.pos().asLong());
			stuck(p, "can't get at the blocks to mine for " + step.pos().toShortString());
		}
		return true;
	}

	/**
	 * Baritone's walkWhileBreaking: on a level step, keep walking up to the block while mining it
	 * (a leaf at head height takes a few ticks) instead of standing still for each one. Not into
	 * anything that hurts, and not under sand or gravel that will drop when it's mined.
	 */
	private void walkWhileBreaking(CompanionPlayer p, PathStep step) {
		if (step.type() != MoveType.WALK || step.place() != null) {
			return;
		}
		Vec3d c = Vec3d.ofBottomCenter(step.pos());
		if (Math.max(Math.abs(p.getX() - c.x), Math.abs(p.getZ() - c.z)) < 0.83) {
			return; // already up against it
		}
		Terrain t = new Terrain(p.getServerWorld());
		BlockPos at = step.pos();
		for (BlockPos b : new BlockPos[]{at, at.up()}) {
			byte k = t.kind(b);
			if (k == Terrain.DANGER || k == Terrain.LAVA || t.isFalling(b.up())) {
				return;
			}
		}
		actions.moveTo(c);
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
				if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0 || ++bridgeFails >= 2) {
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
	private void pillar(CompanionPlayer p, PathStep step, @Nullable PathStep prev) {
		BlockPos place = step.place();
		if (place == null) {
			return;
		}
		if (belowSrc(p, prev)) {
			stuck(p, "fell below the pillar" + where(p, step, prev));
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
		progress(p, Math.abs(step.floorY() - p.getY()) + flat(p, center), step);
	}

	/** What's right in front of the body toward the step (for "no progress" logs): blocks and entities. */
	private static String ahead(CompanionPlayer p, PathStep step) {
		Vec3d to = step.center().subtract(p.getPos());
		Vec3d dir = new Vec3d(to.x, 0, to.z);
		if (dir.lengthSquared() < 1e-4) {
			return "";
		}
		dir = dir.normalize().multiply(0.5);
		BlockPos feet = BlockPos.ofFloored(p.getX() + dir.x, p.getY() + 0.1, p.getZ() + dir.z);
		var w = p.getWorld();
		String blocks = w.getBlockState(feet).getBlock().getTranslationKey() + "/" + w.getBlockState(feet.up()).getBlock().getTranslationKey();
		var near = w.getOtherEntities(p, p.getBoundingBox().offset(dir.x, 0, dir.z)).stream().map(e -> e.getType().getUntranslatedName()).toList();
		return ", ahead " + feet.toShortString() + " " + blocks.replace("block.minecraft.", "") + (near.isEmpty() ? "" : " entities " + near);
	}

	private static boolean closedDoor(CompanionPlayer p, BlockPos pos) {
		BlockState s = p.getWorld().getBlockState(pos);
		return (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock) && s.contains(Properties.OPEN) && !s.get(Properties.OPEN);
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
		if (path != null && index < path.size() && stuckCount >= 2) {
			avoid.add(path.get(index).pos().asLong()); // the second time, plan round the spot
		}
		plan();
	}

	private static double sq(double v) {
		return v * v;
	}
}

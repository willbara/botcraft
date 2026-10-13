package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.BreakPolicy;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.nav.Terrain;
import com.botcraft.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Find trees, walk to them, chop the logs we can reach, pick up the drops; repeat until we
 * have gathered {@code count} logs.
 *
 * <p>With griefing off only "natural" trees are cut: a log column whose top has several
 * non-persistent (tree-grown) leaves around it. Log cabins have no such leaves.
 *
 * <p>Failure handling: each phase has a timeout; a tree we can't reach or chop is blacklisted
 * for 5 minutes and we look for another. The whole job fails after repeated failures or when
 * no trees are within range.
 */
public class GatherLogsTask extends Task {
	private enum Phase { SCAN, SEARCH, APPROACH, CHOP, CLIMB, PILLAR, DESCEND, GET_DIRT, COLLECT }

	private static final int BLACKLIST_TICKS = 20 * 60 * 5;
	private static final int MAX_TREE_FAILURES = 6;

	private final int wanted;
	private int gathered;
	private int lastCount = -1;

	private Phase phase = Phase.SCAN;
	private int phaseTicks;
	@Nullable
	private BlockPos treeBase;
	private final Deque<BlockPos> logs = new ArrayDeque<>();
	private final Map<BlockPos, Integer> blacklist = new HashMap<>();
	private int treeFailures;
	private int emptyScans;
	/** Attempts to step into the trunk of the current tree (then we pillar instead). */
	private int climbTries;
	private final SearchTrips search = new SearchTrips("gather", 48, 5);
	/** Trees seen from afar that we've already set off for (so a wood that turns out no good isn't picked again). */
	private final java.util.Set<BlockPos> triedFar = new java.util.HashSet<>();
	@Nullable
	private ItemEntity collecting;
	private final Pickup pickup = new Pickup();
	private List<BlockPos> treeChoices = List.of();
	@Nullable
	private Goal treeGoal;
	private boolean locked;
	private static final int MAX_PILLAR = 24;
	/** Blocks we placed to climb the current tree (bottom first), dug back down afterwards. */
	private final List<BlockPos> pillar = new ArrayList<>();
	private int pillarStage;
	private int pillarLevel;
	private int dirtTrips;
	private int dirtNeeded;
	private boolean needMoreDirt;
	private final java.util.Set<BlockPos> badDirt = new HashSet<>();
	private int approachRetries;
	@Nullable
	private BlockPos dirtTarget;
	@Nullable
	private BlockPos chopping;

	public GatherLogsTask(Companion c, int count) {
		super(c);
		this.wanted = Math.max(1, count);
	}

	@Override
	public String name() {
		return "gather_logs";
	}

	@Override
	public String describe() {
		return "chopping wood (" + gathered + "/" + wanted + ")";
	}

	public int gathered() {
		return gathered;
	}

	public int wanted() {
		return wanted;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 15;
	}

	@Override
	protected void onStop() {
		com.botcraft.brain.Claims.release(c);
		search.cancel(c);
		super.onStop();
	}

	@Override
	protected void onStart(boolean resumed) {
		// After an interruption: go back to the tree we were on (if any), otherwise look for one.
		if (treeBase != null && p().getWorld().getBlockState(treeBase).isIn(BlockTags.LOGS) && pillar.isEmpty()) {
			locked = true;
			c.nav().moveTo(Goal.reachVisible(p().getWorld(), treeBase, ActionController.BLOCK_REACH - 0.5), false);
			setPhase(Phase.APPROACH);
		} else if (!pillar.isEmpty()) {
			setPhase(Phase.DESCEND);
		} else {
			treeBase = null;
			setPhase(Phase.SCAN);
		}
		lastCount = -1;
	}

	private CompanionPlayer p() {
		return c.player();
	}

	private void setPhase(Phase p) {
		AiLog.log(c.name(), "gather: {} -> {}", phase, p);
		phase = p;
		phaseTicks = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		countProgress(p);
		// Finish the tree we're on before calling it done: no half-chopped floating trees.
		if (gathered >= wanted && treeBase == null) {
			return Status.SUCCESS;
		}
		if (treeFailures >= MAX_TREE_FAILURES) {
			return fail("couldn't get to any trees");
		}
		blacklist.values().removeIf(t -> t < ticks());
		phaseTicks++;
		return switch (phase) {
			case SCAN -> scan(p);
			case SEARCH -> {
				if (!search.tick(c)) {
					setPhase(Phase.SCAN);
				}
				yield Status.RUNNING;
			}
			case APPROACH -> approach(p);
			case CHOP -> chop(p);
			case CLIMB -> climb(p);
			case PILLAR -> pillar(p);
			case DESCEND -> descend(p);
			case GET_DIRT -> getDirt(p);
			case COLLECT -> collect(p);
		};
	}

	/** Logs gained since we started count toward the goal (we don't count logs we already had). */
	private void countProgress(CompanionPlayer p) {
		int now = Inventories.count(p.getInventory(), Inventories::isLog);
		if (lastCount >= 0 && now > lastCount) {
			gathered += now - lastCount;
			c.memory().count("logs_chopped", now - lastCount);
			c.data.job = "gather logs " + Math.max(0, wanted - gathered);
		}
		lastCount = now;
	}

	// ---- phases ---------------------------------------------------------------------------------

	private Status scan(CompanionPlayer p) {
		var scanner = c.perception().blocks();
		if (phaseTicks == 1) {
			scanner.start(s -> s.isIn(BlockTags.LOGS), CompanionConfig.get().treeSearchRadius, 8, 20);
		}
		if (!scanner.tick(ticks())) {
			if (phaseTicks > 200) {
				return fail("tree scan took too long");
			}
			return Status.RUNNING;
		}
		List<BlockPos> trees = pickTrees(p.getServerWorld(), scanner.results(), p.getBlockPos());
		treeBase = trees.isEmpty() ? null : trees.get(0);
		if (treeBase == null) {
			if (++emptyScans < 2) {
				setPhase(Phase.SCAN);
				return Status.RUNNING;
			}
			// None in range: go and look (a remembered wood, the surface, or just somewhere else).
			if (search.exhausted()) {
				return fail(gathered > 0 ? "I ran out of trees around here" : "I looked around but couldn't find any trees");
			}
			emptyScans = 0;
			BlockPos here = p.getBlockPos();
			int radius = CompanionConfig.get().treeSearchRadius;
			BlockPos remembered = null;
			for (var seen : c.memory().sightings("tree", here, c.dimension(), 160)) {
				if (seen.pos().getSquaredDistance(here) > (radius + 8) * (radius + 8)) {
					remembered = seen.pos();
					break;
				}
				c.memory().forgetSighting("tree", seen.pos()); // in range, but gone (chopped)
			}
			boolean underground = SearchTrips.underground(p.getServerWorld(), here);
			if (remembered == null && !underground) {
				// Nothing remembered: look further, as far as we can see (trees across the water,
				// a forest past the hill), before wandering off at random.
				remembered = com.botcraft.perception.Sight.nearestTrees(p.getServerWorld(), here,
					t -> t.getSquaredDistance(here) < radius * radius || triedFar.contains(t));
				if (remembered != null) {
					triedFar.add(remembered);
					AiLog.log(c.name(), "gather: trees in sight at {}", remembered.toShortString());
				}
			}
			search.start(c, underground ? null : remembered);
			setPhase(Phase.SEARCH);
			return Status.RUNNING;
		}
		emptyScans = 0;
		AiLog.log(c.name(), "gather: picked tree at {}", treeBase.toShortString());
		c.memory().sight("tree", treeBase, c.dimension(), p.getWorld().getTime());
		// Baritone-style: offer the few best trees as one goal; whichever the path reaches is the
		// one we commit to (no dithering between equally good trees).
		treeChoices = trees;
		List<Goal> goals = new ArrayList<>();
		for (BlockPos t : trees) {
			goals.add(Goal.reachVisible(p.getWorld(), t, ActionController.BLOCK_REACH - 0.5));
		}
		treeGoal = Goal.anyOf(goals);
		locked = false;
		c.nav().moveTo(treeGoal, p.getBlockPos().getSquaredDistance(treeBase) > 100);
		setPhase(Phase.APPROACH);
		return Status.RUNNING;
	}

	private Status approach(CompanionPlayer p) {
		if (!locked) {
			BlockPos end = c.nav().plannedEnd();
			if (end != null && treeGoal != null) {
				int i = treeGoal.which(end);
				treeBase = treeChoices.get(Math.max(0, i));
				climbTries = 0;
				locked = true;
				CLAIMED.put(treeBase, c.name());
				com.botcraft.brain.Claims.claim(c, treeBase);
				AiLog.log(c.name(), "gather: locked onto tree at {} (of {})", treeBase.toShortString(), treeChoices.size());
			}
		}
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED || (c.actions().canReachBlock(treeBase) && p.isOnGround() && phaseTicks > 5)) {
			c.nav().stop();
			if (logs.isEmpty()) {
				collectTreeLogs(p.getServerWorld());
			}
			approachRetries = 0;
			setPhase(Phase.CHOP);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 60) {
			if (!logs.isEmpty() && approachRetries++ < 3) {
				// Coming back to a tree we've started: try again rather than leave it half done.
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), treeBase, ActionController.BLOCK_REACH - 0.5), false);
				phaseTicks = 0;
				return Status.RUNNING;
			}
			giveUpOnTree("can't reach it (" + c.nav().failReason() + ")");
		}
		return Status.RUNNING;
	}

	private Status chop(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		logs.removeIf(l -> !world.getBlockState(l).isIn(BlockTags.LOGS));
		if (logs.isEmpty()) {
			return finishTree();
		}
		if (!p.isOnGround() && !p.isTouchingWater() && !c.actions().isMining()) {
			return Status.RUNNING; // judge reach once we've landed, not at the top of a jump
		}
		// Any log we can reach from here, lowest first.
		BlockPos next = null;
		for (BlockPos l : logs) {
			if (c.actions().canReachBlock(l)) {
				next = l;
				break;
			}
		}
		if (next == null) {
			if (pillar.isEmpty() && !Navigator.feet(p).equals(treeBase) && canStandIn(world, treeBase) && climbTries++ < 2) {
				// The bottom of the trunk is gone: step into it, the logs above are right overhead.
				c.nav().moveTo(Goal.near(treeBase, 0.3), false);
				setPhase(Phase.CLIMB);
			} else if (highestLog() > p.getEyeY()) {
				setPhase(Phase.PILLAR);
			} else {
				// Out of reach sideways (long branches): leave those.
				AiLog.log(c.name(), "gather: {} logs out of reach, leaving them", logs.size());
				logs.clear();
			}
			return Status.RUNNING;
		}
		if (!next.equals(chopping)) {
			chopping = next;
			phaseTicks = 0;
		}
		ActionController.MineResult r = c.actions().mineBlock(next);
		if (r == ActionController.MineResult.FAILED || phaseTicks > 20 * 15) {
			AiLog.log(c.name(), "gather: skipping log at {} ({})", next.toShortString(), r);
			c.actions().abortMining();
			logs.remove(next);
			phaseTicks = 0;
		}
		return Status.RUNNING;
	}

	private Status finishTree() {
		setPhase(pillar.isEmpty() ? Phase.COLLECT : Phase.DESCEND);
		return Status.RUNNING;
	}

	private double highestLog() {
		double top = Double.NEGATIVE_INFINITY;
		for (BlockPos l : logs) {
			top = Math.max(top, l.getY() + 0.5);
		}
		return top;
	}

	private Status climb(CompanionPlayer p) {
		Navigator.State s = c.nav().state();
		if (Navigator.feet(p).equals(treeBase)) {
			c.nav().stop();
			setPhase(Phase.CHOP);
		} else if (s == Navigator.State.ARRIVED) {
			// "Arrived" next to or on top of the stump rather than in it: pillar up instead
			// (going back to chopping would just send us here again).
			c.nav().stop();
			setPhase(climbTries >= 2 ? Phase.PILLAR : Phase.CHOP);
		} else if (s == Navigator.State.FAILED || phaseTicks > 100) {
			c.nav().stop();
			setPhase(Phase.PILLAR); // can't get into the trunk: build up from here instead
		}
		return Status.RUNNING;
	}

	/**
	 * Tower up under ourselves (jump, place a throwaway block below) until the remaining logs are
	 * in reach, clearing leaves/logs above our head as we go.
	 */
	/** Jumps in a row that ended without a block placed under us. */
	private int pillarFails;

	private Status pillar(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		logs.removeIf(l -> !world.getBlockState(l).isIn(BlockTags.LOGS));
		for (BlockPos l : logs) {
			if (pillarStage == 0 && p.isOnGround() && c.actions().canReachBlock(l)) {
				pillarStage = 0;
				setPhase(Phase.CHOP);
				return Status.RUNNING;
			}
		}
		if (logs.isEmpty() || highestLog() <= p.getEyeY() || pillar.size() >= MAX_PILLAR || phaseTicks > 20 * 60) {
			logs.clear();
			return finishTree();
		}
		if (Inventories.count(p.getInventory(), Inventories::isThrowaway) == 0) {
			if (dirtTrips >= 3) {
				AiLog.log(c.name(), "gather: out of blocks to climb with, leaving the top of this tree");
				logs.clear();
				return finishTree();
			}
			dirtTrips++;
			if (pillar.isEmpty()) {
				startDirtRun(p);
			} else {
				// Up the tower with nothing left to place: climb down, fetch more, come back.
				AiLog.log(c.name(), "gather: out of blocks halfway up, going down for more dirt");
				needMoreDirt = true;
				setPhase(Phase.DESCEND);
			}
			return Status.RUNNING;
		}
		BlockPos feet = Navigator.feet(p);
		BlockPos above = feet.up(2);
		var aboveState = world.getBlockState(above);
		if (!aboveState.getCollisionShape(world, above).isEmpty()) {
			// Clear head room: leaves and this tree's logs only.
			if (aboveState.isIn(BlockTags.LOGS) || BreakPolicy.canBreakForNav(world, above, aboveState)) {
				c.actions().mineBlock(above);
				return Status.RUNNING;
			}
			logs.clear();
			return finishTree();
		}
		// Stay centred on the block and look down, like a player towering up.
		c.actions().moveTo(Vec3d.ofBottomCenter(feet), 0.4);
		c.actions().lookAt(p.getPos().add(0, -2, 0));
		if (pillarStage == 0 && p.isOnGround()) {
			// A flower, fern or sapling where we stand can't be placed over (only grass-like plants
			// can): pick it first, or every jump ends without a block under us.
			var here = world.getBlockState(feet);
			if (!here.isAir() && !here.isReplaceable() && here.getCollisionShape(world, feet).isEmpty() && here.getHardness(world, feet) == 0) {
				c.actions().mineBlock(feet);
				return Status.RUNNING;
			}
		}
		if (pillarStage == 0) {
			if (p.isOnGround()) {
				pillarLevel = feet.getY();
				c.actions().jump();
				pillarStage = 1;
			}
		} else if (p.getY() >= pillarLevel + 1.05) {
			BlockPos spot = new BlockPos(feet.getX(), pillarLevel, feet.getZ());
			if (c.actions().placeBlock(spot, Inventories::isThrowaway)) {
				pillar.add(spot);
				AiLog.log(c.name(), "gather: pillar block {} at {}", pillar.size(), spot.toShortString());
				pillarStage = 0;
				pillarFails = 0;
			} else if (++pillarFails >= 15) { // (a few attempts each jump: about five jumps)
				// Jumping on the spot for a minute helped nobody (a player saw it): say why and move on.
				AiLog.log(c.name(), "gather: can't put a block under me at {} ({}), leaving the top of this tree", spot.toShortString(),
					c.actions().lastPlaceProblem());
				pillarFails = 0;
				logs.clear();
				return finishTree();
			}
		} else if (p.isOnGround() && pillarStage == 1 && p.getY() < pillarLevel + 0.5) {
			pillarStage = 0; // landed without placing: try again
		}
		return Status.RUNNING;
	}

	/** Dig our tower back down (getting the blocks back), one block at a time so falls are short. */
	private Status descend(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		while (!pillar.isEmpty() && world.getBlockState(pillar.get(pillar.size() - 1)).isAir()) {
			pillar.remove(pillar.size() - 1);
		}
		if (pillar.isEmpty() || phaseTicks > 20 * 60) {
			pillar.clear();
			if (needMoreDirt) {
				needMoreDirt = false;
				startDirtRun(p);
			} else {
				setPhase(Phase.COLLECT);
			}
			return Status.RUNNING;
		}
		BlockPos top = pillar.get(pillar.size() - 1);
		c.actions().moveTo(Vec3d.ofBottomCenter(top.up()), 0.4);
		if (c.actions().mineBlock(top) == ActionController.MineResult.FAILED) {
			pillar.remove(pillar.size() - 1); // can't reach it any more; leave it
		}
		return Status.RUNNING;
	}

	private void startDirtRun(CompanionPlayer p) {
		dirtNeeded = (int) Math.min(MAX_PILLAR, Math.ceil(highestLog() - p.getEyeY())) + 2;
		AiLog.log(c.name(), "gather: need {} blocks to climb, digging some dirt", dirtNeeded);
		setPhase(Phase.GET_DIRT);
	}

	/** Dig a few surface dirt/grass blocks nearby to climb with, then go back to the tree. */
	private Status getDirt(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		int have = Inventories.count(p.getInventory(), Inventories::isThrowaway);
		if (have >= dirtNeeded || phaseTicks > 20 * 60) {
			AiLog.log(c.name(), "gather: dirt run done, have {}/{} ({} ticks)", have, dirtNeeded, phaseTicks);
			dirtTarget = null;
			c.nav().moveTo(Goal.reachVisible(world, treeBase, ActionController.BLOCK_REACH - 0.5), false);
			setPhase(have > 0 ? Phase.APPROACH : Phase.COLLECT);
			if (have == 0) {
				logs.clear();
			}
			return Status.RUNNING;
		}
		if (phaseTicks % 100 == 0) {
			AiLog.log(c.name(), "gather: dirt run {} ticks, have {}/{}, target {}, nav {}, at {}", phaseTicks, have, dirtNeeded,
				dirtTarget, c.nav().state(), p.getPos());
		}
		ItemEntity drop = nearestItem(p, s -> Inventories.isThrowaway(s), 5.0);
		if (drop != null) {
			if (!pickup.tick(c, drop)) {
				dirtTarget = null;
			}
			return Status.RUNNING;
		}
		if (dirtTarget == null || !isDiggableDirt(world, dirtTarget)) {
			dirtTarget = findDirt(p);
			if (dirtTarget == null) {
				AiLog.log(c.name(), "gather: no diggable dirt nearby");
				dirtNeeded = 0; // nothing suitable around: carry on with what we have
				return Status.RUNNING;
			}
		}
		if (c.actions().canReachBlock(dirtTarget)) {
			c.nav().stop();
			if (c.actions().mineBlock(dirtTarget) == ActionController.MineResult.FAILED) {
				dirtTarget = null;
			}
		} else if (c.nav().state() == Navigator.State.ARRIVED) {
			// Next to it but can't see it (e.g. we're standing in one of our own holes): pick another.
			badDirt.add(dirtTarget);
			dirtTarget = null;
			c.nav().stop();
		} else {
			c.nav().moveTo(Goal.reachVisible(world, dirtTarget, ActionController.BLOCK_REACH - 0.5), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				c.nav().stop();
				dirtTarget = null;
			}
		}
		return Status.RUNNING;
	}

	/** Natural dirt we may dig: not under the tree, not someone's, not home's ground. */
	private boolean isDiggableDirt(ServerWorld world, BlockPos pos) {
		var colony = com.botcraft.colony.Colony.get();
		return world.getBlockState(pos).isIn(BlockTags.DIRT) && world.getBlockState(pos.up()).isAir()
			&& !(pos.getX() == treeBase.getX() && pos.getZ() == treeBase.getZ()) && !com.botcraft.colony.Builds.recorded(world, pos)
			&& (colony == null || !colony.protectsGround(world, pos));
	}

	/** Exposed natural dirt/grass near us, not the block we stand on and not under the tree. */
	@Nullable
	private BlockPos findDirt(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		BlockPos feet = Navigator.feet(p);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(feet.add(-10, -3, -10), feet.add(10, 2, 10))) {
			if (pos.equals(feet.down()) || badDirt.contains(pos) || !isDiggableDirt(world, pos)) {
				continue;
			}
			double d = pos.getSquaredDistance(feet);
			if (d < bestD && d > 1.5) {
				bestD = d;
				best = pos.toImmutable();
			}
		}
		return best;
	}

	@Nullable
	private ItemEntity nearestItem(CompanionPlayer p, java.util.function.Predicate<net.minecraft.item.ItemStack> which, double r) {
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(r, 3.0, r),
			e -> e.isAlive() && which.test(e.getStack()) && !pickup.ignored(e) && !com.botcraft.brain.Blackboard.claimedByOther(c, e));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}

	private Status collect(CompanionPlayer p) {
		if (collecting == null || !collecting.isAlive()) {
			collecting = nearestDrop(p);
			if (collecting == null) {
				// Tree finished and drops collected.
				blacklist.put(treeBase, ticks() + BLACKLIST_TICKS);
				treeBase = null;
				setPhase(Phase.SCAN);
				return Status.RUNNING;
			}
		}
		if (!pickup.tick(c, collecting)) {
			collecting = null;
		}
		return Status.RUNNING;
	}

	// ---- helpers --------------------------------------------------------------------------------

	private void giveUpOnTree(String why) {
		AiLog.log(c.name(), "gather: giving up on tree at {}: {}", treeBase == null ? "?" : treeBase.toShortString(), why);
		if (treeBase != null) {
			blacklist.put(treeBase, ticks() + BLACKLIST_TICKS);
		}
		treeFailures++;
		treeBase = null;
		logs.clear();
		c.nav().stop();
		setPhase(Phase.SCAN);
	}

	@Nullable
	private ItemEntity nearestDrop(CompanionPlayer p) {
		Box box = new Box(treeBase).expand(7.0, 6.0, 7.0);
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, box,
			e -> e.isAlive() && !e.getStack().isEmpty() && !pickup.ignored(e) && (e.getStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS)
				|| e.getStack().isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS) || e.getStack().getItem() == net.minecraft.item.Items.APPLE));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}

	/** Trees within this of another companion's tree are theirs (veins use 6; trees stand further apart). */
	static final double TREE_SPACING = 8;

	/** The best few trees to choose from (nearest, sized to what we need), best first. */
	private List<BlockPos> pickTrees(ServerWorld world, List<BlockPos> found, BlockPos from) {
		Set<BlockPos> seenBases = new HashSet<>();
		List<BlockPos> bases = new ArrayList<>();
		java.util.Map<BlockPos, Double> score = new java.util.HashMap<>();
		int checked = 0;
		for (BlockPos log : found) {
			if (++checked > 200 || bases.size() >= 10) {
				break;
			}
			BlockPos base = log;
			while (world.getBlockState(base.down()).isIn(BlockTags.LOGS) && from.getY() - base.getY() < 12) {
				base = base.down();
			}
			if (!seenBases.add(base) || blacklist.containsKey(base) || claimedByOther(base)) {
				continue;
			}
			if (!new Terrain(world).support(base.down())) {
				continue; // floating log / branch, not a trunk we can stand next to
			}
			if (!CompanionConfig.get().griefing && !isNaturalTree(world, base)) {
				continue;
			}
			// We always finish a tree we start, so a 50-log giant is a poor choice for a few logs.
			int size = treeSize(world, base);
			int need = Math.max(1, wanted - gathered);
			score.put(base, Math.sqrt(base.getSquaredDistance(from)) + Math.max(0, size - need - 8) * 1.5);
			bases.add(base);
		}
		// Like veins: trees near where someone else is working are left to them, so companions
		// spread out through a wood. Only when every tree is near someone do we share.
		bases = new ArrayList<>(com.botcraft.brain.Claims.spreadOut(c, bases, b -> b, TREE_SPACING));
		bases.sort(Comparator.comparingDouble(score::get));
		return bases.subList(0, Math.min(5, bases.size()));
	}

	/** Trees other companions are working on (so two don't fight over one). */
	private static final java.util.Map<BlockPos, String> CLAIMED = new java.util.concurrent.ConcurrentHashMap<>();

	private boolean claimedByOther(BlockPos base) {
		String who = CLAIMED.get(base);
		if (who == null || who.equals(c.name())) {
			return false;
		}
		var m = com.botcraft.CompanionManager.get();
		var other = m == null ? null : m.byName(who);
		if (other == null || !(other.brain().job() instanceof GatherLogsTask g) || !base.equals(g.treeBase)) {
			CLAIMED.remove(base); // stale claim
			return false;
		}
		return true;
	}

	private static int treeSize(ServerWorld world, BlockPos base) {
		Set<BlockPos> seen = new HashSet<>();
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		q.add(base);
		seen.add(base);
		while (!q.isEmpty() && seen.size() < 64) {
			BlockPos cur = q.poll();
			for (BlockPos n : BlockPos.iterate(cur.add(-1, 0, -1), cur.add(1, 1, 1))) {
				BlockPos im = n.toImmutable();
				if (im.getY() >= base.getY() && !seen.contains(im) && world.getBlockState(im).isIn(BlockTags.LOGS)) {
					seen.add(im);
					q.add(im);
				}
			}
		}
		return seen.size();
	}

	/** A log column topped with tree-grown (non-persistent) leaves. */
	static boolean isNaturalTree(ServerWorld world, BlockPos base) {
		if (com.botcraft.colony.Builds.recorded(world, base)) {
			return false;
		}
		BlockPos top = base;
		for (int i = 0; i < 32 && world.getBlockState(top.up()).isIn(BlockTags.LOGS); i++) {
			top = top.up();
		}
		int natural = 0;
		for (BlockPos pos : BlockPos.iterate(top.add(-3, -3, -3), top.add(3, 3, 3))) {
			BlockState s = world.getBlockState(pos);
			if (s.getBlock() instanceof LeavesBlock && !s.get(LeavesBlock.PERSISTENT)) {
				if (++natural >= 4) {
					return true;
				}
			}
		}
		return false;
	}

	/** Logs of this tree (trunk + branches touching it), lowest first. */
	private void collectTreeLogs(ServerWorld world) {
		logs.clear();
		List<BlockPos> out = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(treeBase);
		seen.add(treeBase);
		while (!queue.isEmpty() && out.size() < 64) {
			BlockPos cur = queue.poll();
			out.add(cur);
			for (BlockPos n : BlockPos.iterate(cur.add(-1, 0, -1), cur.add(1, 1, 1))) {
				BlockPos im = n.toImmutable();
				// Someone's logs (a cabin wall the tree grew against) aren't part of the tree.
				if (im.getY() >= treeBase.getY() && seen.add(im) && world.getBlockState(im).isIn(BlockTags.LOGS)
					&& !com.botcraft.colony.Builds.recorded(world, im) && !com.botcraft.colony.Builds.builtMaterial(world.getBlockState(im))) {
					queue.add(im);
				}
			}
		}
		out.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(b -> b.getSquaredDistance(treeBase)));
		logs.addAll(out);
		AiLog.log(c.name(), "gather: tree at {} has {} logs", treeBase.toShortString(), out.size());
	}

	private static boolean canStandIn(ServerWorld world, BlockPos pos) {
		Terrain t = new Terrain(world);
		return t.standable(pos);
	}
}

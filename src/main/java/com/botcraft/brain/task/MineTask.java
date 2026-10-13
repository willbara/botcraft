package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.OreKind;
import com.botcraft.memory.Memory;
import com.botcraft.nav.BreakPolicy;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.perception.CaveFinder;
import com.botcraft.util.AiLog;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Mine {@code wanted} items of an ore (or cobblestone), the way Baritone's MineProcess does:
 * every ore of the kind known nearby (scanned, or remembered by us or a friend), exposed or not,
 * becomes one composite path goal of "get the feet or head into this block" goals. A* reaches
 * the cheapest by path cost, digging time included, so the nearest reachable ore wins, and the
 * path's last moves mine it. We <b>lock onto</b> the ore it picked until it's mined (no circling
 * between ores; others see the claim). Whole veins are mined, drops collected.
 *
 * <p>With no ore known in range: look for a cave and go into it, else dig a staircase down
 * toward the ore's depth and strip-mine along. Needs a suitable pickaxe; if there isn't one, it
 * queues crafting one first.
 */
public class MineTask extends Task {
	private enum Phase { SCAN, CHOOSE, APPROACH, MINE, COLLECT, CAVE_SEARCH, CAVE, EXPLORE }

	private static final int SCAN_RADIUS = 24;
	private static final int MAX_EXPLORES = 24;
	private static final int MAX_FAILURES = 16;
	/** Ores in one composite goal, nearest first (Baritone keeps 64; the heuristic is a min over all of them). */
	private static final int MAX_CHOICES = 32;
	private static final int MAX_CAVE_SEARCHES = 3;

	private final OreKind kind;
	private final int wanted;
	private final Random random = new Random();
	private int gathered;
	private int lastCount = -1;
	private Phase phase = Phase.SCAN;
	private int phaseTicks;
	@Nullable
	private BlockPos target;
	private List<BlockPos> choices = List.of();
	@Nullable
	private Goal composite;
	private final Deque<BlockPos> vein = new ArrayDeque<>();
	private final Set<BlockPos> blacklist = new HashSet<>();
	private final List<BlockPos> visitedCaves = new ArrayList<>();
	private List<BlockPos> caveChoices = List.of();
	private final CaveFinder caves = new CaveFinder();
	private int caveSearches;
	private int explores;
	/** Moving to reach the next block of a vein; time spent finishing a vein after we had enough. */
	private int repositionTicks;
	private int overtime;
	private int failures;
	private Direction exploreDir;
	@Nullable
	private ItemEntity collecting;
	private final Pickup pickup = new Pickup();
	private int replans;
	private boolean askedForPickaxe;

	public MineTask(Companion c, OreKind kind, int count) {
		super(c);
		this.kind = kind;
		this.wanted = Math.max(1, count);
		this.exploreDir = Direction.Type.HORIZONTAL.random(net.minecraft.util.math.random.Random.create());
	}

	@Override
	public String name() {
		return "mine_" + kind.word;
	}

	@Override
	public String describe() {
		String where = phase == Phase.CAVE ? ", looking in a cave" : phase == Phase.EXPLORE ? ", digging around" : "";
		return "mining " + kind.noun() + " (" + gathered + "/" + wanted + ")" + where;
	}

	public OreKind kind() {
		return kind;
	}

	public int gathered() {
		return gathered;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * (kind.deep() ? 25 : 15);
	}

	@Override
	protected void onStart(boolean resumed) {
		lastCount = -1;
		// Resuming with a locked target: carry on toward it rather than re-choosing.
		if (resumed && target != null && kind.blocks().test(c.player().getWorld().getBlockState(target))) {
			goTo(target);
		} else {
			setPhase(Phase.SCAN);
		}
	}

	@Override
	protected void onStop() {
		com.botcraft.brain.Claims.release(c);
		super.onStop();
	}

	private void setPhase(Phase p) {
		AiLog.log(c.name(), "mine {}: {} -> {}", kind.word, phase, p);
		phase = p;
		phaseTicks = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		countProgress(p);
		if (gathered >= wanted) {
			// A player finishes the vein they're on (and picks up the drops) rather than leaving
			// half of it in the wall.
			boolean midVein = (phase == Phase.MINE && !vein.isEmpty()) || phase == Phase.COLLECT;
			if (!midVein || ++overtime > 20 * 45) {
				return Status.SUCCESS;
			}
		}
		if (!hasPickaxe(p)) {
			if (!askedForPickaxe) {
				askedForPickaxe = true;
				com.botcraft.Companion helper = com.botcraft.brain.Sharing.ask(c, kind.pickaxe, kind.pickaxe.replace('_', ' '), 1, true);
				if (helper != null) {
					return deferTo(List.of("await " + kind.pickaxe + " 1 " + helper.name()));
				}
			}
			return deferTo(List.of("craft " + kind.pickaxe + " 1"));
		}
		if (failures >= MAX_FAILURES) {
			return fail("I couldn't get to any " + kind.noun());
		}
		phaseTicks++;
		if (ticks() % 10 == 0 && (phase == Phase.APPROACH || phase == Phase.CHOOSE || phase == Phase.CAVE || phase == Phase.EXPLORE)
			&& grabOnTheWay(p)) {
			return Status.RUNNING;
		}
		return switch (phase) {
			case SCAN -> scan(p);
			case CHOOSE -> choose(p);
			case APPROACH -> approach(p);
			case MINE -> mine(p);
			case COLLECT -> collect(p);
			case CAVE_SEARCH -> caveSearch(p);
			case CAVE -> cave(p);
			case EXPLORE -> explore(p);
		};
	}

	private boolean hasPickaxe(CompanionPlayer p) {
		var state = kind.sample();
		var inv = p.getInventory();
		for (int i = 0; i < inv.main.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty() && s.isSuitableFor(state) && (!s.isDamageable() || s.getMaxDamage() - s.getDamage() > 2)) {
				return true;
			}
		}
		return false;
	}

	private void countProgress(CompanionPlayer p) {
		int now = Inventories.count(p.getInventory(), kind::isDrop);
		if (lastCount >= 0 && now > lastCount) {
			gathered += now - lastCount;
			c.memory().count("mined_" + kind.word, now - lastCount);
			c.data.job = "mine " + kind.word + " " + Math.max(0, wanted - gathered);
		}
		lastCount = now;
	}

	private String dim() {
		return c.dimension();
	}

	// ---- finding a target ----------------------------------------------------------------------

	private Status scan(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		if (phaseTicks == 1 && kind != OreKind.STONE) {
			// More of it right where we are (the rest of this vein, one just behind the wall)?
			// That beats walking off to the next one we can see.
			BlockPos here = p.getBlockPos();
			List<BlockPos> local = new ArrayList<>();
			for (BlockPos b : c.perception().surroundings().ores(kind, here)) {
				if (b.getSquaredDistance(here) <= 7 * 7 && !blacklist.contains(b) && kind.blocks().test(world.getBlockState(b))) {
					local.add(b);
				}
			}
			if (!local.isEmpty()) {
				AiLog.log(c.name(), "mine {}: {} more right around here", kind.word, local.size());
				return chooseAmong(p, local);
			}
		}
		if (phaseTicks == 1) {
			c.perception().blocks().start(kind.blocks(), SCAN_RADIUS, 24, 12);
		}
		if (!c.perception().blocks().tick(ticks())) {
			return phaseTicks > 300 ? fail("scan took too long") : Status.RUNNING;
		}
		Set<BlockPos> all = new LinkedHashSet<>();
		// Spots we (or a friend who told us) remember.
		for (Memory.Sighting s : c.memory().sightings(kind.word, p.getBlockPos(), dim(), 64)) {
			if (world.isChunkLoaded(s.pos()) && kind.blocks().test(world.getBlockState(s.pos()))) {
				all.add(s.pos());
			} else {
				c.memory().forgetSighting(kind.word, s.pos());
			}
		}
		all.addAll(c.perception().blocks().results());
		all.removeIf(blacklist::contains);
		if (kind != OreKind.STONE) {
			int n = 0;
			for (BlockPos b : all) {
				if (exposed(world, b) && n++ < 12) {
					c.memory().sight(kind.word, b, dim(), world.getTime());
				}
			}
		}
		AiLog.log(c.name(), "mine {}: {} known in range", kind.word, all.size());
		if (!all.isEmpty()) {
			return chooseAmong(p, new ArrayList<>(all));
		}
		if (kind == OreKind.STONE) {
			setPhase(Phase.EXPLORE);
			return Status.RUNNING;
		}
		// Nothing in range: a player would go find a cave before tunnelling blind.
		if (caveSearches < MAX_CAVE_SEARCHES) {
			setPhase(Phase.CAVE_SEARCH);
			return Status.RUNNING;
		}
		setPhase(Phase.EXPLORE);
		return Status.RUNNING;
	}

	/**
	 * Walking somewhere (to a vein further off, a cave, exploring) and the ore we want is right
	 * here in reach and in sight: take it now instead of walking past.
	 */
	private boolean grabOnTheWay(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		BlockPos eye = BlockPos.ofFloored(p.getEyePos());
		BlockPos best = null;
		for (BlockPos b : c.perception().surroundings().ores(kind, eye)) {
			if (b.getSquaredDistance(eye) > 5 * 5) {
				break; // nearest first: the rest are out of reach
			}
			if (blacklist.contains(b) || b.equals(target) || !kind.blocks().test(world.getBlockState(b)) || !plausible(world, b)) {
				continue;
			}
			if (c.actions().canReachBlock(b)) {
				best = b;
				break;
			}
		}
		if (best == null) {
			return false;
		}
		AiLog.log(c.name(), "mine {}: {} right here at {}, taking it", kind.word, kind.noun(), best.toShortString());
		c.nav().stop();
		target = best;
		com.botcraft.brain.Claims.claim(c, best);
		collectVein(world);
		setPhase(Phase.MINE);
		return true;
	}

	/**
	 * Worth trying to mine (Baritone's plausibleToBreak): we may break it, and it doesn't let
	 * liquid in (nothing liquid above or beside it; see BreakPolicy).
	 */
	private static boolean plausible(ServerWorld world, BlockPos pos) {
		return BreakPolicy.canBreakForNav(world, pos, world.getBlockState(pos), true);
	}

	private static boolean exposed(ServerWorld world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			var s = world.getBlockState(pos.offset(d));
			if (s.isAir()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Let A* pick the cheapest candidate to actually reach (composite goal), then lock onto it.
	 */
	private Status chooseAmong(CompanionPlayer p, List<BlockPos> candidates) {
		BlockPos from = p.getBlockPos();
		ServerWorld world = p.getServerWorld();
		var colony = com.botcraft.colony.Colony.get();
		List<BlockPos> ok = candidates.stream().filter(b -> plausible(world, b) && (colony == null || !colony.protectsGround(world, b))).toList();
		if (ok.isEmpty()) {
			blacklist.addAll(candidates);
			setPhase(Phase.EXPLORE); // all of it under water or at home: look elsewhere
			return Status.RUNNING;
		}
		// Leave veins someone else is already working alone (unless that's all there is).
		List<BlockPos> sorted = new ArrayList<>(com.botcraft.brain.Claims.spreadOut(c, ok, b -> b, 6));
		sorted.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(from)));
		choices = sorted.subList(0, Math.min(MAX_CHOICES, sorted.size()));
		List<Goal> goals = new ArrayList<>();
		for (BlockPos b : choices) {
			goals.add(intoGoal(world, b));
		}
		composite = Goal.anyOf(goals);
		c.nav().moveTo(composite, from.getSquaredDistance(choices.get(0)) > 100);
		setPhase(Phase.CHOOSE);
		return Status.RUNNING;
	}

	/** Get into the block, or under it when nothing above would fall on us (Baritone's GoalTwoBlocks / GoalThreeBlocks). */
	private static Goal intoGoal(ServerWorld world, BlockPos b) {
		return Goal.into(b, !(world.getBlockState(b.up()).getBlock() instanceof net.minecraft.block.FallingBlock));
	}

	private Status choose(CompanionPlayer p) {
		BlockPos end = c.nav().plannedEnd();
		if (end != null && composite != null) {
			int i = composite.which(end);
			BlockPos picked = choices.get(Math.max(0, i));
			AiLog.log(c.name(), "mine {}: locked onto {} (of {} candidates)", kind.word, picked.toShortString(), choices.size());
			target = picked;
			com.botcraft.brain.Claims.claim(c, picked);
			replans = 0;
			// Keep following the path that was found; future re-plans aim at this target only.
			setPhase(Phase.APPROACH);
			return Status.RUNNING;
		}
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 20) {
			AiLog.log(c.name(), "mine {}: none of {} candidates reachable ({})", kind.word, choices.size(), c.nav().failReason());
			blacklist.addAll(choices);
			failures++;
			setPhase(caveSearches < MAX_CAVE_SEARCHES && kind != OreKind.STONE ? Phase.CAVE_SEARCH : Phase.SCAN);
		}
		return Status.RUNNING;
	}

	private Status goTo(BlockPos pos) {
		target = pos.toImmutable();
		replans = 0;
		c.nav().moveTo(intoGoal(c.player().getServerWorld(), target), c.player().getBlockPos().getSquaredDistance(target) > 100);
		setPhase(Phase.APPROACH);
		return Status.RUNNING;
	}

	private Status approach(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		if (!kind.blocks().test(world.getBlockState(target))) {
			c.memory().forgetSighting(kind.word, target);
			if (p.getBlockPos().getSquaredDistance(target) <= 3 * 3) {
				// The path's last move mined it: on to the rest of the vein.
				c.nav().stop();
				collectVein(world);
				setPhase(Phase.MINE);
			} else {
				setPhase(Phase.SCAN);
			}
			return Status.RUNNING;
		}
		Navigator.State s = c.nav().state();
		if (c.actions().canReachBlock(target) && (s == Navigator.State.ARRIVED || p.isOnGround())) {
			c.nav().stop(); // in reach and in sight (or right over our head at the end of a shaft)
			collectVein(world);
			setPhase(Phase.MINE);
		} else if ((s == Navigator.State.IDLE || s == Navigator.State.FAILED) && replans++ < 4) {
			c.nav().moveTo(intoGoal(world, target), false); // locked target: re-plan to the same one
		} else if (s == Navigator.State.ARRIVED) {
			AiLog.log(c.name(), "mine {}: arrived but can't reach {} ({})", kind.word, target.toShortString(), c.actions().reachDebug(target));
			blacklist.add(target);
			failures++;
			c.nav().stop();
			setPhase(Phase.SCAN);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 90) {
			AiLog.log(c.name(), "mine {}: giving up on {} ({})", kind.word, target.toShortString(), c.nav().failReason());
			blacklist.add(target);
			failures++;
			setPhase(Phase.SCAN);
		}
		return Status.RUNNING;
	}

	// ---- caves -----------------------------------------------------------------------------------

	private Status caveSearch(CompanionPlayer p) {
		if (phaseTicks == 1) {
			caveSearches++;
			// Deep ores: look further and prefer caves near the ore's depth.
			caves.start(p.getServerWorld(), p.getBlockPos(), kind.deep() ? 64 : 40, kind.deep() || kind == OreKind.IRON ? kind.bestY : null);
		}
		if (!caves.tick()) {
			return Status.RUNNING;
		}
		List<BlockPos> fresh = new ArrayList<>();
		for (BlockPos cave : com.botcraft.brain.Claims.spreadOut(c, caves.results(), b -> b, 10)) {
			if (visitedCaves.stream().noneMatch(v -> v.getSquaredDistance(cave) < 12 * 12)) {
				fresh.add(cave);
			}
			if (fresh.size() >= 8) {
				break;
			}
		}
		if (fresh.isEmpty()) {
			AiLog.log(c.name(), "mine {}: no caves nearby", kind.word);
			caveSearches = MAX_CAVE_SEARCHES;
			setPhase(Phase.EXPLORE);
			return Status.RUNNING;
		}
		caveChoices = fresh;
		List<Goal> goals = new ArrayList<>();
		for (BlockPos cave : fresh) {
			goals.add(Goal.near(cave, 2.0));
		}
		AiLog.log(c.name(), "mine {}: heading for a cave ({} candidates, nearest {})", kind.word, fresh.size(), fresh.get(0).toShortString());
		c.nav().moveTo(Goal.anyOf(goals), true);
		setPhase(Phase.CAVE);
		return Status.RUNNING;
	}

	private Status cave(CompanionPlayer p) {
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED) {
			BlockPos here = p.getBlockPos();
			visitedCaves.add(here);
			c.memory().sight("cave", here, dim(), p.getWorld().getTime());
			AiLog.log(c.name(), "mine {}: in a cave at {}, looking around", kind.word, here.toShortString());
			setPhase(Phase.SCAN);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 90) {
			visitedCaves.addAll(caveChoices);
			c.nav().stop();
			setPhase(Phase.SCAN);
		}
		return Status.RUNNING;
	}

	// ---- mining -----------------------------------------------------------------------------------

	private Status mine(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		while (!vein.isEmpty() && !kind.blocks().test(world.getBlockState(vein.peek()))) {
			c.memory().forgetSighting(kind.word, vein.pop());
			phaseTicks = 0;
		}
		BlockPos next = vein.peek();
		if (next == null) {
			setPhase(Phase.COLLECT);
			return Status.RUNNING;
		}
		if (!c.actions().canReachBlock(next)) {
			// The rest of the vein is a step or two further, or behind stone: path into it.
			Navigator.State ns = c.nav().state();
			if (repositionTicks == 0 || ns == Navigator.State.IDLE) {
				c.nav().moveTo(intoGoal(world, next), false);
			}
			if (++repositionTicks > 20 * 30 || ns == Navigator.State.FAILED
				|| (ns == Navigator.State.ARRIVED && !c.actions().canReachBlock(next) && kind.blocks().test(world.getBlockState(next)))) {
				AiLog.log(c.name(), "mine {}: can't get at {} in this vein, leaving it", kind.word, next.toShortString());
				blacklist.add(vein.pop());
				repositionTicks = 0;
				c.nav().stop();
			}
			return Status.RUNNING;
		}
		if (repositionTicks > 0) {
			repositionTicks = 0;
			c.nav().stop();
		}
		ActionController.MineResult r = c.actions().mineBlock(next);
		if (r == ActionController.MineResult.FAILED || phaseTicks > 20 * 20) {
			AiLog.log(c.name(), "mine {}: skipping {} ({})", kind.word, next.toShortString(), r);
			c.actions().abortMining();
			blacklist.add(vein.pop());
			phaseTicks = 0;
		}
		return Status.RUNNING;
	}

	private Status collect(CompanionPlayer p) {
		if (collecting == null || !collecting.isAlive()) {
			collecting = nearestDrop(p);
			if (collecting == null) {
				target = null;
				setPhase(Phase.SCAN);
				return Status.RUNNING;
			}
		}
		if (!pickup.tick(c, collecting)) {
			collecting = null;
		}
		return Status.RUNNING;
	}

	private Status explore(CompanionPlayer p) {
		if (phaseTicks == 1) {
			if (++explores > MAX_EXPLORES) {
				return fail("I couldn't find any " + kind.noun() + " around here");
			}
			BlockPos here = p.getBlockPos();
			int minY = p.getWorld().getBottomY() + 6;
			BlockPos goal;
			if (here.getY() > kind.bestY + 4) {
				// Too high: staircase down toward the ore's depth, quickly.
				int down = Math.min(12, Math.max(0, Math.min(here.getY() - kind.bestY, here.getY() - minY)));
				goal = here.offset(exploreDir, 10).down(down);
				AiLog.log(c.name(), "mine {}: heading down toward y={} ({} -> {})", kind.word, kind.bestY, here.toShortString(), goal.toShortString());
			} else {
				// At the right depth: strip-mine forward and look again.
				goal = here.offset(exploreDir, 16);
				AiLog.log(c.name(), "mine {}: strip mining {} toward {}", kind.word, exploreDir, goal.toShortString());
			}
			c.nav().moveTo(Goal.near(goal, 1.5), false);
		}
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED || phaseTicks > 20 * 60) {
			c.nav().stop();
			if (!atDepth(p.getBlockPos()) || explores % 4 == 0) {
				caveSearches = Math.min(caveSearches, MAX_CAVE_SEARCHES - 1); // new area: worth one more cave look
			}
			setPhase(Phase.SCAN);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE) {
			exploreDir = random.nextBoolean() ? exploreDir.rotateYClockwise() : exploreDir.rotateYCounterclockwise();
			failures++;
			setPhase(Phase.SCAN);
		}
		return Status.RUNNING;
	}

	// ---- helpers --------------------------------------------------------------------------------

	private boolean atDepth(BlockPos here) {
		return kind != OreKind.STONE && Math.abs(here.getY() - kind.bestY) <= 10;
	}

	private void collectVein(ServerWorld world) {
		vein.clear();
		var colony = com.botcraft.colony.Colony.get();
		List<BlockPos> out = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(target);
		seen.add(target);
		int limit = kind == OreKind.STONE ? Math.min(8, wanted - gathered + 1) : 24;
		while (!queue.isEmpty() && out.size() < limit) {
			BlockPos cur = queue.poll();
			out.add(cur);
			// Veins connect diagonally too (that's how they look in the wall).
			for (BlockPos n : BlockPos.iterate(cur.add(-1, -1, -1), cur.add(1, 1, 1))) {
				if (seen.add(n.toImmutable()) && kind.blocks().test(world.getBlockState(n)) && !blacklist.contains(n) && plausible(world, n)
					&& (colony == null || !colony.protectsGround(world, n))) {
					queue.add(n.toImmutable());
				}
			}
		}
		// Nearest first, so we eat into the vein from our side.
		BlockPos eye = c.player().getBlockPos().up();
		out.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(eye)));
		vein.addAll(out);
	}

	@Nullable
	private ItemEntity nearestDrop(CompanionPlayer p) {
		Box box = p.getBoundingBox().expand(6.0, 3.0, 6.0);
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && kind.isDrop(e.getStack()) && !pickup.ignored(e)
			&& !com.botcraft.brain.Blackboard.claimedByOther(c, e));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}
}

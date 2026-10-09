package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.OreKind;
import com.mcfriends.memory.Memory;
import com.mcfriends.nav.BreakPolicy;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.perception.CaveFinder;
import com.mcfriends.util.AiLog;
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
 * Mine {@code wanted} items of an ore (or cobblestone), the way a player would:
 * <ol>
 *   <li>look for ore we can already see (exposed to air: in caves, ravines, cliffs), including
 *       spots we or a friend remembered;</li>
 *   <li>if none is exposed nearby, <b>find a cave</b> and go into it, then look again;</li>
 *   <li>only then dig toward buried ore close by, or dig a staircase toward the ore's depth.</li>
 * </ol>
 * Choosing among many candidates is done like Baritone: all of them become one composite path
 * goal, A* reaches the cheapest, and we <b>lock onto</b> that one until it's mined (no circling
 * between ores). Whole veins are mined, drops collected.
 *
 * <p>While this job runs the navigator is in dig mode: natural terrain only, never next to
 * fluids. Needs a suitable pickaxe; if there isn't one, it queues crafting one first.
 */
public class MineTask extends Task {
	private enum Phase { SCAN, CHOOSE, APPROACH, MINE, COLLECT, CAVE_SEARCH, CAVE, EXPLORE }

	private static final int SCAN_RADIUS = 24;
	private static final int MAX_EXPLORES = 24;
	private static final int MAX_FAILURES = 16;
	private static final int MAX_CHOICES = 16;
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
	private List<BlockPos> buried = List.of();
	private int caveSearches;
	private int explores;
	private int failures;
	private Direction exploreDir;
	@Nullable
	private ItemEntity collecting;
	private final Pickup pickup = new Pickup();
	@Nullable
	private BlockPos digging;
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
		c.nav().setDigMode(true);
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
		c.nav().setDigMode(false);
		com.mcfriends.brain.Claims.release(c);
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
			return Status.SUCCESS;
		}
		if (!hasPickaxe(p)) {
			if (!askedForPickaxe) {
				askedForPickaxe = true;
				com.mcfriends.Companion helper = com.mcfriends.brain.Sharing.ask(c, kind.pickaxe, kind.pickaxe.replace('_', ' '), 1, true);
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
		List<BlockPos> exposed = new ArrayList<>();
		List<BlockPos> hidden = new ArrayList<>();
		for (BlockPos b : all) {
			(exposed(world, b) ? exposed : hidden).add(b.toImmutable());
		}
		if (kind != OreKind.STONE) {
			int n = 0;
			for (BlockPos b : exposed) {
				if (n++ < 12) {
					c.memory().sight(kind.word, b, dim(), world.getTime());
				}
			}
		}
		AiLog.log(c.name(), "mine {}: {} exposed, {} buried in range", kind.word, exposed.size(), hidden.size());
		if (!exposed.isEmpty()) {
			return chooseAmong(p, exposed);
		}
		BlockPos here = p.getBlockPos();
		List<BlockPos> nearBuried = hidden.stream().filter(b -> Math.abs(b.getX() - here.getX()) <= 12 && Math.abs(b.getZ() - here.getZ()) <= 12
			&& Math.abs(b.getY() - here.getY()) <= 10).toList();
		if (!nearBuried.isEmpty()) {
			buried = nearBuried; // remember these in case the cave trip comes up empty
		}
		// Very close buried ore is quicker to dig to than any cave trip.
		List<BlockPos> veryClose = nearBuried.stream().filter(b -> b.getSquaredDistance(here) <= 6.5 * 6.5).toList();
		if (!veryClose.isEmpty() && kind != OreKind.STONE) {
			return chooseAmong(p, veryClose);
		}
		// Already down at the ore's depth: dig to what's nearby rather than hunting for more caves.
		if (!nearBuried.isEmpty() && kind != OreKind.STONE && atDepth(here)) {
			return chooseAmong(p, nearBuried);
		}
		if (kind == OreKind.STONE) {
			if (!hidden.isEmpty()) {
				return chooseAmong(p, hidden); // stone: just dig some, it's everywhere
			}
			setPhase(Phase.EXPLORE);
			return Status.RUNNING;
		}
		// Nothing in sight: a player would go find a cave before tunnelling blind.
		if (caveSearches < MAX_CAVE_SEARCHES) {
			setPhase(Phase.CAVE_SEARCH);
			return Status.RUNNING;
		}
		if (!buried.isEmpty()) {
			return chooseAmong(p, buried);
		}
		setPhase(Phase.EXPLORE);
		return Status.RUNNING;
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
		// Leave veins someone else is already working alone (unless that's all there is).
		List<BlockPos> sorted = new ArrayList<>(com.mcfriends.brain.Claims.spreadOut(c, candidates, b -> b, 6));
		sorted.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(from) + 3.0 * (b.getY() - from.getY()) * (b.getY() - from.getY())));
		choices = sorted.subList(0, Math.min(MAX_CHOICES, sorted.size()));
		List<Goal> goals = new ArrayList<>();
		for (BlockPos b : choices) {
			goals.add(reachGoal(b));
		}
		composite = Goal.anyOf(goals);
		c.nav().moveTo(composite, from.getSquaredDistance(choices.get(0)) > 100);
		setPhase(Phase.CHOOSE);
		return Status.RUNNING;
	}

	private Goal reachGoal(BlockPos b) {
		return Goal.reachVisible(c.player().getWorld(), b, ActionController.BLOCK_REACH - 0.5);
	}

	private Status choose(CompanionPlayer p) {
		BlockPos end = c.nav().plannedEnd();
		if (end != null && composite != null) {
			int i = composite.which(end);
			BlockPos picked = choices.get(Math.max(0, i));
			AiLog.log(c.name(), "mine {}: locked onto {} (of {} candidates)", kind.word, picked.toShortString(), choices.size());
			target = picked;
			com.mcfriends.brain.Claims.claim(c, picked);
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
		c.nav().moveTo(reachGoal(target), c.player().getBlockPos().getSquaredDistance(target) > 100);
		setPhase(Phase.APPROACH);
		return Status.RUNNING;
	}

	private Status approach(CompanionPlayer p) {
		if (!kind.blocks().test(p.getWorld().getBlockState(target))) {
			c.memory().forgetSighting(kind.word, target);
			setPhase(Phase.SCAN);
			return Status.RUNNING;
		}
		Navigator.State s = c.nav().state();
		if (c.actions().canReachBlock(target) && (s == Navigator.State.ARRIVED || (p.isOnGround() && phaseTicks > 5))) {
			c.nav().stop();
			collectVein(p.getServerWorld());
			setPhase(Phase.MINE);
		} else if (digThrough(p, target)) {
			if (c.nav().isBusy()) {
				c.nav().stop(); // in reach but hidden behind stone: dig toward it like a player would
			}
		} else if ((s == Navigator.State.IDLE || s == Navigator.State.FAILED) && replans++ < 4) {
			c.nav().moveTo(reachGoal(target), false); // locked target: re-plan to the same one
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
		for (BlockPos cave : com.mcfriends.brain.Claims.spreadOut(c, caves.results(), b -> b, 10)) {
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
			if (!buried.isEmpty()) {
				return chooseAmong(p, buried);
			}
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
			if (digThrough(p, next)) {
				return Status.RUNNING;
			}
			vein.pop();
			if (vein.isEmpty() && next.getSquaredDistance(p.getBlockPos()) < 8 * 8) {
				return goTo(next); // the rest of the vein is just further in
			}
			return Status.RUNNING;
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

	/** If natural terrain hides an in-reach target, mine it. @return true while doing so */
	private boolean digThrough(CompanionPlayer p, BlockPos goal) {
		// Stick with one block until it's gone; the raycast can flip between neighbours as we sway.
		BlockPos block = digging != null && !p.getWorld().getBlockState(digging).isAir() ? digging : c.actions().obstruction(goal);
		digging = block == null ? null : block.toImmutable();
		if (block == null) {
			return false;
		}
		var state = p.getWorld().getBlockState(block);
		if (!BreakPolicy.canBreakForNav(p.getServerWorld(), block, state, true)) {
			return false;
		}
		return c.actions().mineBlock(block) != ActionController.MineResult.FAILED;
	}

	private void collectVein(ServerWorld world) {
		vein.clear();
		List<BlockPos> out = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(target);
		seen.add(target);
		int limit = kind == OreKind.STONE ? Math.min(8, wanted - gathered + 1) : 16;
		while (!queue.isEmpty() && out.size() < limit) {
			BlockPos cur = queue.poll();
			out.add(cur);
			for (Direction d : Direction.values()) {
				BlockPos n = cur.offset(d);
				if (seen.add(n) && kind.blocks().test(world.getBlockState(n)) && !blacklist.contains(n)) {
					queue.add(n);
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
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && kind.isDrop(e.getStack()) && !pickup.ignored(e));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}
}

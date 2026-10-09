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
import com.mcfriends.util.AiLog;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Mine {@code wanted} items of an ore (or cobblestone). Looks in memory for ores seen before,
 * then scans around; if nothing is in range it explores by digging a staircase (down toward
 * the ore's usual depth, otherwise sideways) and scans again.
 *
 * <p>While this job runs the navigator is in dig mode: it may tunnel through natural terrain
 * (stone, dirt, ores...) but never through built blocks, and never next to fluids. Needs a
 * suitable pickaxe; if there isn't one, it queues crafting one first.
 */
public class MineTask extends Task {
	private enum Phase { SCAN, APPROACH, MINE, COLLECT, EXPLORE }

	private static final int SCAN_RADIUS = 24;
	private static final int MAX_EXPLORES = 6;
	private static final int MAX_FAILURES = 8;

	private final OreKind kind;
	private final int wanted;
	private final Random random = new Random();
	private int gathered;
	private int lastCount = -1;
	private Phase phase = Phase.SCAN;
	private int phaseTicks;
	@Nullable
	private BlockPos target;
	private final Deque<BlockPos> vein = new ArrayDeque<>();
	private final Set<BlockPos> blacklist = new HashSet<>();
	private int explores;
	private int failures;
	private Direction exploreDir;
	@Nullable
	private ItemEntity collecting;
	@Nullable
	private BlockPos digging;
	private int replans;

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
		return "mining " + kind.noun() + " (" + gathered + "/" + wanted + ")";
	}

	public OreKind kind() {
		return kind;
	}

	public int gathered() {
		return gathered;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 15;
	}

	@Override
	protected void onStart(boolean resumed) {
		c.nav().setDigMode(true);
		lastCount = -1;
		setPhase(Phase.SCAN);
	}

	@Override
	protected void onStop() {
		c.nav().setDigMode(false);
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
			return deferTo(List.of("craft " + kind.pickaxe + " 1"));
		}
		if (failures >= MAX_FAILURES) {
			return fail("I couldn't get to any " + kind.noun());
		}
		phaseTicks++;
		return switch (phase) {
			case SCAN -> scan(p);
			case APPROACH -> approach(p);
			case MINE -> mine(p);
			case COLLECT -> collect(p);
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

	private String dim(CompanionPlayer p) {
		return p.getWorld().getRegistryKey().getValue().toString();
	}

	// ---- phases ---------------------------------------------------------------------------------

	private Status scan(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		if (phaseTicks == 1) {
			// Somewhere we remember seeing this ore?
			for (Memory.Sighting s : c.memory().sightings(kind.word, p.getBlockPos(), dim(p), 64)) {
				BlockPos pos = s.pos();
				if (blacklist.contains(pos)) {
					continue;
				}
				if (world.isChunkLoaded(pos) && kind.blocks().test(world.getBlockState(pos))) {
					AiLog.log(c.name(), "mine {}: remembered a spot at {}", kind.word, pos.toShortString());
					return goTo(pos);
				}
				c.memory().forgetSighting(kind.word, pos);
			}
			c.perception().blocks().start(kind.blocks(), SCAN_RADIUS, 24, 12);
		}
		if (!c.perception().blocks().tick(ticks())) {
			return phaseTicks > 300 ? fail("scan took too long") : Status.RUNNING;
		}
		List<BlockPos> found = new ArrayList<>(c.perception().blocks().results());
		found.removeIf(blacklist::contains);
		if (kind == OreKind.STONE) {
			// Plenty of stone around: prefer exposed blocks so we don't tunnel for no reason.
			found.removeIf(b -> !exposed(world, b));
		} else {
			for (int i = 0; i < Math.min(12, found.size()); i++) {
				c.memory().sight(kind.word, found.get(i), dim(p), world.getTime());
			}
		}
		if (found.isEmpty()) {
			setPhase(Phase.EXPLORE);
			return Status.RUNNING;
		}
		// Nearest by rough travel cost: vertical distance counts extra (digging is slow).
		BlockPos from = p.getBlockPos();
		found.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(from) + 3.0 * (b.getY() - from.getY()) * (b.getY() - from.getY())));
		return goTo(found.get(0));
	}

	private static boolean exposed(ServerWorld world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (world.getBlockState(pos.offset(d)).isAir()) {
				return true;
			}
		}
		return false;
	}

	private Status goTo(BlockPos pos) {
		target = pos.toImmutable();
		replans = 0;
		c.nav().moveTo(Goal.reachVisible(c.player().getWorld(), target, ActionController.BLOCK_REACH - 0.5), c.player().getBlockPos().getSquaredDistance(target) > 100);
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
		if (phaseTicks % 200 == 0) {
			AiLog.log(c.name(), "mine {}: approaching {} from {} nav {} dig {} reach {}", kind.word, target.toShortString(), p.getPos(), s,
				digging, c.actions().reachDebug(target));
		}
		if (c.actions().canReachBlock(target) && (s == Navigator.State.ARRIVED || (p.isOnGround() && phaseTicks > 5))) {
			c.nav().stop();
			collectVein(p.getServerWorld());
			setPhase(Phase.MINE);
		} else if (digThrough(p, target)) {
			if (c.nav().isBusy()) {
				c.nav().stop(); // in reach but hidden behind stone: dig toward it like a player would
			}
		} else if (s == Navigator.State.IDLE && replans++ < 5) {
			// We paused navigation to dig; carry on toward the target.
			c.nav().moveTo(Goal.reachVisible(p.getWorld(), target, ActionController.BLOCK_REACH - 0.5), false);
		} else if (s == Navigator.State.ARRIVED) {
			// The planner thought we could see it from here but we can't: blacklist and move on.
			AiLog.log(c.name(), "mine {}: arrived but can't reach {} ({})", kind.word, target.toShortString(), c.actions().reachDebug(target));
			blacklist.add(target);
			failures++;
			c.nav().stop();
			setPhase(Phase.SCAN);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 90) {
			AiLog.log(c.name(), "mine {}: can't reach {} ({})", kind.word, target.toShortString(), c.nav().failReason());
			blacklist.add(target);
			failures++;
			setPhase(Phase.SCAN);
		}
		return Status.RUNNING;
	}

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
			AiLog.log(c.name(), "mine {}: {} out of reach ({})", kind.word, next.toShortString(), c.actions().reachDebug(next));
			vein.pop();
			if (!vein.isEmpty() || !kind.blocks().test(world.getBlockState(next))) {
				return Status.RUNNING;
			}
			return goTo(next); // the rest of the vein is further in: go there
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
			phaseTicks = 0;
			if (collecting == null) {
				setPhase(Phase.SCAN);
				return Status.RUNNING;
			}
		}
		// Aim for the middle of the item's block: drops in a hole under our feet are only picked
		// up once we actually step into the hole.
		Vec3d at = Vec3d.ofBottomCenter(collecting.getBlockPos());
		if (phaseTicks % 20 == 1) {
			AiLog.log(c.name(), "mine {}: collecting {} at {} (me at {}, nav {})", kind.word, collecting.getStack(), at, p.getPos(), c.nav().state());
		}
		if (p.getPos().squaredDistanceTo(at) < 2.5 * 2.5) {
			c.nav().stop();
			c.actions().moveTo(at);
		} else {
			c.nav().moveTo(Goal.near(collecting.getBlockPos(), 1.0), false);
		}
		if (phaseTicks > 20 * 6 || c.nav().state() == Navigator.State.FAILED) {
			collecting = null;
			c.nav().stop();
		}
		return Status.RUNNING;
	}

	private Status explore(CompanionPlayer p) {
		if (phaseTicks == 1) {
			if (++explores > MAX_EXPLORES) {
				return fail("I couldn't find any " + kind.noun() + " around here");
			}
			BlockPos here = p.getBlockPos();
			int down = here.getY() > kind.searchBelowY ? 6 : 0;
			int minY = p.getWorld().getBottomY() + 6;
			BlockPos goal = here.offset(exploreDir, 8).down(Math.min(down, Math.max(0, here.getY() - minY)));
			AiLog.log(c.name(), "mine {}: nothing in range, exploring {} toward {}", kind.word, exploreDir, goal.toShortString());
			c.nav().moveTo(Goal.near(goal, 1.5), false);
		}
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED || phaseTicks > 20 * 60) {
			c.nav().stop();
			setPhase(Phase.SCAN);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE) {
			exploreDir = random.nextBoolean() ? exploreDir.rotateYClockwise() : exploreDir.rotateYCounterclockwise();
			failures++;
			setPhase(Phase.SCAN);
		}
		return Status.RUNNING;
	}

	// ---- helpers --------------------------------------------------------------------------------

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
		return c.actions().mineBlock(block) != com.mcfriends.action.ActionController.MineResult.FAILED;
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
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && kind.isDrop(e.getStack()));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}
}

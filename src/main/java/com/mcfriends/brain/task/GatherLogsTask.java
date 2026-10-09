package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.nav.Terrain;
import com.mcfriends.util.AiLog;
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
	private enum Phase { SCAN, APPROACH, CHOP, CLIMB, COLLECT }

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
	@Nullable
	private ItemEntity collecting;

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
	protected void onStart(boolean resumed) {
		// Re-scan after an interruption; we may have moved (fled, followed).
		setPhase(Phase.SCAN);
		lastCount = -1;
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
		if (gathered >= wanted) {
			return Status.SUCCESS;
		}
		if (treeFailures >= MAX_TREE_FAILURES) {
			return fail("couldn't get to any trees");
		}
		blacklist.values().removeIf(t -> t < ticks());
		phaseTicks++;
		return switch (phase) {
			case SCAN -> scan(p);
			case APPROACH -> approach(p);
			case CHOP -> chop(p);
			case CLIMB -> climb(p);
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
		treeBase = pickTree(p.getServerWorld(), scanner.results(), p.getBlockPos());
		if (treeBase == null) {
			if (++emptyScans >= 2) {
				return fail("no trees nearby");
			}
			setPhase(Phase.SCAN);
			return Status.RUNNING;
		}
		emptyScans = 0;
		AiLog.log(c.name(), "gather: picked tree at {}", treeBase.toShortString());
		c.memory().sight("tree", treeBase, c.dimension(), p.getWorld().getTime());
		c.nav().moveTo(Goal.reachVisible(p.getWorld(), treeBase, ActionController.BLOCK_REACH - 0.5), p.getBlockPos().getSquaredDistance(treeBase) > 100);
		setPhase(Phase.APPROACH);
		return Status.RUNNING;
	}

	private Status approach(CompanionPlayer p) {
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED || (c.actions().canReachBlock(treeBase) && p.isOnGround() && phaseTicks > 5)) {
			c.nav().stop();
			collectTreeLogs(p.getServerWorld());
			setPhase(Phase.CHOP);
		} else if (s == Navigator.State.FAILED || s == Navigator.State.IDLE || phaseTicks > 20 * 60) {
			giveUpOnTree("can't reach it (" + c.nav().failReason() + ")");
		}
		return Status.RUNNING;
	}

	private Status chop(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		while (!logs.isEmpty() && !world.getBlockState(logs.peek()).isIn(BlockTags.LOGS)) {
			AiLog.log(c.name(), "gather: {} is now {}", logs.peek().toShortString(), world.getBlockState(logs.peek()));
			logs.pop();
			phaseTicks = 0;
		}
		BlockPos next = logs.peek();
		if (next == null) {
			setPhase(Phase.COLLECT);
			return Status.RUNNING;
		}
		if (!c.actions().canReachBlock(next)) {
			// Out of reach: if we've opened up the trunk, step into it to reach higher logs.
			if (canStandIn(world, treeBase)) {
				c.nav().moveTo(Goal.near(treeBase, 0.3), false);
				setPhase(Phase.CLIMB);
			} else {
				AiLog.log(c.name(), "gather: log at {} out of reach (eye {})", next.toShortString(), p.getEyePos());
				logs.pop(); // leave it, like most players would
			}
			return Status.RUNNING;
		}
		ActionController.MineResult r = c.actions().mineBlock(next);
		if (r == ActionController.MineResult.FAILED || phaseTicks > 20 * 15) {
			AiLog.log(c.name(), "gather: skipping log at {} ({})", next.toShortString(), r);
			c.actions().abortMining();
			logs.pop();
			phaseTicks = 0;
		}
		return Status.RUNNING;
	}

	private Status climb(CompanionPlayer p) {
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED || Navigator.feet(p).equals(treeBase)) {
			c.nav().stop();
			setPhase(Phase.CHOP);
			// Only logs reachable from here are worth keeping.
			logs.removeIf(l -> !c.actions().canReachBlock(l));
		} else if (s == Navigator.State.FAILED || phaseTicks > 100) {
			logs.clear();
			setPhase(Phase.COLLECT);
		}
		return Status.RUNNING;
	}

	private Status collect(CompanionPlayer p) {
		if (collecting == null || !collecting.isAlive()) {
			collecting = nearestDrop(p);
			phaseTicks = 0;
			if (collecting == null) {
				blacklist.put(treeBase, ticks() + BLACKLIST_TICKS); // done with this tree
				treeBase = null;
				setPhase(Phase.SCAN);
				return Status.RUNNING;
			}
		}
		Vec3d at = Vec3d.ofBottomCenter(collecting.getBlockPos());
		if (p.getPos().squaredDistanceTo(at) < 2.5 * 2.5 && Math.abs(at.y - p.getY()) < 1.5) {
			c.nav().stop();
			c.actions().moveTo(at);
		} else {
			c.nav().moveTo(Goal.near(collecting.getBlockPos(), 1.0), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				collecting = null; // unreachable item, skip it
				c.nav().stop();
				return Status.RUNNING;
			}
		}
		if (phaseTicks > 20 * 8) {
			collecting = null;
			c.nav().stop();
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
		c.nav().stop();
		setPhase(Phase.SCAN);
	}

	@Nullable
	private ItemEntity nearestDrop(CompanionPlayer p) {
		Box box = new Box(treeBase).expand(7.0, 6.0, 7.0);
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, box,
			e -> e.isAlive() && !e.getStack().isEmpty() && (e.getStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS)
				|| e.getStack().isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS) || e.getStack().getItem() == net.minecraft.item.Items.APPLE));
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items.isEmpty() ? null : items.get(0);
	}

	/** Lowest log of a natural tree near us, skipping blacklisted ones. */
	@Nullable
	private BlockPos pickTree(ServerWorld world, List<BlockPos> found, BlockPos from) {
		Set<BlockPos> seenBases = new HashSet<>();
		int checked = 0;
		for (BlockPos log : found) {
			if (++checked > 200) {
				break;
			}
			BlockPos base = log;
			while (world.getBlockState(base.down()).isIn(BlockTags.LOGS) && from.getY() - base.getY() < 12) {
				base = base.down();
			}
			if (!seenBases.add(base) || blacklist.containsKey(base)) {
				continue;
			}
			if (!new Terrain(world).support(base.down())) {
				continue; // floating log / branch, not a trunk we can stand next to
			}
			if (!CompanionConfig.get().griefing && !isNaturalTree(world, base)) {
				continue;
			}
			return base;
		}
		return null;
	}

	/** A log column topped with tree-grown (non-persistent) leaves. */
	static boolean isNaturalTree(ServerWorld world, BlockPos base) {
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
				if (im.getY() >= treeBase.getY() && seen.add(im) && world.getBlockState(im).isIn(BlockTags.LOGS)) {
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

package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Crops;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Break grass/ferns nearby until we have {@code count} wheat seeds (each has ~1/8 chance). */
public class GatherSeedsTask extends Task {
	private static final int RADIUS = 14;
	private static final int MAX_BROKEN = 120;

	private final int count;
	private int broken;
	@Nullable
	private BlockPos target;
	@Nullable
	private ItemEntity collecting;
	private final Set<BlockPos> bad = new HashSet<>();
	private final Pickup pickup = new Pickup();

	public GatherSeedsTask(Companion c, int count) {
		super(c);
		this.count = Math.max(1, count);
	}

	@Override
	public String name() {
		return "seeds";
	}

	@Override
	public String describe() {
		return "looking for seeds in the grass (" + seeds() + "/" + count + ")";
	}

	private int seeds() {
		return Inventories.count(c.player().getInventory(), s -> s.isOf(Items.WHEAT_SEEDS));
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 3;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (seeds() >= count) {
			return Status.SUCCESS;
		}
		if (collecting == null || !collecting.isAlive()) {
			List<ItemEntity> drops = p.getServerWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(6, 3, 6),
				e -> e.isAlive() && e.getStack().isOf(Items.WHEAT_SEEDS) && !pickup.ignored(e));
			drops.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
			collecting = drops.isEmpty() ? null : drops.get(0);
		}
		if (collecting != null) {
			if (!pickup.tick(c, collecting)) {
				collecting = null;
			}
			return Status.RUNNING;
		}
		if (broken >= MAX_BROKEN) {
			return seeds() > 0 ? Status.SUCCESS : fail("no luck finding seeds");
		}
		if (target == null || !Crops.seedGrass(p.getWorld().getBlockState(target))) {
			target = findGrass(p);
			if (target == null) {
				return seeds() > 0 ? Status.SUCCESS : fail("there's no grass around to find seeds in");
			}
		}
		if (c.actions().canReachBlock(target)) {
			c.nav().stop();
			ActionController.MineResult r = c.actions().mineBlock(target);
			if (r == ActionController.MineResult.DONE) {
				broken++;
				target = null;
			} else if (r == ActionController.MineResult.FAILED) {
				bad.add(target);
				target = null;
			}
		} else {
			c.nav().moveTo(Goal.reachVisible(p.getWorld(), target, ActionController.BLOCK_REACH - 0.5), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				bad.add(target);
				target = null;
				c.nav().stop();
			}
		}
		return Status.RUNNING;
	}

	@Nullable
	private BlockPos findGrass(CompanionPlayer p) {
		BlockPos center = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-RADIUS, -3, -RADIUS), center.add(RADIUS, 3, RADIUS))) {
			if (Crops.seedGrass(p.getWorld().getBlockState(pos)) && !bad.contains(pos)) {
				double d = pos.getSquaredDistance(center);
				if (d < bestD) {
					bestD = d;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}
}

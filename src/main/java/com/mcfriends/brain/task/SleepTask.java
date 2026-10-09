package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Find a free bed near the owner (or us) and sleep in it, so the night can be skipped. Uses
 * the vanilla bed interaction (right-click), so all the normal rules apply: only at night,
 * not with monsters nearby, bed must be free. Wakes up when the task stops.
 */
public class SleepTask extends Task {
	private static final int RADIUS = 24;
	@Nullable
	private BlockPos bed;
	private int attempts;

	public SleepTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "sleep";
	}

	@Override
	public String describe() {
		return c.player() != null && c.player().isSleeping() ? "sleeping" : "looking for a bed";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 12; // a whole night
	}

	@Override
	protected void onStart(boolean resumed) {
		bed = null;
	}

	@Override
	protected void onStop() {
		CompanionPlayer p = c.player();
		if (p != null && p.isSleeping()) {
			p.wakeUp(false, true);
		}
		super.onStop();
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (p.isSleeping()) {
			return Status.RUNNING;
		}
		if (p.getWorld().isDay() && ticks() > 40) {
			return Status.SUCCESS; // morning (we woke up, or never needed to sleep)
		}
		if (bed == null) {
			bed = findBed(p);
			if (bed == null) {
				return fail("there's no free bed for me");
			}
			c.nav().moveTo(Goal.near(bed, 1.5), false);
		}
		BlockState s = p.getWorld().getBlockState(bed);
		if (!(s.getBlock() instanceof BedBlock) || s.get(BedBlock.OCCUPIED)) {
			bed = null;
			return ++attempts > 3 ? fail("all the beds are taken") : Status.RUNNING;
		}
		if (c.actions().canReachBlock(bed) && p.getPos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofBottomCenter(bed)) < 2.5 * 2.5) {
			c.nav().stop();
			c.actions().useBlock(bed);
			if (!p.isSleeping() && ++attempts > 5) {
				return fail("I can't sleep now (monsters nearby?)");
			}
			if (p.isSleeping()) {
				AiLog.log(c.name(), "sleeping at {}", bed.toShortString());
				c.memory().setPlace("bed", bed, p.getWorld().getRegistryKey().getValue().toString(), p.getWorld().getTime());
			}
			return Status.RUNNING;
		}
		if (c.nav().state() == Navigator.State.FAILED) {
			bed = null;
			return ++attempts > 3 ? fail("I can't reach the bed") : Status.RUNNING;
		}
		return Status.RUNNING;
	}

	/** Nearest unoccupied bed (head half) around the owner, else around us. */
	@Nullable
	private BlockPos findBed(CompanionPlayer p) {
		ServerPlayerEntity owner = c.perception().owner();
		BlockPos center = owner != null && owner.getWorld() == p.getWorld() ? owner.getBlockPos() : p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-RADIUS, -4, -RADIUS), center.add(RADIUS, 4, RADIUS))) {
			BlockState s = p.getWorld().getBlockState(pos);
			if (s.isIn(BlockTags.BEDS) && s.get(BedBlock.PART) == BedPart.HEAD && !s.get(BedBlock.OCCUPIED)) {
				double d = pos.getSquaredDistance(p.getBlockPos());
				if (d < bestD) {
					bestD = d;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}
}

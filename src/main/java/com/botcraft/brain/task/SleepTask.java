package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.memory.Memory;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Find a free bed and sleep in it, so the night can be skipped. Looks near home in free mode,
 * else near the owner (or us); our own remembered bed counts too. With no free bed around but
 * one in our pockets, we put it down first (at home in free mode). Uses the vanilla bed
 * interaction (right-click), so all the normal rules apply: only at night, not with monsters
 * nearby, bed must be free. Wakes up when the task stops.
 */
public class SleepTask extends Task {
	private static final int RADIUS = 24;
	@Nullable
	private BlockPos bed;
	private int attempts;
	/** Putting our own bed down (no free one around). */
	@Nullable
	private PlaceTask placing;

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
		placing = null;
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
		if (placing != null) {
			Status st = placing.tick();
			if (st == Status.RUNNING) {
				return Status.RUNNING;
			}
			placing.stop();
			String why = placing.failReason();
			placing = null;
			if (st != Status.SUCCESS) {
				return fail("I couldn't put my bed down" + (why == null ? "" : ": " + why));
			}
			AiLog.log(c.name(), "put my bed down to sleep");
		}
		if (bed == null) {
			bed = findBed(p);
			if (bed == null) {
				int slot = Inventories.find(p.getInventory(), st -> st.isIn(ItemTags.BEDS));
				if (slot >= 0 && attempts < 3) {
					attempts++;
					placing = new PlaceTask(c, p.getInventory().getStack(slot).getItem(), placeNear(p));
					placing.start();
					return Status.RUNNING;
				}
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

	/** Where our bed goes: home in free mode (if it's not a trek), else right here. */
	private BlockPos placeNear(CompanionPlayer p) {
		Memory.Place home = c.memory().place("home");
		if ("free".equals(c.data.mode) && home != null && home.pos().getSquaredDistance(p.getBlockPos()) < 48 * 48) {
			return home.pos();
		}
		return p.getBlockPos();
	}

	/**
	 * Our own bed if it's still there and free, else the nearest unoccupied bed (head half)
	 * around home (free mode), the owner, or us.
	 */
	@Nullable
	private BlockPos findBed(CompanionPlayer p) {
		Memory.Place mine = c.memory().place("bed");
		if (mine != null && mine.pos().getSquaredDistance(p.getBlockPos()) < 64 * 64 && freeBedHead(p, mine.pos())) {
			return mine.pos();
		}
		ServerPlayerEntity owner = c.perception().owner();
		Memory.Place home = c.memory().place("home");
		BlockPos center = "free".equals(c.data.mode) && home != null && home.pos().getSquaredDistance(p.getBlockPos()) < 48 * 48 ? home.pos()
			: owner != null && owner.getWorld() == p.getWorld() ? owner.getBlockPos() : p.getBlockPos();
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

	private static boolean freeBedHead(CompanionPlayer p, BlockPos pos) {
		BlockState s = p.getWorld().getBlockState(pos);
		return s.isIn(BlockTags.BEDS) && s.get(BedBlock.PART) == BedPart.HEAD && !s.get(BedBlock.OCCUPIED);
	}
}

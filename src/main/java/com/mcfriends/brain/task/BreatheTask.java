package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Running out of air: drop everything and get to air. Heads for the nearest air at a water
 * surface (from {@link com.mcfriends.perception.Surroundings}), swimming up all the way; with no
 * air in sight it just swims straight up. Done once the head is out and the air bar is back.
 */
public class BreatheTask extends Task {
	@Nullable
	private BlockPos air;
	private int outTicks;

	public BreatheTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "breathe";
	}

	@Override
	public String describe() {
		return "coming up for air";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 30;
	}

	@Override
	protected void onStart(boolean resumed) {
		CompanionPlayer p = c.player();
		air = c.perception().surroundings().nearestAir(p.getBlockPos());
		if (air == null) {
			air = searchAir(p);
		}
		AiLog.log(c.name(), "out of air ({}), heading for {}", p.getAir(), air == null ? "the surface" : air.toShortString());
		if (air != null) {
			c.nav().moveTo(Goal.near(air, 1.0), true);
		} else {
			c.nav().stop();
		}
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (!p.isSubmergedInWater()) {
			// Head out: hold position a moment while the air bar refills.
			if (++outTicks > 20 && p.getAir() >= p.getMaxAir() - 20) {
				c.nav().stop();
				return Status.SUCCESS;
			}
			c.actions().holdJump(); // stay afloat
			return Status.RUNNING;
		}
		outTicks = 0;
		if (air != null && c.nav().state() != Navigator.State.FAILED) {
			// The navigator swims the path; keep kicking upward whenever the way is up.
			if (air.getY() >= p.getBlockPos().getY()) {
				c.actions().holdJump();
			}
			if (!c.nav().isBusy()) {
				c.nav().moveTo(Goal.near(air, 1.0), true);
			}
			return Status.RUNNING;
		}
		// No air spot known (or no path): straight up, steering toward the air if we know one.
		if (air != null) {
			c.actions().moveTo(Vec3d.ofBottomCenter(air));
		}
		c.actions().holdJump();
		if (phaseTicks() % 40 == 0) {
			air = c.perception().surroundings().nearestAir(p.getBlockPos());
			if (air == null) {
				air = searchAir(p);
			}
			if (air != null) {
				c.nav().moveTo(Goal.near(air, 1.0), true);
			}
		}
		return Status.RUNNING;
	}

	/**
	 * Nothing in the nearby picture (just arrived, or a long flooded tunnel): follow the water
	 * itself outward, up to about 24 blocks, to the nearest place with air.
	 */
	@Nullable
	private static BlockPos searchAir(CompanionPlayer p) {
		var world = p.getServerWorld();
		BlockPos start = BlockPos.ofFloored(p.getEyePos());
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty() && seen.size() < 6000) {
			BlockPos cur = queue.poll();
			for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
				BlockPos n = cur.offset(d);
				if (n.getSquaredDistance(start) > 24 * 24 || !seen.add(n)) {
					continue;
				}
				var state = world.getBlockState(n);
				if (state.isAir()) {
					return n; // breadth-first: the nearest air by swimming distance
				}
				if (state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER) && state.getCollisionShape(world, n).isEmpty()) {
					queue.add(n);
				}
			}
		}
		return null;
	}

	private int phaseTicks() {
		return ticks();
	}
}

package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Claims;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * "Nothing here, let's look somewhere else", the way a player would: head for a remembered spot
 * if there is one; if underground, get back to the surface first; otherwise walk a fair way in
 * one direction (away from where others are working), turning every other trip to sweep a wider
 * area. Used by tasks that look for things on the surface (trees, animals).
 */
final class SearchTrips {
	private final String what;
	private final int tripLength;
	private final int maxTrips;
	private int trips;
	@Nullable
	private Direction dir;
	@Nullable
	private BlockPos goal;
	private int ticks;
	private int quickFails;

	SearchTrips(String what, int tripLength, int maxTrips) {
		this.what = what;
		this.tripLength = tripLength;
		this.maxTrips = maxTrips;
	}

	boolean exhausted() {
		return trips >= maxTrips;
	}

	boolean travelling() {
		return goal != null;
	}

	int trips() {
		return trips;
	}

	void cancel(Companion c) {
		if (goal != null) {
			goal = null;
			c.nav().stop();
		}
	}

	/** Start the next trip, preferring {@code remembered} (a past sighting) when given. */
	void start(Companion c, @Nullable BlockPos remembered) {
		CompanionPlayer p = c.player();
		ServerWorld world = p.getServerWorld();
		trips++;
		ticks = 0;
		BlockPos here = p.getBlockPos();
		if (remembered != null) {
			goal = remembered;
			AiLog.log(c.name(), "{}: none in sight, going to a remembered spot {} (trip {}/{})", what, remembered.toShortString(), trips, maxTrips);
		} else if (underground(world, here)) {
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ());
			goal = new BlockPos(here.getX(), y, here.getZ());
			AiLog.log(c.name(), "{}: none down here, heading up to the surface (trip {}/{})", what, trips, maxTrips);
		} else {
			if (dir == null) {
				Direction best = Direction.Type.HORIZONTAL.random(p.getRandom());
				int bestScore = Integer.MAX_VALUE;
				for (Direction d : Direction.Type.HORIZONTAL) {
					int score = (Claims.crowded(c, here.offset(d, tripLength), tripLength * 0.75) ? 10 : 0) + p.getRandom().nextInt(3);
					if (score < bestScore) {
						bestScore = score;
						best = d;
					}
				}
				dir = best;
			} else if (trips % 2 == 1) {
				dir = dir.rotateYClockwise();
			}
			BlockPos ahead = here.offset(dir, tripLength);
			// Stay inside the world border: turn (or go less far) rather than walk into it.
			var border = world.getWorldBorder();
			for (int turn = 0; turn < 4 && !border.contains(ahead); turn++) {
				int len = tripLength;
				while (len > 8 && !border.contains(here.offset(dir, len))) {
					len -= 8;
				}
				ahead = here.offset(dir, len);
				if (!border.contains(ahead) || len <= 8) {
					dir = dir.rotateYClockwise();
					ahead = here.offset(dir, tripLength);
				}
			}
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
			goal = new BlockPos(ahead.getX(), y, ahead.getZ());
			AiLog.log(c.name(), "{}: none in sight, searching {} (trip {}/{})", what, dir, trips, maxTrips);
		}
		c.nav().moveTo(Goal.near(goal, 4), true);
	}

	/** Keep travelling. @return true while still on the way */
	boolean tick(Companion c) {
		if (goal == null) {
			return false;
		}
		CompanionPlayer p = c.player();
		ticks++;
		Navigator.State state = c.nav().state();
		boolean failed = state == Navigator.State.FAILED;
		if (state == Navigator.State.ARRIVED || failed || ticks > 20 * 60 || p.getBlockPos().getSquaredDistance(goal) < 25) {
			if (failed) {
				if (dir != null) {
					dir = dir.rotateYClockwise();
				}
				// A trip that couldn't even start doesn't use up the search (dead-end pocket, cliff...),
				// but don't spin forever on it either.
				if (ticks < 40 && quickFails++ < 4) {
					trips--;
				}
			}
			goal = null;
			c.nav().stop();
			return false;
		}
		if (!c.nav().isBusy()) {
			c.nav().moveTo(Goal.near(goal, 4), true);
		}
		return true;
	}

	static boolean underground(ServerWorld world, BlockPos here) {
		return here.getY() < world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ()) - 6;
	}
}

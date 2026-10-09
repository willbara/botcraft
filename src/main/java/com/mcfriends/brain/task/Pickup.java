package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Walks over to a dropped item so it gets picked up. Walks straight at it when that works;
 * if that stalls (a ledge, a 1-high gap, a hole) it paths there instead, which in dig mode may
 * clear the way. Gives up on an item after a while.
 */
final class Pickup {
	private static final int GIVE_UP_TICKS = 20 * 10;
	private static final int STALL_TICKS = 15;

	@Nullable
	private ItemEntity item;
	private int ticks;
	private int stall;
	private double best;
	private boolean pathing;
	private final java.util.Set<java.util.UUID> ignored = new java.util.HashSet<>();

	/** Items we gave up on (unreachable): don't pick them again. */
	boolean ignored(ItemEntity e) {
		return ignored.contains(e.getUuid());
	}

	/** @return true while still busy with this item */
	boolean tick(Companion c, ItemEntity target) {
		if (target != item) {
			item = target;
			ticks = 0;
			stall = 0;
			best = Double.MAX_VALUE;
			pathing = false;
		}
		CompanionPlayer p = c.player();
		if (!item.isAlive() || ++ticks > GIVE_UP_TICKS) {
			ignored.add(item.getUuid());
			c.nav().stop();
			return false;
		}
		// Aim for the middle of the item's block: drops in a hole under our feet are only picked
		// up once we actually step into the hole.
		Vec3d at = Vec3d.ofBottomCenter(item.getBlockPos());
		double d = p.getPos().squaredDistanceTo(at);
		if (d < best - 0.05) {
			best = d;
			stall = 0;
		} else if (++stall > STALL_TICKS) {
			pathing = true;
		}
		if (!pathing && d < 3.0 * 3.0) {
			c.actions().moveTo(at);
			if (p.horizontalCollision && p.isOnGround()) {
				c.actions().jump();
			}
		} else {
			c.nav().moveTo(Goal.near(item.getBlockPos(), pathing ? 0.5 : 1.0), false);
			if (c.nav().state() == Navigator.State.FAILED) {
				ignored.add(item.getUuid());
				c.nav().stop();
				return false;
			}
		}
		return true;
	}
}

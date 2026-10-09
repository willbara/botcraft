package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.nav.Terrain;
import com.mcfriends.util.AiLog;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Keep a comfortable distance from the owner. Paths (through doors, up steps) when the owner
 * gets too far, stands and glances at them otherwise. Teleports if the owner is very far or
 * in another dimension (configurable), like tamed pets do.
 */
public class FollowOwnerTask extends Task {
	private final Glances glances = new Glances();
	private boolean moving;
	private BlockPos goalCenter = BlockPos.ORIGIN;
	private int retryCooldown;

	public FollowOwnerTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "follow";
	}

	/** Who we follow: someone we were told to ("follow alex") if they're around, else the owner. */
	private ServerPlayerEntity leader() {
		if (c.data.followTarget != null) {
			ServerPlayerEntity t = com.mcfriends.chat.ChatRouter.findByName(c, c.data.followTarget);
			if (t != null && t != c.player() && t.isAlive()) {
				return t;
			}
		}
		return c.perception().owner();
	}

	@Override
	public String describe() {
		ServerPlayerEntity o = leader();
		return o == null ? "waiting for my owner" : "following " + o.getGameProfile().getName();
	}

	@Override
	protected int timeoutTicks() {
		return Integer.MAX_VALUE; // a standing order
	}

	@Override
	protected void onStart(boolean resumed) {
		moving = false;
		retryCooldown = 0;
		failures = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		ServerPlayerEntity owner = leader();
		if (owner == null) {
			glances.tick(c, null);
			return Status.RUNNING;
		}
		double comfy = CompanionConfig.get().followDistance * (c.perception().isNight() ? 0.75 : 1.0);
		double tp = CompanionConfig.get().teleportToOwnerDistance;
		double d = owner.getWorld() == p.getWorld() ? owner.distanceTo(p) : Double.POSITIVE_INFINITY;
		if (tp > 0 && (d > tp || owner.getWorld() != p.getWorld())) {
			teleportNear(owner);
			return Status.RUNNING;
		}

		// Being close isn't enough if a wall or closed door is between us.
		boolean visible = p.canSee(owner);
		if (!moving && (d > comfy + 1.5 || (!visible && d > 2.0))) {
			moving = true;
		}
		if (moving) {
			// Settle only on solid ground at about the owner's height (not hanging off a ladder).
			boolean settled = (p.isOnGround() || p.isTouchingWater()) && Math.abs(owner.getY() - p.getY()) < 1.5;
			if (d <= comfy && visible && settled) {
				moving = false;
				c.nav().stop();
			} else {
				move(owner, comfy, d);
			}
		}
		if (!moving) {
			glances.tick(c, owner);
		}
		return Status.RUNNING;
	}

	/** Failed attempts in a row to path to the owner. */
	private int failures;

	private void move(ServerPlayerEntity owner, double comfy, double d) {
		BlockPos ownerPos = owner.getBlockPos();
		if (retryCooldown > 0) {
			retryCooldown--;
			// While waiting to retry (e.g. a door we can't open), face the owner.
			c.actions().lookAt(owner);
			return;
		}
		boolean ownerMoved = ownerPos.getSquaredDistance(goalCenter) > 2.5 * 2.5;
		if (ownerMoved || !c.nav().isBusy()) {
			if (c.nav().state() == Navigator.State.ARRIVED && !ownerMoved) {
				// Already as close as a path gets us (owner on a ledge, out of sight): don't
				// re-plan every tick, just look again in a moment.
				retryCooldown = 20;
				c.nav().stop();
				return;
			}
			if (c.nav().state() == Navigator.State.FAILED && !ownerMoved) {
				AiLog.log(c.name(), "can't reach owner ({}), waiting", c.nav().failReason());
				retryCooldown = 40;
				c.nav().stop();
				// Stuck somewhere (deep in a cave, a pit) and it keeps failing: come back the way
				// we do when far away, rather than wait there forever.
				if (++failures >= 3 && d > 12 && CompanionConfig.get().teleportToOwnerDistance > 0) {
					AiLog.log(c.name(), "can't find a way back to owner, teleporting");
					failures = 0;
					teleportNear(owner);
				}
				return;
			}
			if (c.nav().state() == Navigator.State.MOVING) {
				failures = 0;
			}
			goalCenter = ownerPos;
			c.nav().moveTo(Goal.near(ownerPos, Math.max(1.5, comfy - 1.0)), d > 8);
		}
	}

	private void teleportNear(ServerPlayerEntity owner) {
		Terrain t = new Terrain(owner.getServerWorld());
		BlockPos o = owner.getBlockPos();
		for (int r = 1; r <= 3; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos cand = o.add(dx, dy, dz);
						if (t.standable(cand) && !t.isWater(cand) && t.hazard(cand) == 0) {
							Vec3d at = new Vec3d(cand.getX() + 0.5, t.floorY(cand), cand.getZ() + 0.5);
							AiLog.log(c.name(), "teleporting to owner at {}", cand.toShortString());
							c.nav().stop();
							c.player().teleport(owner.getServerWorld(), at.x, at.y, at.z, owner.getYaw(), 0.0F);
							c.player().fallDistance = 0;
							return;
						}
					}
				}
			}
		}
	}
}

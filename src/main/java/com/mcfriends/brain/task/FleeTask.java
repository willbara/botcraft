package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.nav.Terrain;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Run away from threats (sprinting). With {@code retreat} (low health) we prefer running to the
 * owner. Ends once nothing hostile is within the safe distance.
 */
public class FleeTask extends Task {
	public static final double SAFE_DISTANCE = 12.0;
	private final boolean retreat;
	private int replanIn;

	public FleeTask(Companion c, boolean retreat) {
		super(c);
		this.retreat = retreat;
	}

	@Override
	public String name() {
		return retreat ? "retreat" : "flee";
	}

	@Override
	public String describe() {
		return retreat ? "falling back to heal up" : "getting away from monsters";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 25;
	}

	@Override
	protected void onStart(boolean resumed) {
		replanIn = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		List<MobEntity> threats = c.perception().threats();
		MobEntity nearest = threats.isEmpty() ? null : threats.get(0);
		if (nearest == null || nearest.distanceTo(p) > SAFE_DISTANCE) {
			return Status.SUCCESS;
		}
		Vec3d away = Vec3d.ZERO;
		for (MobEntity m : threats) {
			Vec3d v = p.getPos().subtract(m.getPos());
			double len = Math.max(0.5, v.length());
			away = away.add(v.multiply(1.0 / (len * len)));
		}
		away = new Vec3d(away.x, 0, away.z);
		ServerPlayerEntity owner = c.perception().owner();
		if (owner != null && owner.getWorld() == p.getWorld() && (retreat || owner.distanceTo(p) > 4)) {
			Vec3d toOwner = owner.getPos().subtract(p.getPos());
			toOwner = new Vec3d(toOwner.x, 0, toOwner.z);
			// Head for the owner unless that means running through the monsters.
			if (toOwner.lengthSquared() > 1 && toOwner.normalize().dotProduct(away.normalize()) > -0.3) {
				away = away.normalize().add(toOwner.normalize().multiply(retreat ? 1.5 : 0.7));
			}
		}
		if (away.lengthSquared() < 1e-4) {
			away = Vec3d.fromPolar(0, p.getYaw() + 180);
		}
		away = away.normalize();

		if (--replanIn <= 0 || c.nav().state() == Navigator.State.ARRIVED || c.nav().state() == Navigator.State.FAILED) {
			replanIn = 30;
			BlockPos spot = findSpot(p, away);
			if (spot != null) {
				c.nav().moveTo(Goal.near(spot, 2.0), true);
			} else {
				c.nav().stop();
			}
		}
		if (!c.nav().isBusy()) {
			// No path: just run - but never off a cliff in the dark; turn aside instead.
			Terrain t = new Terrain(p.getServerWorld());
			for (int turn = 0; turn < 4 && !safeStep(t, p.getBlockPos(), away); turn++) {
				away = rotate(away, Math.PI / 2);
			}
			c.actions().moveTo(p.getPos().add(away.multiply(3)));
			c.actions().sprint(true);
			if (p.horizontalCollision && p.isOnGround()) {
				c.actions().jump();
			}
		}
		if (p.age % 20 == 0) {
			AiLog.log(c.name(), "{}: {} at {}", name(), nearest.getType().getUntranslatedName(), String.format("%.1f", nearest.distanceTo(p)));
		}
		return Status.RUNNING;
	}

	@Nullable
	private BlockPos findSpot(CompanionPlayer p, Vec3d dir) {
		Terrain t = new Terrain(p.getServerWorld());
		for (double dist = 12; dist >= 5; dist -= 3.5) {
			for (int turn = 0; turn < 5; turn++) {
				double angle = (turn % 2 == 0 ? 1 : -1) * ((turn + 1) / 2) * 0.5;
				Vec3d d = rotate(dir, angle).multiply(dist);
				BlockPos base = BlockPos.ofFloored(p.getX() + d.x, p.getY(), p.getZ() + d.z);
				for (int dy = 0; dy <= 6; dy++) {
					for (int sign : new int[]{1, -1}) {
						BlockPos cand = base.up(dy * sign);
						if (t.standable(cand) && !t.isWater(cand) && t.hazard(cand) == 0) {
							return cand;
						}
					}
				}
			}
		}
		return null;
	}

	/** The next block that way has something to stand on within a safe fall. */
	private static boolean safeStep(Terrain t, BlockPos from, Vec3d dir) {
		BlockPos next = BlockPos.ofFloored(from.getX() + 0.5 + dir.x * 1.2, from.getY(), from.getZ() + 0.5 + dir.z * 1.2);
		for (int k = 0; k <= 3; k++) {
			if (t.standable(next.down(k)) && !t.isWater(next.down(k))) {
				return true;
			}
			if (!t.passable(next.down(k))) {
				return k == 0 && t.standable(next.up()); // a step up is fine
			}
		}
		return false;
	}

	private static Vec3d rotate(Vec3d v, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3d(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
	}
}

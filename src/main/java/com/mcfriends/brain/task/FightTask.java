package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Random;

/**
 * Melee a hostile mob with the best weapon: close in (direct when near and visible, pathing
 * otherwise), hit when the cooldown is ready, and step back a little after each hit so we
 * don't eat every counter-attack. Switches to the next threat when the target dies.
 */
public class FightTask extends Task {
	private static final double GIVE_UP_DISTANCE = 24.0;
	private final Random random = new Random();
	private MobEntity target;
	private BlockPos goalCenter = BlockPos.ORIGIN;
	private int backOff;
	private int strafeDir = 1;
	private int kills;

	public FightTask(Companion c, MobEntity target) {
		super(c);
		this.target = target;
	}

	@Override
	public String name() {
		return "fight";
	}

	@Override
	public String describe() {
		return "fighting " + (target == null ? "monsters" : target.getDisplayName().getString().toLowerCase());
	}

	public int kills() {
		return kills;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 45;
	}

	@Override
	protected void onStart(boolean resumed) {
		double dmg = Inventories.equipBestWeapon(c.player());
		AiLog.log(c.name(), "fight {} with {} (damage {})", target.getType().getUntranslatedName(),
			c.player().getMainHandStack().getName().getString(), dmg);
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (!target.isAlive() || target.isRemoved()) {
			if (target.isDead()) {
				kills++;
				c.onKill(target);
			}
			List<MobEntity> next = c.perception().threats();
			if (next.isEmpty() && c.data.defendOwner) {
				next = c.perception().ownerThreats();
			}
			if (next.isEmpty()) {
				return Status.SUCCESS;
			}
			target = next.get(0);
		}
		double d = p.distanceTo(target);
		if (d > GIVE_UP_DISTANCE || target.getWorld() != p.getWorld()) {
			return Status.SUCCESS; // it ran off; good enough
		}
		c.actions().lookAt(target);

		if (backOff > 0) {
			backOff--;
			Vec3d away = p.getPos().subtract(target.getPos()).normalize();
			Vec3d side = new Vec3d(-away.z, 0, away.x).multiply(strafeDir * 0.6);
			c.actions().moveTo(p.getPos().add(away).add(side), 0.8);
			return Status.RUNNING;
		}

		boolean visible = p.canSee(target);
		if (d > ActionController.ATTACK_REACH - 0.4) {
			if (visible && d < 7 && Math.abs(target.getY() - p.getY()) < 1.5) {
				c.nav().stop();
				c.actions().moveTo(target.getPos());
				c.actions().sprint(d > 3.5);
				if (p.horizontalCollision && p.isOnGround()) {
					c.actions().jump();
				}
			} else {
				BlockPos tp = target.getBlockPos();
				if (!c.nav().isBusy() || tp.getSquaredDistance(goalCenter) > 4) {
					goalCenter = tp;
					c.nav().moveTo(Goal.near(tp, 1.5), true);
				}
			}
		} else {
			c.nav().stop();
		}
		if (c.actions().attack(target)) {
			// Step back after a hit (most mobs get knocked back too), strafing a bit.
			backOff = 4 + random.nextInt(5);
			strafeDir = random.nextBoolean() ? 1 : -1;
		}
		return Status.RUNNING;
	}
}

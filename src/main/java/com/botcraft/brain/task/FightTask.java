package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.util.AiLog;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
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
	/** Usually a hostile mob; can be a player (the popbob easter egg). */
	private LivingEntity target;
	private BlockPos goalCenter = BlockPos.ORIGIN;
	private int backOff;
	private int strafeDir = 1;
	private int kills;
	/** Fighting a player because they're an enemy (stop when they aren't any more). */
	private boolean pvp;
	/** Jumped for a critical hit: strike while falling, for this many more ticks. */
	private int critTicks;
	/** How often it goes for a critical hit when it can (set from aggression). */
	private float critChance = 0.4F;
	/** Holding the shield up. */
	private boolean blocking;

	public FightTask(Companion c, LivingEntity target) {
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

	/**
	 * Raise the shield while something we can block is coming (a skeleton drawing its bow, a
	 * creeper hissing, a player drawing a bow), lower it otherwise.
	 *
	 * @return true while blocking (the tick is spent behind the shield)
	 */
	private boolean shieldUp(CompanionPlayer p, double d) {
		boolean shield = p.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD) && com.botcraft.util.Experiments.on("shield");
		boolean creeper = target instanceof CreeperEntity cr && cr.getFuseSpeed() > 0 && d < 5.0;
		boolean incoming = creeper
			|| (target instanceof net.minecraft.entity.mob.AbstractSkeletonEntity sk && sk.isUsingItem() && d > 3.0 && p.canSee(target))
			|| (target instanceof net.minecraft.entity.player.PlayerEntity pl && pl.isUsingItem()
				&& pl.getActiveItem().getItem() instanceof net.minecraft.item.RangedWeaponItem && d > 3.0);
		if (shield && incoming) {
			if (!p.isUsingItem()) {
				p.interactionManager.interactItem(p, p.getWorld(), p.getOffHandStack(), net.minecraft.util.Hand.OFF_HAND);
			}
			blocking = true;
			c.nav().stop();
			if (creeper) {
				Vec3d away = p.getPos().subtract(target.getPos()).normalize();
				c.actions().moveTo(p.getPos().add(away.multiply(2)), 0.5); // back off behind the shield
			} else {
				c.actions().moveTo(target.getPos(), 0.6); // close in behind it
			}
			return true;
		}
		if (blocking) {
			blocking = false;
			if (p.isUsingItem() && p.getActiveHand() == net.minecraft.util.Hand.OFF_HAND) {
				p.stopUsingItem();
			}
		}
		return false;
	}

	@Override
	protected void onStop() {
		blocking = false;
		critTicks = 0;
		super.onStop();
	}

	@Override
	protected void onStart(boolean resumed) {
		critChance = 0.25F + 0.5F * c.brain().personality().aggression();
		pvp = target instanceof net.minecraft.server.network.ServerPlayerEntity sp && com.botcraft.faction.Hostility.isEnemy(c, sp);
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
			LivingEntity nextTarget = next.isEmpty() ? null : next.get(0);
			if (nextTarget == null && !c.perception().enemyPlayers().isEmpty()) {
				nextTarget = c.perception().enemyPlayers().get(0);
			}
			if (nextTarget == null) {
				return Status.SUCCESS;
			}
			target = nextTarget;
			pvp = target instanceof net.minecraft.server.network.ServerPlayerEntity sp && com.botcraft.faction.Hostility.isEnemy(c, sp);
		}
		// A player we were fighting isn't an enemy any more (made up, or left our base in defend mode).
		if (pvp && ticks() % 20 == 0 && target instanceof net.minecraft.server.network.ServerPlayerEntity sp
			&& !com.botcraft.faction.Hostility.isEnemy(c, sp)) {
			return Status.SUCCESS;
		}
		double d = p.distanceTo(target);
		if (d > GIVE_UP_DISTANCE || target.getWorld() != p.getWorld()) {
			return Status.SUCCESS; // it ran off; good enough
		}
		c.actions().lookAt(target);

		// Shield up against an arrow being drawn or a creeper about to blow (if we carry one),
		// walking in behind it; down again to hit back.
		if (shieldUp(p, d)) {
			return Status.RUNNING;
		}

		// A hissing creeper: get out of blast range, then come back and hit it again.
		if (target instanceof CreeperEntity creeper && creeper.getFuseSpeed() > 0 && d < 5.0) {
			Vec3d away = p.getPos().subtract(target.getPos()).normalize();
			c.nav().stop();
			c.actions().moveTo(p.getPos().add(away.multiply(3)));
			c.actions().sprint(true);
			return Status.RUNNING;
		}

		if (backOff > 0) {
			backOff--;
			Vec3d away = p.getPos().subtract(target.getPos()).normalize();
			Vec3d side = new Vec3d(-away.z, 0, away.x).multiply(strafeDir * 0.6);
			c.actions().moveTo(p.getPos().add(away).add(side), 0.8);
			return Status.RUNNING;
		}

		// A critical hit lands on the way down from a jump.
		if (critTicks > 0) {
			critTicks--;
			if (!p.isOnGround() && p.fallDistance > 0.0F && c.actions().attack(target)) {
				critTicks = 0;
				backOff = 4 + random.nextInt(5);
				strafeDir = random.nextBoolean() ? 1 : -1;
			}
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
			// In reach with the swing nearly charged: some go for a critical hit (more so the aggressive ones).
			if (com.botcraft.util.Experiments.on("crits") && p.isOnGround() && !p.isTouchingWater() && !p.isClimbing()
				&& p.getAttackCooldownProgress(0.5F) > 0.75F && random.nextFloat() < critChance) {
				c.actions().jump();
				critTicks = 12;
				return Status.RUNNING;
			}
		}
		if (c.actions().attack(target)) {
			// Step back after a hit (most mobs get knocked back too), strafing a bit.
			backOff = 4 + random.nextInt(5);
			strafeDir = random.nextBoolean() ? 1 : -1;
		}
		return Status.RUNNING;
	}
}

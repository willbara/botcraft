package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.perception.Perception;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;

import java.util.List;

/** The drive set. Scores are tuned so that interrupts (danger, starving) beat everything else. */
public final class Drives {
	private Drives() {
	}

	public static List<Drive> all() {
		return List.of(Drives::breath, Drives::safety, Drives::health, Drives::hunger, Drives::sleep, Drives::owner, Drives::night, Drives::work, Drives::idle);
	}

	/** Head under water and the air running out: get to air before anything else. */
	static void breath(Companion c, Brain b, Scores out) {
		var p = c.player();
		if (p != null && p.isInLava() && com.botcraft.util.Experiments.on("lavaEscape")) {
			out.propose(Objective.BREATHE, 0.99F, "in lava");
			return;
		}
		if (p == null || !p.isSubmergedInWater()) {
			// Keep breathing until the air bar is back, or we'd dive straight back in.
			if (p != null && b.currentObjective() == Objective.BREATHE && p.getAir() < p.getMaxAir() - 20) {
				out.propose(Objective.BREATHE, 0.9F, "getting my breath back");
			}
			return;
		}
		int air = p.getAir();
		int max = p.getMaxAir();
		if (air < max / 2) {
			out.propose(Objective.BREATHE, air < max / 4 ? 0.99F : 0.9F, "air " + air + "/" + max);
		}
	}

	/** Melee damage at which we consider ourselves armed (wooden/gold sword = 4, any axe more). */
	public static final double ARMED_DAMAGE = 4.0;

	/**
	 * Hostile mobs. Armed (a sword, axe, or similar) means fight whatever it is, creepers
	 * included (FightTask backs off while one is hissing). Unarmed means survival first: run
	 * from anything close or coming for us. Personality only flavours what they say.
	 */
	static void safety(Companion c, Brain b, Scores out) {
		Perception per = c.perception();
		CompanionPlayer p = c.player();
		List<MobEntity> threats = per.threats();
		List<MobEntity> ownerThreats = c.data.defendOwner ? per.ownerThreats() : List.of();
		if (threats.isEmpty() && ownerThreats.isEmpty()) {
			// Players we're at odds with (enemy factions, someone who attacked us or our base).
			List<net.minecraft.server.network.ServerPlayerEntity> enemies = per.enemyPlayers();
			if (!enemies.isEmpty()) {
				var enemy = enemies.get(0);
				double d = enemy.distanceTo(p);
				boolean armed = per.inventory().bestWeapon() >= ARMED_DAMAGE;
				float hp = p.getHealth() / p.getMaxHealth();
				if ((armed && hp >= b.personality().retreatHealth()) || d < 3) {
					float aggro = 0.75F + b.personality().aggression() * 0.1F;
					out.propose(Objective.FIGHT, d < 8 ? Math.max(0.85F, aggro) : aggro,
						String.format("%s %.1f away, %s", enemy.getGameProfile().getName(), d, armed ? "enemy" : "too close"));
				}
				return;
			}
			// The popbob easter egg: armed companions take it on; anyone it's hitting hits back.
			var popbob = Popbob.near(c, 12);
			if (popbob != null && !Popbob.is(c)) {
				double d = popbob.distanceTo(p);
				boolean armed = per.inventory().bestWeapon() >= ARMED_DAMAGE;
				if (armed || d < 4) {
					out.propose(Objective.FIGHT, d < 6 ? 0.9F : 0.75F, String.format("popbob %.1f away", d));
				}
			}
			return;
		}
		boolean armed = per.inventory().bestWeapon() >= ARMED_DAMAGE;
		MobEntity nearest = !threats.isEmpty() ? threats.get(0) : ownerThreats.get(0);
		double d = nearest.distanceTo(p);
		String what = nearest.getType().getUntranslatedName();
		CreeperEntity creeper = per.nearestCreeper(7.0);
		if (creeper != null && creeper.getFuseSpeed() > 0 && creeper.distanceTo(p) < 5.0) {
			out.propose(Objective.FLEE, 0.97F, "creeper about to blow");
			return;
		}
		float hp = p.getHealth() / p.getMaxHealth();
		boolean weak = hp < b.personality().retreatHealth() + c.mood().get(Mood.Kind.FEAR) * 0.05F;
		if (armed && weak && d > 2.5 && com.botcraft.util.Experiments.on("lowHealthCaution")) {
			return; // too hurt to go looking for a fight; only hit back when cornered (the health drive retreats)
		}
		if (armed) {
			float urgency = d < 6 ? 0.9F : d < 10 ? 0.8F : 0.65F;
			String why = threats.isEmpty() ? "defending owner from " + what : String.format("%s %.1f away, armed", what, d);
			out.propose(Objective.FIGHT, threats.isEmpty() ? 0.75F : urgency, why);
			return;
		}
		if (threats.isEmpty()) {
			return; // unarmed: can't help the owner, but nothing is after us
		}
		// Same distance FleeTask considers safe, so we don't start fleeing from something it would ignore.
		boolean coming = d < com.botcraft.brain.task.FleeTask.SAFE_DISTANCE && (nearest.getTarget() == p || d < 10);
		if (creeper != null || coming) {
			out.propose(Objective.FLEE, d < 6 ? 0.9F : 0.8F, String.format("%s %.1f away, no weapon", what, d));
		}
	}

	/**
	 * Low health with enemies around: retreat (toward the owner if possible). Brave companions
	 * hold on a little longer.
	 */
	static void health(Companion c, Brain b, Scores out) {
		CompanionPlayer p = c.player();
		float hp = p.getHealth() / p.getMaxHealth();
		float threshold = b.personality().retreatHealth() + c.mood().get(Mood.Kind.FEAR) * 0.05F;
		// Only for a threat inside the distance a retreat counts as done at; otherwise a mob
		// hovering just past it starts a retreat that ends at once, over and over.
		MobEntity near = c.perception().nearestThreat();
		if (hp < threshold && near != null && near.distanceTo(p) < com.botcraft.brain.task.FleeTask.SAFE_DISTANCE - 1) {
			out.propose(Objective.RETREAT, 0.93F, String.format("health %.0f%%", hp * 100));
			return;
		}
		// Something shooting at us from further off: walking back into its arrows to fight it at
		// this health is how retreats used to end. Get out of its sight instead.
		if (hp < threshold && com.botcraft.util.Experiments.on("lowHealthCaution")) {
			for (MobEntity m : c.perception().threats()) {
				if (com.botcraft.brain.task.FleeTask.ranged(m) && com.botcraft.brain.task.FleeTask.dangerous(m, p)) {
					out.propose(Objective.RETREAT, 0.93F, String.format("health %.0f%%, %s shooting", hp * 100, m.getType().getUntranslatedName()));
					return;
				}
			}
		}
	}

	/** Eat when hungry, or to regenerate when hurt (natural regen needs a full-ish food bar). */
	static void hunger(Companion c, Brain b, Scores out) {
		CompanionPlayer p = c.player();
		int food = p.getHungerManager().getFoodLevel();
		if (!p.canConsume(false)) {
			return;
		}
		boolean starving = food <= 6;
		if (Inventories.bestFood(p.getInventory(), starving) < 0) {
			return;
		}
		float s = 0;
		if (starving) {
			s = 0.92F;
		} else if (food < 14) {
			s = 0.55F + (14 - food) * 0.03F;
		}
		float hp = p.getHealth() / p.getMaxHealth();
		if (hp < 0.5F && food < 20) {
			s = Math.max(s, 0.7F);
		}
		MobEntity t = c.perception().nearestThreat();
		if (t != null && t.distanceTo(p) < 5 && !starving) {
			s = Math.min(s, 0.5F); // deal with the zombie first
		}
		if (s > 0) {
			out.propose(Objective.EAT, s, "food " + food + ", health " + Math.round(p.getHealth()));
		}
	}

	/** Go to bed when the owner does, so the night can be skipped (everyone must sleep). */
	static void sleep(Companion c, Brain b, Scores out) {
		if (!CompanionConfig.get().sleepWithOwner || !c.perception().isNight() || c.now() < c.sleepRetryAfter) {
			return;
		}
		var owner = c.perception().owner();
		if (owner != null && owner.isSleeping() && c.perception().threats().isEmpty() && c.perception().ownerDistance() < 48) {
			out.propose(Objective.SLEEP, 0.8F, "owner is sleeping");
		}
	}

	/** Stay near the owner (follow mode). */
	static void owner(Companion c, Brain b, Scores out) {
		if (!"follow".equals(c.data.mode) || c.perception().owner() == null) {
			return;
		}
		double d = c.perception().ownerDistance();
		float helpful = b.personality().helpfulness();
		if (b.hasJob()) {
			// Busy with a job: let it finish, however far it takes us (hunting trips, caves).
			// Being pulled back mid-job meant a teleport to the owner and a walk back out, over and over.
			return;
		}
		double comfy = CompanionConfig.get().followDistance;
		float s = d <= comfy ? 0.3F : (float) Math.min(0.85, 0.35 + (d - comfy) * 0.04);
		out.propose(Objective.FOLLOW, s + helpful * 0.05F, String.format("owner %.1f away", d));
	}

	/** At night: stick closer to the owner, or hold still if alone. Also tempers work. */
	static void night(Companion c, Brain b, Scores out) {
		if (!c.perception().isNight()) {
			return;
		}
		float brave = b.personality().bravery();
		if (c.perception().owner() != null && "follow".equals(c.data.mode)) {
			// A brave companion keeps working through the night; a timid one comes back.
			out.propose(Objective.FOLLOW, 0.45F + (1.0F - brave) * 0.2F, "night, staying close");
		} else if (!b.hasJob()) {
			out.propose(Objective.STAY, 0.35F, "night, alone");
		}
	}

	/** An assigned job (gather, come here). */
	static void work(Companion c, Brain b, Scores out) {
		if (b.hasJob()) {
			out.propose(Objective.WORK, 0.5F + b.personality().helpfulness() * 0.1F, "job: " + b.jobName());
		}
	}

	/** Boredom / idling and the explicit stay mode. */
	static void idle(Companion c, Brain b, Scores out) {
		if ("stay".equals(c.data.mode)) {
			out.propose(Objective.STAY, 0.3F, "told to stay");
		}
		// Free mode: idling means pottering about near home (Life queues real activities).
		out.propose(Objective.IDLE, 0.1F + b.personality().curiosity() * 0.05F, "nothing to do");
	}
}

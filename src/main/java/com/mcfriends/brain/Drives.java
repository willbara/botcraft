package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.perception.Perception;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;

import java.util.List;

/** The drive set. Scores are tuned so that interrupts (danger, starving) beat everything else. */
public final class Drives {
	private Drives() {
	}

	public static List<Drive> all() {
		return List.of(Drives::safety, Drives::health, Drives::hunger, Drives::owner, Drives::night, Drives::work, Drives::idle);
	}

	/** Hostile mobs: fight (brave, armed, healthy, few enemies) or flee. Creepers are always fled. */
	static void safety(Companion c, Brain b, Scores out) {
		Perception per = c.perception();
		CompanionPlayer p = c.player();
		List<MobEntity> threats = per.threats();
		List<MobEntity> ownerThreats = c.data.defendOwner ? per.ownerThreats() : List.of();
		if (threats.isEmpty() && ownerThreats.isEmpty()) {
			return;
		}
		CreeperEntity creeper = per.nearestCreeper(7.0);
		if (creeper != null) {
			out.propose(Objective.FLEE, 0.95F, "creeper " + String.format("%.1f", creeper.distanceTo(p)) + " away");
			return;
		}
		MobEntity nearest = !threats.isEmpty() ? threats.get(0) : ownerThreats.get(0);
		double d = nearest.distanceTo(p);
		float urgency = d < 6 ? 0.9F : d < 10 ? 0.8F : 0.6F;
		Personality pers = b.personality();
		float hp = p.getHealth() / p.getMaxHealth();
		double weapon = per.inventory().bestWeapon();
		int count = threats.size();
		float fight = pers.bravery() * 0.5F + (float) Math.min(weapon, 8.0) / 8.0F * 0.3F + hp * 0.3F - (count - 1) * 0.12F;
		if (c.data.defendOwner) {
			fight += 0.25F;
		}
		if (threats.isEmpty()) {
			// Only the owner is threatened: go help (defend mode).
			out.propose(Objective.FIGHT, 0.75F, "defending owner from " + nearest.getType().getUntranslatedName());
			return;
		}
		String what = nearest.getType().getUntranslatedName();
		if (fight >= 0.45F && hp > 0.35F) {
			out.propose(Objective.FIGHT, urgency, String.format("%s %.1f away, fight score %.2f", what, d, fight));
		} else {
			out.propose(Objective.FLEE, urgency, String.format("%s %.1f away, fight score %.2f", what, d, fight));
		}
	}

	/** Low health with enemies around: retreat (toward the owner if possible). */
	static void health(Companion c, Brain b, Scores out) {
		CompanionPlayer p = c.player();
		float hp = p.getHealth() / p.getMaxHealth();
		if (hp < 0.35F && !c.perception().threats().isEmpty()) {
			out.propose(Objective.RETREAT, 0.93F, String.format("health %.0f%%", hp * 100));
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

	/** Stay near the owner (follow mode). */
	static void owner(Companion c, Brain b, Scores out) {
		if (!"follow".equals(c.data.mode) || c.perception().owner() == null) {
			return;
		}
		double d = c.perception().ownerDistance();
		float helpful = b.personality().helpfulness();
		if (b.hasJob()) {
			// Busy with a job: only drop it if the owner wandered really far away.
			if (d > 40) {
				out.propose(Objective.FOLLOW, 0.6F, "owner far while working");
			}
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
		out.propose(Objective.IDLE, 0.1F + b.personality().curiosity() * 0.05F, "nothing to do");
	}
}

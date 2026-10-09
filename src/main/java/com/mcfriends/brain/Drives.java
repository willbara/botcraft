package com.mcfriends.brain;

import com.mcfriends.config.CompanionConfig;

import java.util.List;

/** The drive set. Scores are tuned so that interrupts (danger, starving) beat everything else. */
public final class Drives {
	private Drives() {
	}

	public static List<Drive> all() {
		return List.of(Drives::owner, Drives::night, Drives::work, Drives::idle);
	}

	/** Stay near the owner (follow mode). */
	static void owner(com.mcfriends.Companion c, Brain b, Scores out) {
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
	static void night(com.mcfriends.Companion c, Brain b, Scores out) {
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
	static void work(com.mcfriends.Companion c, Brain b, Scores out) {
		if (b.hasJob()) {
			out.propose(Objective.WORK, 0.5F + b.personality().helpfulness() * 0.1F, "job: " + b.jobName());
		}
	}

	/** Boredom / idling and the explicit stay mode. */
	static void idle(com.mcfriends.Companion c, Brain b, Scores out) {
		if ("stay".equals(c.data.mode)) {
			out.propose(Objective.STAY, 0.3F, "told to stay");
		}
		out.propose(Objective.IDLE, 0.1F + b.personality().curiosity() * 0.05F, "nothing to do");
	}
}

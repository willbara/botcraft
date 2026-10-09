package com.mcfriends.brain;

import com.mcfriends.entity.CompanionData;

/**
 * Personality traits in [0, 1]. They bias drive scores (bravery: fight vs flee, helpfulness:
 * eagerness to follow / work, curiosity: idle wandering) and dialogue (chattiness: how often
 * the companion speaks unprompted, plus template choice).
 */
public record Personality(float chattiness, float bravery, float curiosity, float helpfulness) {
	public static Personality of(CompanionData d) {
		return new Personality(d.chattiness, d.bravery, d.curiosity, d.helpfulness);
	}

	public String summary() {
		return String.format("chatty %.1f, brave %.1f, curious %.1f, helpful %.1f", chattiness, bravery, curiosity, helpfulness);
	}
}

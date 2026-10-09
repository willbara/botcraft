package com.mcfriends.brain;

import java.util.EnumMap;
import java.util.Map;

/** Utility per objective for one think() pass. Drives propose; the max per objective wins. */
public class Scores {
	private final EnumMap<Objective, Float> scores = new EnumMap<>(Objective.class);
	private final EnumMap<Objective, String> reasons = new EnumMap<>(Objective.class);

	public void propose(Objective o, float score, String reason) {
		Float cur = scores.get(o);
		if (cur == null || score > cur) {
			scores.put(o, Math.max(0.0F, Math.min(1.0F, score)));
			reasons.put(o, reason);
		}
	}

	public float get(Objective o) {
		return scores.getOrDefault(o, 0.0F);
	}

	public String reason(Objective o) {
		return reasons.getOrDefault(o, "-");
	}

	public Objective best(Objective prefer) {
		Objective best = prefer;
		float bestScore = prefer == null ? -1 : get(prefer);
		for (Map.Entry<Objective, Float> e : scores.entrySet()) {
			if (e.getValue() > bestScore) {
				best = e.getKey();
				bestScore = e.getValue();
			}
		}
		return best;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Objective, Float> e : scores.entrySet()) {
			if (e.getValue() > 0.01F) {
				sb.append(e.getKey()).append('=').append(String.format("%.2f", e.getValue())).append(' ');
			}
		}
		return sb.toString().trim();
	}
}

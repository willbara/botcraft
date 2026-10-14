package com.botcraft.brain;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Short-lived feelings that colour what a companion does and says. Each is 0..1 and fades
 * toward 0 with its own half-life; events push them up. Not saved: a restart is a fresh start.
 * Pure Java (time is passed in), so it can be unit tested.
 */
public final class Mood {
	public enum Kind {
		/** Damage, enemies around. Fades fast. */
		FEAR(20 * 30),
		/** Goals done, gifts, praise, kills. */
		JOY(20 * 60 * 5),
		/** Failures, getting stuck, refusals. */
		FRUSTRATION(20 * 60 * 3),
		/** Long work without rest; sleep clears it. */
		FATIGUE(20 * 60 * 20),
		/** Doing the same thing over and over. */
		BOREDOM(20 * 60 * 6),
		/** A friend died nearby. */
		GRIEF(20 * 60 * 10),
		/** Someone hit us, stole from us, insulted us. */
		ANGER(20 * 60 * 2);

		/** Ticks for the value to halve. */
		public final int halfLife;

		Kind(int halfLife) {
			this.halfLife = halfLife;
		}
	}

	private final EnumMap<Kind, Float> values = new EnumMap<>(Kind.class);
	private long updatedAt = Long.MIN_VALUE;

	/** Fade everything to {@code now}. */
	public void update(long now) {
		if (updatedAt == Long.MIN_VALUE || now <= updatedAt) {
			updatedAt = Math.max(now, updatedAt == Long.MIN_VALUE ? now : updatedAt);
			return;
		}
		long dt = now - updatedAt;
		updatedAt = now;
		for (Map.Entry<Kind, Float> e : values.entrySet()) {
			double factor = Math.pow(0.5, dt / (double) e.getKey().halfLife);
			float v = (float) (e.getValue() * factor);
			e.setValue(v < 0.01F ? 0.0F : v);
		}
	}

	/** Push a feeling up (or down, with a negative amount), as of {@code now}. */
	public void bump(Kind kind, float amount, long now) {
		update(now);
		float v = values.getOrDefault(kind, 0.0F) + amount;
		values.put(kind, Math.max(0.0F, Math.min(1.0F, v)));
	}

	public float get(Kind kind) {
		return values.getOrDefault(kind, 0.0F);
	}

	/** Clear one feeling (e.g. fatigue after sleeping). */
	public void reset(Kind kind) {
		values.remove(kind);
	}

	/** The strongest feeling above {@code threshold}, or null if it's a calm moment. */
	public Kind dominant(float threshold) {
		Kind best = null;
		float bestV = threshold;
		for (Map.Entry<Kind, Float> e : values.entrySet()) {
			if (e.getValue() > bestV) {
				bestV = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/**
	 * The dialogue variant for the current mood ("scared", "happy", "grumpy", "tired", "sad",
	 * "angry"), or "" when calm. Dialogue lines can have keys like {@code ack.plan.grumpy}.
	 */
	public String variant() {
		Kind k = dominant(0.5F);
		if (k == null) {
			return "";
		}
		return switch (k) {
			case FEAR -> "scared";
			case JOY -> "happy";
			case FRUSTRATION, BOREDOM -> "grumpy";
			case FATIGUE -> "tired";
			case GRIEF -> "sad";
			case ANGER -> "angry";
		};
	}

	/** In words, for /companion info. */
	public String summary() {
		StringBuilder sb = new StringBuilder();
		for (Kind k : Kind.values()) {
			float v = get(k);
			if (v >= 0.05F) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				sb.append(k.name().toLowerCase(Locale.ROOT)).append(String.format(Locale.ROOT, " %.2f", v));
			}
		}
		return sb.length() == 0 ? "calm" : sb.toString();
	}

	/** How it feels, in a few words (for "how do you feel?"). */
	public String describe() {
		Kind k = dominant(0.25F);
		if (k == null) {
			return "";
		}
		return switch (k) {
			case FEAR -> "a bit shaken";
			case JOY -> "pretty happy";
			case FRUSTRATION -> "frustrated";
			case FATIGUE -> "tired";
			case BOREDOM -> "bored";
			case GRIEF -> "sad";
			case ANGER -> "annoyed";
		};
	}
}

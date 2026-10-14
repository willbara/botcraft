package com.botcraft.faction;

import java.util.Locale;

/**
 * What a faction thinks of one player, -100..100. Hitting, killing or robbing members lowers it;
 * helping, gifts and apologies raise it; quiet days let it drift back toward where it started.
 * The band (trusted, friendly, neutral, hostile) only changes past a small margin, so a player
 * near a threshold doesn't flicker between being an enemy and not. Pure Java.
 */
public final class Standing {
	public enum Band {
		HOSTILE, NEUTRAL, FRIENDLY, TRUSTED;

		public String word() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** What a player did, and what it does to their standing. */
	public enum Incident {
		HIT(-25),
		KILLED(-60),
		STOLE(-15),
		BROKE_BUILD(-5),
		/** Killed something that was attacking a member. */
		HELPED(5),
		GIFT(2),
		APOLOGY(10),
		/** Trespass in the base while already disliked. */
		TRESPASS(-2);

		public final int delta;

		Incident(int delta) {
			this.delta = delta;
		}
	}

	/** Thresholds (from the config), so this class stays free of game code. */
	public record Thresholds(int trusted, int friendly, int hostile) {
		public static final Thresholds DEFAULT = new Thresholds(60, 20, -30);
	}

	/** How far past a threshold a band change back needs (hysteresis). */
	static final int MARGIN = 5;
	/** A tenth of the way back to the baseline per in-game day without incidents. */
	static final long DAY = 24000L;

	/** The player's name when last seen (for listings). */
	public String name = "";
	public float value;
	/** Where it drifts back to: the start value. */
	public float baseline;
	public Band band = Band.NEUTRAL;
	public long lastIncident;
	public long decayedAt;
	/** Times this player got a warning ("Hey! Watch it.") instead of being treated as an enemy. */
	public int warnings;

	public Standing() {
	}

	public Standing(String name, float start, Thresholds t) {
		this.name = name;
		this.value = clamp(start);
		this.baseline = this.value;
		this.band = bandFor(this.value, null, t);
	}

	/** Apply an incident {@code times} times (e.g. per stack taken). @return the band before, if it changed; else null */
	public Band apply(Incident incident, int times, long now, Thresholds t) {
		decay(now, t);
		Band before = band;
		value = clamp(value + incident.delta * (float) Math.max(1, times));
		if (incident.delta < 0) {
			lastIncident = now;
		}
		band = bandFor(value, band, t);
		return band != before ? before : null;
	}

	/** Set outright (by command). */
	public void set(float v, Thresholds t) {
		value = clamp(v);
		band = bandFor(value, null, t);
	}

	/** Drift back toward the baseline: 10% of the gap per day since the last incident. */
	public void decay(long now, Thresholds t) {
		if (decayedAt == 0 || now <= decayedAt) {
			decayedAt = Math.max(decayedAt, now);
			return;
		}
		double days = (now - decayedAt) / (double) DAY;
		decayedAt = now;
		if (now - lastIncident < DAY) {
			return; // still fresh
		}
		double factor = Math.pow(0.9, days);
		value = (float) (baseline + (value - baseline) * factor);
		band = bandFor(value, band, t);
	}

	/** The band for a value, given the band it's in now (null = no hysteresis). */
	static Band bandFor(float v, Band current, Thresholds t) {
		int up = current == null ? 0 : MARGIN;
		// Staying in a band needs only to be within MARGIN of its threshold.
		if (current == Band.TRUSTED && v >= t.trusted() - up) {
			return Band.TRUSTED;
		}
		if (current == Band.HOSTILE && v <= t.hostile() + up) {
			return Band.HOSTILE;
		}
		if (current == Band.FRIENDLY && v >= t.friendly() - up && v < t.trusted()) {
			return Band.FRIENDLY;
		}
		if (v >= t.trusted()) {
			return Band.TRUSTED;
		}
		if (v >= t.friendly()) {
			return Band.FRIENDLY;
		}
		if (v <= t.hostile()) {
			return Band.HOSTILE;
		}
		return Band.NEUTRAL;
	}

	public String summary() {
		return String.format(Locale.ROOT, "%s %+.0f (%s)", name, value, band.word());
	}

	private static float clamp(float v) {
		return Math.max(-100.0F, Math.min(100.0F, v));
	}
}

package com.botcraft.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * How one companion feels about one person (a player or another companion): liking (affinity,
 * -1..1), trust (0..1, starts at 0.5) and favours owed (debt: positive means they owe us).
 * Both drift back toward neutral over a few in-game days. Kept in the companion's memory with
 * the person. Pure Java, so it can be unit tested.
 */
public final class Relationship {
	/** What happened, and how much it moves affinity, trust and debt. */
	public enum Event {
		GIFT(0.05F, 0.02F, 1),
		HELPED(0.10F, 0.03F, 1),
		THANKS(0.02F, 0.0F, 0),
		PRAISE(0.02F, 0.0F, 0),
		INSULT(-0.05F, 0.0F, 0),
		APOLOGY(0.05F, 0.03F, 0),
		/** Worked on the same thing, or chatted. */
		TEAMWORK(0.02F, 0.01F, 0),
		HIT(-0.30F, -0.20F, 0),
		KILLED_ME(-0.60F, -0.50F, 0),
		STOLE(-0.30F, -0.40F, 0),
		BROKE_PROMISE(0.0F, -0.10F, 0),
		/** We gave them something / did them a favour. */
		GAVE(0.01F, 0.0F, -1);

		public final float affinity;
		public final float trust;
		public final int debt;

		Event(float affinity, float trust, int debt) {
			this.affinity = affinity;
			this.trust = trust;
			this.debt = debt;
		}
	}

	/** Ticks for affinity (and trust's distance from 0.5) to halve: three in-game days. */
	public static final long HALF_LIFE = 24000L * 3;
	private static final int MAX_NOTES = 8;

	public float affinity;
	public float trust = 0.5F;
	public int debt;
	/** Last time something happened between us (world time). */
	public long lastInteraction;
	/** When decay was last applied. */
	public long decayedAt;
	/** The last few notable things, newest last ("hit me", "gift"), for grudges and "remember when". */
	public List<String> notes = new ArrayList<>();

	/** Something happened, at world time {@code now}. */
	public void apply(Event e, long now) {
		decay(now);
		affinity = clamp(affinity + e.affinity, -1.0F, 1.0F);
		trust = clamp(trust + e.trust, 0.0F, 1.0F);
		debt = Math.max(-20, Math.min(20, debt + e.debt));
		lastInteraction = now;
		if (notes == null) {
			notes = new ArrayList<>();
		}
		if (e != Event.TEAMWORK && e != Event.THANKS) {
			notes.add(e.name().toLowerCase(java.util.Locale.ROOT));
			while (notes.size() > MAX_NOTES) {
				notes.remove(0);
			}
		}
	}

	/** Fade toward neutral up to {@code now}. */
	public void decay(long now) {
		if (decayedAt == 0 || now <= decayedAt) {
			decayedAt = Math.max(decayedAt, now);
			return;
		}
		double factor = Math.pow(0.5, (now - decayedAt) / (double) HALF_LIFE);
		affinity = (float) (affinity * factor);
		trust = (float) (0.5 + (trust - 0.5) * factor);
		decayedAt = now;
	}

	/** Recently hurt by them (within {@code window} ticks of {@code now}). */
	public boolean grudge(long now, long window) {
		return notes != null && !notes.isEmpty() && now - lastInteraction < window
			&& (notes.get(notes.size() - 1).equals("hit") || notes.get(notes.size() - 1).equals("killed_me") || notes.get(notes.size() - 1).equals("stole"));
	}

	/** In words, for /companion info. */
	public String summary() {
		return String.format(java.util.Locale.ROOT, "affinity %+.2f, trust %.2f%s", affinity, trust, debt == 0 ? "" : ", owes " + debt);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}

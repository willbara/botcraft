package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.memory.Memory;
import com.botcraft.util.AiLog;

/**
 * One companion's feelings about the people it knows (players and other companions), kept with
 * them in its memory. A thin layer over {@link Relationship} that knows the time and saves.
 */
public final class Relations {
	private final Companion c;

	public Relations(Companion c) {
		this.c = c;
	}

	private long time() {
		return c.server.getOverworld().getTime();
	}

	/** The relationship with someone (created if new). */
	public Relationship of(String name) {
		Memory.Person p = c.memory().person(name, time());
		if (p.rel == null) {
			p.rel = new Relationship();
			p.rel.decayedAt = time();
		}
		p.rel.decay(time());
		return p.rel;
	}

	/** Liking, -1..1 (0 for strangers). */
	public float affinity(String name) {
		Memory.Person p = c.memory().knownPerson(name);
		if (p == null || p.rel == null) {
			return 0.0F;
		}
		p.rel.decay(time());
		return p.rel.affinity;
	}

	/** Trust, 0..1 (0.5 for strangers). */
	public float trust(String name) {
		Memory.Person p = c.memory().knownPerson(name);
		if (p == null || p.rel == null) {
			return 0.5F;
		}
		p.rel.decay(time());
		return p.rel.trust;
	}

	/** They hurt or robbed us lately. */
	public boolean grudge(String name, long window) {
		Memory.Person p = c.memory().knownPerson(name);
		return p != null && p.rel != null && p.rel.grudge(time(), window);
	}

	/** Something happened between us. */
	public void note(String name, Relationship.Event e) {
		Relationship r = of(name);
		r.apply(e, time());
		c.memory().touch();
		AiLog.log(c.name(), "{} -> {} ({})", e, name, r.summary());
	}
}

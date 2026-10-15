package com.botcraft.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoodTest {
	@Test
	void feelingsFadeWithTheirHalfLife() {
		Mood m = new Mood();
		m.bump(Mood.Kind.FEAR, 0.8F, 0);
		assertEquals("scared", m.variant());
		m.update(Mood.Kind.FEAR.halfLife);
		assertEquals(0.4F, m.get(Mood.Kind.FEAR), 1e-3);
		assertEquals("", m.variant(), "calm again below 0.5");
		m.update(Mood.Kind.FEAR.halfLife * 20L);
		assertEquals(0.0F, m.get(Mood.Kind.FEAR), 1e-6);
	}

	@Test
	void strongestFeelingWins() {
		Mood m = new Mood();
		m.bump(Mood.Kind.JOY, 0.6F, 0);
		m.bump(Mood.Kind.FRUSTRATION, 0.9F, 0);
		assertEquals(Mood.Kind.FRUSTRATION, m.dominant(0.5F));
		assertEquals("grumpy", m.variant());
		m.bump(Mood.Kind.FRUSTRATION, -1.0F, 0);
		assertEquals("happy", m.variant());
		assertTrue(m.summary().contains("joy"));
	}
}

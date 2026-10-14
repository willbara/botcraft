package com.botcraft.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipTest {
	@Test
	void hitsMakeAGrudgeThatFades() {
		Relationship r = new Relationship();
		r.decayedAt = 1;
		r.apply(Relationship.Event.HIT, 100);
		assertEquals(-0.3F, r.affinity, 1e-6);
		assertEquals(0.3F, r.trust, 1e-6);
		assertTrue(r.grudge(200, 400));
		assertFalse(r.grudge(1000, 400), "grudges are short");
		r.decay(100 + Relationship.HALF_LIFE);
		assertEquals(-0.15F, r.affinity, 1e-3);
		assertEquals(0.4F, r.trust, 1e-3, "trust drifts back to 0.5");
	}

	@Test
	void giftsBuildDebtAndLiking() {
		Relationship r = new Relationship();
		r.decayedAt = 1;
		for (int i = 0; i < 30; i++) {
			r.apply(Relationship.Event.GIFT, 10 + i);
		}
		assertEquals(1.0F, r.affinity, 1e-6, "clamped");
		assertEquals(20, r.debt, "capped");
		assertTrue(r.notes.size() <= 8);
	}
}

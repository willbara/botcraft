package com.botcraft.faction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingTest {
	private static final Standing.Thresholds T = Standing.Thresholds.DEFAULT;

	@Test
	void twoHitsMakeAnEnemy() {
		Standing s = new Standing("Steve", 0, T);
		assertEquals(Standing.Band.NEUTRAL, s.band);
		assertNull(s.apply(Standing.Incident.HIT, 1, 10, T), "one hit: still neutral");
		assertEquals(Standing.Band.NEUTRAL, s.apply(Standing.Incident.HIT, 1, 20, T), "band changed from neutral");
		assertEquals(Standing.Band.HOSTILE, s.band);
	}

	@Test
	void bandsHaveSomeHysteresis() {
		Standing s = new Standing("Steve", -50, T);
		s.apply(Standing.Incident.APOLOGY, 1, 10, T);
		assertEquals(Standing.Band.HOSTILE, s.band, "-40 is still hostile");
		s.apply(Standing.Incident.GIFT, 7, 20, T);
		assertEquals(Standing.Band.HOSTILE, s.band, "-26 is within the margin");
		s.apply(Standing.Incident.GIFT, 1, 30, T);
		assertEquals(Standing.Band.NEUTRAL, s.band, "-24 is out");
		Standing f = new Standing("Alex", 22, T);
		f.apply(Standing.Incident.BROKE_BUILD, 1, 10, T);
		assertEquals(Standing.Band.FRIENDLY, f.band, "17 stays friendly");
		f.apply(Standing.Incident.BROKE_BUILD, 1, 20, T);
		assertEquals(Standing.Band.NEUTRAL, f.band);
	}

	@Test
	void quietDaysHealStanding() {
		Standing s = new Standing("Owner", 90, T);
		s.decayedAt = 1;
		s.apply(Standing.Incident.HIT, 2, 10, T);
		assertEquals(40, s.value, 1e-3);
		assertEquals(Standing.Band.FRIENDLY, s.band);
		s.decay(10 + Standing.DAY * 30, T);
		assertTrue(s.value > 85, "back near where it started: " + s.value);
		assertEquals(Standing.Band.TRUSTED, s.band);
	}
}

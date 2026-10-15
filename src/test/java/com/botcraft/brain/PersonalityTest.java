package com.botcraft.brain;

import com.botcraft.entity.CompanionData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalityTest {
	private static CompanionData data(String name) {
		CompanionData d = new CompanionData();
		d.name = name;
		d.uuid = UUID.nameUUIDFromBytes(name.getBytes());
		d.chattiness = 0.5F;
		d.bravery = 0.5F;
		d.curiosity = 0.5F;
		d.helpfulness = 0.5F;
		return d;
	}

	@Test
	void missingTraitsAreSeededAndStable() {
		CompanionData d = data("Bob");
		Personality a = Personality.of(d);
		Personality b = Personality.of(d);
		assertEquals(a, b);
		assertTrue(a.diligence() >= 0.2F && a.diligence() <= 0.8F);
		assertEquals(8, d.traits.size(), "seeded traits are kept");
		assertFalse(Personality.of(data("Alice")).equals(a), "different companions differ");
	}

	@Test
	void archetypesAndSettingTraits() {
		CompanionData d = data("Bob");
		assertTrue(Personality.applyArchetype(d, "guard"));
		assertEquals("guard", d.archetype);
		assertEquals(0.9F, Personality.of(d).bravery(), 1e-6);
		assertEquals(0.7F, Personality.of(d).aggression(), 1e-6);
		assertFalse(Personality.applyArchetype(d, "astronaut"));
		assertTrue(Personality.set(d, "humor", 2.0F));
		assertEquals(1.0F, Personality.of(d).humor(), 1e-6, "clamped");
		assertFalse(Personality.set(d, "height", 0.5F));
	}

	@Test
	void driftStaysNearWhereItStarted() {
		CompanionData d = data("Bob");
		for (int i = 0; i < 100; i++) {
			Personality.drift(d, "bravery", -0.02F);
		}
		assertEquals(0.5F - Personality.DRIFT_LIMIT, Personality.of(d).bravery(), 1e-4);
	}

	@Test
	void traitsShapeBehaviour() {
		CompanionData brave = data("Bob");
		brave.bravery = 1.0F;
		CompanionData timid = data("Bob");
		timid.bravery = 0.0F;
		assertTrue(Personality.of(brave).retreatHealth() < Personality.of(timid).retreatHealth(), "brave ones retreat later");
		Personality.set(brave, "generosity", 0.9F);
		Personality.set(timid, "generosity", 0.1F);
		assertTrue(Personality.of(brave).reserve(false) < Personality.of(timid).reserve(false), "generous ones keep less back");
	}
}

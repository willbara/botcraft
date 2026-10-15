package com.botcraft.brain;

import com.botcraft.brain.goal.GoalTree;
import com.botcraft.brain.goal.GoalTree.Root;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalTreeTest {
	private static final Map<Root, Float> EVEN = Map.of();

	private static void offerAll(GoalTree t, long now) {
		t.begin(now);
		t.offer(Root.FACTION, "store", "pockets full", List.of("deposit"), 1.0F);
		t.offer(Root.SURVIVE, "hunt", "hungry", List.of("hunt meat 4"), 0.96F);
		t.offer(Root.PROGRESS, "cave_coal", "cave nearby", List.of("mine coal 8"), 0.92F);
	}

	@Test
	void picksTheMostImportantAndSticksWithIt() {
		GoalTree t = new GoalTree();
		offerAll(t, 0);
		assertEquals("store", t.pick(0, EVEN, 0.1F, false).key);
		// Personality can tip close calls...
		offerAll(t, 200);
		GoalTree fresh = new GoalTree();
		offerAll(fresh, 0);
		assertEquals("hunt", fresh.pick(0, Map.of(Root.FACTION, 0.9F, Root.SURVIVE, 1.1F), 0.0F, false).key);
		// ...but what it's already doing gets a bonus.
		assertEquals("store", t.pick(200, Map.of(Root.FACTION, 0.92F, Root.SURVIVE, 1.0F), 0.1F, false).key);
	}

	@Test
	void failuresPutAGoalAsideForLongerEachTime() {
		GoalTree t = new GoalTree();
		offerAll(t, 0);
		t.pick(0, EVEN, 0.1F, false);
		t.failed("store", 0, "no chest");
		assertFalse(t.allowed("store", GoalTree.BACKOFF - 1));
		assertTrue(t.allowed("store", GoalTree.BACKOFF));
		offerAll(t, GoalTree.BACKOFF);
		t.failed("store", GoalTree.BACKOFF, "no chest");
		assertFalse(t.allowed("store", GoalTree.BACKOFF * 2 + 1), "twice as long the second time");
		offerAll(t, GoalTree.BACKOFF + 10);
		assertEquals("hunt", t.pick(GoalTree.BACKOFF + 10, EVEN, 0.1F, false).key);
		t.succeeded("store");
		assertTrue(t.allowed("store", GoalTree.BACKOFF + 20), "success clears it");
	}

	@Test
	void strictOrderAndRendering() {
		GoalTree t = new GoalTree();
		offerAll(t, 0);
		assertEquals("store", t.pick(0, Map.of(Root.FACTION, 0.1F), 0.0F, true).key, "strict ignores weights");
		List<String> lines = t.render(0);
		assertTrue(lines.stream().anyMatch(l -> l.contains("> store")));
		t.begin(10);
		assertNull(t.pick(10, EVEN, 0.1F, false), "nothing offered");
		assertEquals(Root.SURVIVE, GoalTree.rootOf("hunt"));
		assertEquals(Root.PROGRESS, GoalTree.rootOf("target_iron_pickaxe"));
		assertEquals(Root.CURIOSITY, GoalTree.rootOf("fish"));
	}
}

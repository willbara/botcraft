package com.botcraft.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchNamesTest {
	@Test
	void namesAreValidUsernames() {
		Random r = new Random(1);
		for (int i = 0; i < 5000; i++) {
			String n = BatchNames.next(r, x -> false);
			assertTrue(n.matches("[A-Za-z0-9_]{3,16}"), n);
		}
	}

	@Test
	void neverRunsOutOfNames() {
		Random r = new Random(2);
		Set<String> taken = new HashSet<>();
		int n = BatchNames.pairs() + 2000; // more than there are plain pairs
		for (int i = 0; i < n; i++) {
			String name = BatchNames.next(r, x -> taken.contains(x.toLowerCase(Locale.ROOT)));
			assertTrue(taken.add(name.toLowerCase(Locale.ROOT)), "repeated " + name);
		}
	}
}

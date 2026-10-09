package com.mcfriends.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatesTest {
	@Test
	void fillsPlaceholdersAndCapitalises() {
		assertEquals("Chopping wood (2/5). Health 20", Templates.fill("{task}. Health {health}", Map.of("task", "chopping wood (2/5)", "health", "20")));
		assertEquals("Hi {missing}", Templates.fill("Hi {missing}", Map.of()));
	}

	@Test
	void everyEventKeyResolves() {
		Random r = new Random(1);
		for (String key : new String[]{"event.night", "event.low_health", "event.creeper", "event.task_done", "event.hello",
			"ack.follow", "ack.stop", "ack.come", "ack.gather", "ack.defend", "status", "greeting"}) {
			assertTrue(Templates.has(key), key);
			Map<String, String> vars = Map.ofEntries(Map.entry("player", "Will"), Map.entry("count", "3"), Map.entry("item", "logs"),
				Map.entry("task", "x"), Map.entry("health", "1"), Map.entry("food", "2"), Map.entry("logs", "3"), Map.entry("mood", "ok"),
				Map.entry("time_note", ""), Map.entry("name", "Bob"), Map.entry("owner", "Will"), Map.entry("mob", "zombie"));
			String line = Templates.pick(key, "brave", vars, r);
			assertFalse(line.isEmpty(), key);
			assertFalse(line.contains("{"), key + " left a placeholder: " + line);
		}
	}
}

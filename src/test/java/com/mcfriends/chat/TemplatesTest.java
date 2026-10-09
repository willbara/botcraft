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
	void dialogueFileIsRich() {
		assertTrue(Templates.lines("ack.follow").size() >= 8, "plenty of variety");
		assertTrue(Templates.lines("event.muse").size() >= 8);
	}

	@Test
	void avoidsRecentLines() {
		Random r = new Random(3);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < Templates.lines("ack.stop").size(); i++) {
			String l = Templates.pick("ack.stop", "", Map.of(), r, seen::contains);
			assertFalse(seen.contains(l), "repeated " + l);
			seen.add(l);
		}
	}

	@Test
	void voicesDiffer() {
		Voice calm = new Voice(false, 0, java.util.List.of(), "", "");
		Voice casual = new Voice(true, 2, java.util.List.of(), ":D", "boss");
		Random r = new Random(1);
		assertEquals("Okay, lead the way.", calm.apply("Okay, lead the way!", r));
		assertTrue(casual.apply("Okay, lead the way!", new Random(1)).startsWith("ok, lead the way"));
	}

	@Test
	void everyEventKeyResolves() {
		Random r = new Random(1);
		for (String key : new String[]{"talk.joke", "talk.favorite", "talk.like", "talk.time", "banter.found", "social.help", "event.muse",
			"event.biome", "event.night", "event.low_health", "event.creeper", "event.task_done", "event.hello",
			"ack.follow", "ack.stop", "ack.come", "ack.defend", "status", "greeting", "event.step_done", "event.plan_failed",
			"event.initiative", "ack.plan", "ack.plan_more", "where", "where.near", "inventory", "stats", "found", "found.none",
			"help", "cant.do", "ack.home_set", "event.owner_back"}) {
			assertTrue(Templates.has(key), key);
			Map<String, String> vars = Map.ofEntries(Map.entry("player", "Will"), Map.entry("count", "3"), Map.entry("item", "logs"),
				Map.entry("task", "x"), Map.entry("health", "1"), Map.entry("food", "2"), Map.entry("logs", "3"), Map.entry("mood", "ok"),
				Map.entry("time_note", ""), Map.entry("name", "Bob"), Map.entry("owner", "Will"), Map.entry("mob", "zombie"), Map.entry("result", "got 3 logs"),
				Map.entry("next", "mine 8 coal"), Map.entry("plan", "chop 16 logs"), Map.entry("reason", "because"), Map.entry("job", "chop logs"),
				Map.entry("x", "1"), Map.entry("y", "2"), Map.entry("z", "3"), Map.entry("dist", "4"), Map.entry("dir", "north"),
				Map.entry("biome", "plains"), Map.entry("items", "a stick"), Map.entry("ores", "5"), Map.entry("kills", "2"),
				Map.entry("crafted", "1"), Map.entry("deaths", "0"), Map.entry("list", "coal at 1, 2, 3"), Map.entry("nick", "boss"),
				Map.entry("friend", "Alice"), Map.entry("thing", "food"), Map.entry("fav", "cake"), Map.entry("topic", "creepers"),
				Map.entry("time", "noon"), Map.entry("biome_a", "a plains"));
			String line = Templates.fill(Templates.pick(key, "brave", vars, r), vars);
			assertFalse(line.isEmpty(), key);
			assertFalse(line.contains("{"), key + " left a placeholder: " + line);
		}
	}
}

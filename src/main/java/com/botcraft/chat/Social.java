package com.botcraft.chat;

import com.botcraft.Companion;
import com.botcraft.brain.Objective;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.OreKind;
import com.botcraft.memory.Memory;
import com.botcraft.util.AiLog;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Companions noticing each other: greeting newcomers, idle banter (weather, work, favourites,
 * jokes, complaints, and sharing where they found ores - the listener really remembers it),
 * praising kills, mourning deaths. Fighting to protect each other is in Perception (a mob
 * after a friend counts as a threat).
 */
public final class Social {
	private static final Random RANDOM = new Random();
	private static final int PAIR_COOLDOWN = 20 * 60 * 6;
	private static final Map<String, Long> pairCooldown = new HashMap<>();

	private Social() {
	}

	private static boolean idle(Companion c) {
		CompanionPlayer p = c.player();
		if (p == null || !c.isOnline() || p.isDead() || p.isSleeping() || c.nav().isBusy() || !c.jobs().isIdle()) {
			return false;
		}
		Objective o = c.brain().currentObjective();
		return (o == Objective.IDLE || o == Objective.STAY || o == Objective.FOLLOW) && c.perception().threats().isEmpty();
	}

	private static List<Companion> near(Companion c, Collection<Companion> all, double radius) {
		List<Companion> out = new ArrayList<>();
		CompanionPlayer p = c.player();
		if (com.botcraft.brain.Popbob.is(c)) {
			return out; // nobody's friend
		}
		for (Companion o : all) {
			CompanionPlayer op = o.player();
			if (o != c && !com.botcraft.brain.Popbob.is(o) && op != null && o.isOnline() && !op.isDead() && op.getWorld() == p.getWorld() && op.distanceTo(p) <= radius) {
				out.add(o);
			}
		}
		return out;
	}

	/** Called every server tick by the manager. */
	public static void tick(Collection<Companion> all, long now) {
		if (now % 100 != 0 || all.size() < 2) {
			return;
		}
		for (Companion a : all) {
			if (!idle(a)) {
				continue;
			}
			for (Companion b : near(a, all, 10)) {
				String key = pairKey(a, b);
				if (!idle(b) || now < pairCooldown.getOrDefault(key, 0L)) {
					continue;
				}
				float chance = 0.04F * (a.data.chattiness + b.data.chattiness);
				pairCooldown.put(key, now + PAIR_COOLDOWN / 4); // re-roll every ~1.5 min at most
				if (RANDOM.nextFloat() < chance) {
					pairCooldown.put(key, now + PAIR_COOLDOWN);
					banter(a, b);
					return; // one conversation at a time is plenty
				}
			}
		}
	}

	private static String pairKey(Companion a, Companion b) {
		return a.name().compareTo(b.name()) < 0 ? a.name() + "|" + b.name() : b.name() + "|" + a.name();
	}

	private static Map<String, String> with(Companion friend) {
		Map<String, String> v = new HashMap<>();
		v.put("friend", friend.name());
		return v;
	}

	static void banter(Companion a, Companion b) {
		banter(a, b, null);
	}

	/** Start a short conversation; {@code forced} picks the topic (tests), null = random. */
	public static void banter(Companion a, Companion b, String forced) {
		List<String> topics = new ArrayList<>(List.of("weather", "work", "favorite", "joke", "complain"));
		Memory.Sighting found = null;
		for (Memory.Sighting s : a.memory().sightings) {
			if (!s.kind.equals("tree") && s.dim.equals(a.dimension()) && b.memory().sightings(s.kind, s.pos(), s.dim, 6).isEmpty()) {
				found = s;
				topics.add("found");
				topics.add("found");
				break;
			}
		}
		String topic = forced != null && (topics.contains(forced)) ? forced : topics.get(RANDOM.nextInt(topics.size()));
		AiLog.log(a.name(), "banter with {} about {}", b.name(), topic);
		Map<String, String> va = with(b);
		Map<String, String> vb = with(a);
		switch (topic) {
			case "work" -> {
				va.put("logs", Integer.toString(a.memory().stat("logs_chopped")));
				va.put("ores", Integer.toString(ores(a)));
				vb.put("logs", Integer.toString(b.memory().stat("logs_chopped")));
				vb.put("ores", Integer.toString(ores(b)));
			}
			case "favorite" -> {
				String thing = List.of("food", "mob", "biome", "block", "color").get(RANDOM.nextInt(5));
				va.put("thing", thing);
				vb.put("thing", thing);
				vb.put("fav", SmallTalk.favorite(b, thing));
			}
			case "found" -> {
				OreKind k = OreKind.byWord(found.kind);
				va.put("item", k != null ? k.noun() : found.kind);
				va.put("x", Integer.toString(found.x));
				va.put("y", Integer.toString(found.y));
				va.put("z", Integer.toString(found.z));
				// The listener actually learns it, so a later mining trip can go straight there.
				b.memory().sight(found.kind, found.pos(), found.dim, a.server.getOverworld().getTime());
			}
			default -> {
			}
		}
		a.speech().sayIn("banter." + topic, null, va, 0);
		b.speech().sayIn("banter." + topic + ".reply", null, vb, 70);
		if (topic.equals("joke")) {
			a.speech().sayIn("banter.punchline", null, va, 140);
			b.speech().sayIn("banter.laugh", null, vb, 210);
		} else if (topic.equals("favorite")) {
			va.put("fav", SmallTalk.favorite(a, va.get("thing")));
		}
	}

	private static int ores(Companion c) {
		int n = 0;
		for (Map.Entry<String, Integer> e : c.memory().stats.entrySet()) {
			if (e.getKey().startsWith("mined_")) {
				n += e.getValue();
			}
		}
		return n;
	}

	/** A companion joined near others: they greet each other. */
	public static void onSpawn(Companion newcomer, Collection<Companion> all) {
		for (Companion o : near(newcomer, all, 24)) {
			o.speech().sayIn("social.greet", null, with(newcomer), 30);
			newcomer.speech().sayIn("social.greet_reply", null, with(o), 90);
			return; // one greeting is enough
		}
	}

	public static void onKill(Companion killer, LivingEntity victim, Collection<Companion> all) {
		for (Companion o : near(killer, all, 16)) {
			o.speech().event("social.praise", Map.of("friend", killer.name()), 20 * 60, 0.2F);
		}
	}

	public static void onDeath(Companion dead, Collection<Companion> all) {
		for (Companion o : near(dead, all, 32)) {
			o.speech().event("social.friend_died", Map.of("friend", dead.name()), 20 * 30, 0.8F);
		}
	}
}

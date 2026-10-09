package com.mcfriends.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Randomised response templates. Placeholders like {player}, {health}, {task} are filled from
 * real game state by the caller. Keys may have ".brave" / ".timid" variants chosen by
 * personality. Pure Java so it can be unit tested.
 */
public final class Templates {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
	private static final Map<String, List<String>> LINES = new HashMap<>();

	static {
		// --- replies to orders ---
		put("ack.follow", "Right behind you, {player}.", "Okay, lead the way!", "Following you.", "Coming with you.", "Sure thing, I'll tag along.");
		put("ack.stop", "Okay, I'll wait here.", "Stopping.", "I'll stay put.", "Alright, holding position.", "Sure, I'll hang around here.");
		put("ack.come", "On my way!", "Coming!", "Be right there, {player}.", "Heading over.");
		put("ack.gather", "Sure, I'll get {count} {item}.", "On it, {count} {item} coming up.", "Okay! Going to find some trees.",
			"Wood duty, got it. {count} {item}.");
		put("ack.defend", "I've got your back, {player}.", "Nothing's getting past me.", "I'll keep the monsters off you.");
		put("ack.defend.timid", "I'll... try my best to protect you.", "Okay, I'll watch your back. Gulp.");
		put("ack.give", "Here you go, {count} {item}.", "Catch!", "Take these.");
		put("give.none", "I don't have any {item}.", "I'm out of {item}.", "No {item} on me, sorry.");
		put("give.far", "Come closer and I'll toss them to you.", "You're too far to throw to.");
		put("cant.do", "I can't do that: {reason}.", "Hmm, {reason}.", "Sorry, {reason}.");
		put("ack.plan", "Okay: {plan}.", "On it. I'll {plan}.", "Got it, {plan}.", "Sure thing: {plan}.");
		put("ack.plan_more", "Okay, after that I'll {plan}.", "Added to my list: {plan}.", "Sure, I'll {plan} next.");
		put("ack.cancel", "Okay, forget it.", "Cancelled.", "Alright, dropping that.");
		put("ack.home_set", "Got it, this is home ({x}, {y}, {z}).", "Home sweet home. I'll remember it.", "Noted: home is at {x}, {y}, {z}.");
		put("no_home", "We don't have a home yet. Say 'this is home' somewhere.", "Where's home? Tell me with 'set home here'.");
		put("not_night", "It's not night, I'm not sleepy.", "Sleep? It's daytime!");
		put("where", "I'm at {x}, {y}, {z}, about {dist} blocks {dir} of you.", "{dist} blocks {dir} of you, at {x}, {y}, {z} ({biome}).",
			"Over here! {x}, {y}, {z}, roughly {dist} blocks {dir}.");
		put("where.near", "Right here, next to you!", "Look around, I'm right here.", "Behind you! Well, close by anyway.");
		put("where.far", "I'm at {x}, {y}, {z} in a {biome}.", "{x}, {y}, {z}. Not in your dimension, though.");
		put("inventory", "I've got {items}.", "In my pockets: {items}.", "Let's see... {items}.");
		put("inventory.empty", "My pockets are empty.", "Nothing, I'm broke.", "Not a thing.");
		put("stats", "So far I've chopped {logs} logs, mined {ores} ores, crafted {crafted} things, beaten {kills} monsters and died {deaths} times.",
			"Logs: {logs}. Ores: {ores}. Crafted: {crafted}. Monsters: {kills}. Deaths: {deaths}.");
		put("found", "I've seen {list}.", "Spotted {list}.", "Last I saw: {list}.");
		put("found.none", "I haven't seen {item} yet.", "No {item} so far.");
		put("help", "I can follow you, stay, come here, chop wood, mine (coal, iron, stone...), craft things, bring you stuff, defend you, sleep, and tell you where I am or what I'm carrying. Chain orders with 'then'.");
		put("greeting.friend", "Hey {player}! Good to see you.", "{player}! What's the plan?", "Hi boss.");
		put("not_owner", "Sorry {player}, I only take orders from {owner}.", "Ask {owner}, they're my boss.");
		put("confused", "Hm?", "Sorry, what was that?", "I didn't catch that.", "Not sure what you mean, {player}.");
		put("greeting", "Hey {player}!", "Hi {player}!", "Hello!", "Oh hey, {player}.", "Yo!");
		put("thanks", "Anytime!", "No problem.", "Happy to help.", "You're welcome, {player}.");
		put("status", "I'm {task}. Health {health}/20, food {food}/20.", "Just {task}. Feeling {mood}.",
			"{task}, {player}. I've got {logs} logs on me.", "{task}. HP {health}, hunger {food}. {time_note}");

		// --- unprompted events ---
		put("event.hello", "Hi everyone! I'm {name}.", "Hello world!", "Hey, I'm {name}. Nice to meet you!", "Reporting for duty.");
		put("event.back", "I'm back!", "That was rough. I'm back.", "Okay, where was I?", "Respawned. Let's not do that again.");
		put("event.night", "It's getting dark. Monsters will be out soon.", "Night's falling, stay close.", "Sun's going down...",
			"Getting late. Should we head somewhere safe?");
		put("event.night.brave", "Night time. Let them come.", "It's getting dark. I'll keep watch.");
		put("event.morning", "Morning! We made it.", "Sun's up.", "Ah, daylight.");
		put("event.low_health", "I'm hurt pretty bad!", "Ouch... I need a moment.", "Low on health here!", "Help, I'm not doing great!");
		put("event.low_health.brave", "Just a scratch. Mostly.", "I'm hurt, but I'll live.");
		put("event.creeper", "Creeper! Watch out!", "Creeper, run!", "Uh oh. Creeper!", "Hsss... CREEPER!");
		put("event.creeper.brave", "Creeper over there, careful!", "Heads up, creeper!");
		put("event.task_done", "Done! I {result}.", "All done, {result}.", "Finished: {result}. What next?", "There we go, {result}.");
		put("event.task_failed", "I couldn't {job}: {reason}.", "Sorry, I gave up on that: {reason}.", "That didn't work out ({reason}).");
		put("event.step_done", "Okay, {result}. Now I'll {next}.", "That's done ({result}). Next: {next}.", "{result}! On to {next}.");
		put("event.plan_failed", "I couldn't {job} ({reason}), so I'm stopping there.", "Plan's off, sorry: {reason}.");
		put("event.initiative", "I'm going to {plan}, {reason}.", "Mind if I {plan}? {reason}.", "Quick thing: I'll {plan}. {reason}.");
		put("event.no_bed", "There's no bed for me, I'll keep watch.", "No spare bed, I'll stand guard.", "Can't sleep: {reason}.");
		put("event.owner_back", "Welcome back, {player}!", "Hey {player}, you're back!", "Oh hi {player}.");
		put("event.owner_back_long", "{player}! It's been a while.", "Long time no see, {player}!", "I missed you, {player}.");
		put("event.flee_unarmed", "I've got no weapon, running!", "Can't fight that bare-handed!", "No sword, no chance. Bye!");
		put("event.arrived", "Here I am.", "Made it.", "I'm here.");
		put("event.hungry", "I'm starving and out of food.", "Anyone have something to eat?", "My stomach is growling and I have no food.");
		put("event.kill", "Got it!", "Take that!", "One less {mob}.", "That {mob} won't bother us anymore.");
		put("event.fight", "I'll handle this {mob}!", "Stay back, {mob}!", "{mob}! I've got it.");
		put("event.fight.timid", "Eek, a {mob}! Okay... here goes.", "I really don't like {mob}s.");
		put("event.flee", "Nope, nope, nope!", "Too many of them, falling back!", "Run!");
	}

	private Templates() {
	}

	private static void put(String key, String... lines) {
		LINES.put(key, List.of(lines));
	}

	public static boolean has(String key) {
		return LINES.containsKey(key);
	}

	/**
	 * Picks a line for the key (preferring a ".brave"/".timid" variant when given) and fills its
	 * placeholders. Unknown placeholders are left as-is so missing data is visible in testing.
	 */
	public static String pick(String key, String variant, Map<String, String> vars, Random random) {
		List<String> lines = null;
		if (variant != null && !variant.isEmpty() && random.nextFloat() < 0.6F) {
			lines = LINES.get(key + "." + variant);
		}
		if (lines == null) {
			lines = LINES.get(key);
		}
		if (lines == null || lines.isEmpty()) {
			return "";
		}
		return fill(lines.get(random.nextInt(lines.size())), vars);
	}

	public static String fill(String line, Map<String, String> vars) {
		Matcher m = PLACEHOLDER.matcher(line);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = vars.get(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
		}
		m.appendTail(sb);
		String s = sb.toString();
		// Capitalise the first letter (task descriptions start lowercase).
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}

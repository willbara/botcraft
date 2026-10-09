package com.mcfriends.chat;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * How a companion talks, derived from its name and personality so it stays the same for that
 * companion: casual lowercase vs proper, excited vs calm punctuation, favourite filler words,
 * maybe an emoticon habit, and a nickname for the owner. Applied on top of the dialogue lines,
 * so the same line comes out a little differently from different companions. Pure Java.
 */
public record Voice(boolean casual, int energy, List<String> fillers, String emote, String nick) {
	private static final List<List<String>> FILLERS = List.of(
		List.of("hmm, ", "oh, ", "okay so, "), List.of("alright, ", "right, ", "well, "), List.of("yeah, ", "ok, ", "so "),
		List.of("ooh, ", "oh! ", "hey, "), List.of("", "", ""));
	private static final List<String> EMOTES = List.of(":)", ":D", "o/", "xD", "^^", ":P", "", "", "", "");
	private static final List<String> NICKS = List.of("boss", "chief", "buddy", "pal", "friend", "partner", "captain", "");

	/**
	 * @param seed     stable per companion (e.g. UUID bits)
	 * @param chatty   chattiness 0..1 (chatty voices are more energetic and emote more)
	 * @param brave    bravery 0..1 (brave voices are blunter, fewer fillers)
	 */
	public static Voice of(long seed, float chatty, float brave) {
		Random r = new Random(seed);
		boolean casual = r.nextFloat() < 0.35F;
		int energy = chatty > 0.65F ? 2 : chatty < 0.35F ? 0 : 1;
		List<String> fillers = brave > 0.7F ? List.of("", "") : FILLERS.get(r.nextInt(FILLERS.size()));
		String emote = chatty > 0.4F ? EMOTES.get(r.nextInt(EMOTES.size())) : "";
		String nick = NICKS.get(r.nextInt(NICKS.size()));
		return new Voice(casual, energy, fillers, emote, nick);
	}

	/** Restyle a filled line. {@code random} decides the occasional flourishes. */
	public String apply(String text, Random random) {
		if (text.isEmpty()) {
			return text;
		}
		String s = text;
		if (energy == 0) {
			s = s.replace("!", ".").replaceAll("\\.{2,}\\.", "...");
		} else if (energy == 2 && s.endsWith(".") && !s.endsWith("...") && random.nextFloat() < 0.4F) {
			s = s.substring(0, s.length() - 1) + "!";
		}
		if (!fillers.isEmpty() && random.nextFloat() < 0.18F) {
			String f = fillers.get(random.nextInt(fillers.size()));
			if (!f.isEmpty() && s.length() > 1) {
				s = f + lowerFirst(s);
			}
		}
		if (casual) {
			// Casual typing: lowercase sentence starts, but keep names and "I" as they are.
			s = lowerFirst(s).replace("Okay", "ok").replace("okay", "ok").replace("going to", "gonna")
				.replaceAll("\\byou\\b", random.nextFloat() < 0.3F ? "u" : "you");
			if (s.endsWith(".") && !s.endsWith("...")) {
				s = s.substring(0, s.length() - 1);
			}
		} else if (!s.isEmpty()) {
			s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		}
		if (!emote.isEmpty() && random.nextFloat() < (energy == 2 ? 0.25F : 0.12F)) {
			s = s + " " + emote;
		}
		return s;
	}

	/** Lowercase the first letter unless it's "I"/"I'm" or looks like a name (we can't tell, so only common words). */
	private static String lowerFirst(String s) {
		if (s.length() < 2) {
			return s.toLowerCase(Locale.ROOT);
		}
		String first = s.split("[ ,.!?']", 2)[0];
		if (first.equals("I") || !COMMON_START.contains(first.toLowerCase(Locale.ROOT))) {
			return s;
		}
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	private static final java.util.Set<String> COMMON_START = java.util.Set.of("okay", "ok", "on", "got", "sure", "can", "you", "alright", "right", "following", "coming", "stopping", "here", "take", "catch", "all", "done", "finished", "there", "that", "step", "so", "yeah", "yep", "hey", "hi", "hello", "oh", "hmm", "well", "nice", "good", "thanks", "no", "not", "sorry", "just", "i'll", "it's", "its", "the", "a", "my", "we", "let's", "lets", "what", "where", "how", "hang", "leave", "made", "quick", "mind", "gonna", "time", "night", "morning", "sun's", "new", "creeper", "run", "nope", "too", "ouch", "help", "low", "back", "respawned", "anyone", "great", "rain", "whoa", "ooh", "huh", "welcome", "careful", "diamonds", "noooo", "big", "show", "plan's", "hit", "can't", "couldn't", "nothing", "lovely", "is", "psst", "go", "ha", "boo", "lol", "easy", "noted", "every", "don't", "right?", "it");

	/** What to call the owner: their name, or the nickname this companion likes. */
	public String nickFor(String ownerName, Random random) {
		return nick.isEmpty() || random.nextFloat() < 0.5F ? ownerName : nick;
	}
}

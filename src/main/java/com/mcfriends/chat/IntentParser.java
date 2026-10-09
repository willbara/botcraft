package com.mcfriends.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline keyword/pattern intent parser. Pure Java (no Minecraft classes) so it is unit tested.
 *
 * <p>Pattern order matters: more specific intents are tried first ("stop following" is STOP,
 * "get over here" is COME_HERE not GATHER, "give me 5 logs" is GIVE not GATHER).
 */
public final class IntentParser {
	public static final String ALL = "*";
	public static final int DEFAULT_COUNT = 16;

	private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
		Map.entry("one", 1), Map.entry("a", 1), Map.entry("an", 1), Map.entry("two", 2), Map.entry("couple", 2),
		Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("few", 5), Map.entry("six", 6),
		Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("twelve", 12),
		Map.entry("fifteen", 15), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("fifty", 50),
		Map.entry("stack", 64), Map.entry("lots", 32), Map.entry("bunch", 24), Map.entry("some", DEFAULT_COUNT));

	private static final Pattern ALL_WORDS = Pattern.compile("\\b(everyone|everybody|all|guys|team|friends|y'?all|companions)\\b");
	private static final Pattern ITEM_WORD = Pattern.compile(
		"\\b(logs?|wood|woods|trees?|timber|oak|birch|spruce|jungle|acacia|planks?|stone|cobble(stone)?|dirt|sand|food|iron|coal|diamonds?|wool|apples?|saplings?)\\b");
	private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,4})\\b");

	private static final Pattern STOP = Pattern.compile(
		"\\b(stop|stay|wait|halt|freeze|hold (on|up|still|position)|don'?t move|stand still|stop following|don'?t follow|stay here|stay put|chill)\\b");
	private static final Pattern DEFEND = Pattern.compile(
		"\\b(defend|protect|guard|cover me|watch my back|help me fight|fight (them|it|this|that)|kill (it|them|that|those|this)|attack)\\b");
	private static final Pattern GIVE = Pattern.compile("\\b(give|hand|toss|throw|pass|drop) (me|over|it|them|the|your|those|some|all|\\d+)\\b");
	private static final Pattern GATHER_STRONG = Pattern.compile("\\b(gather|collect|chop|harvest|lumberjack|cut down|go get|farm)\\b");
	private static final Pattern GATHER_WEAK = Pattern.compile("\\b(get|fetch|bring|mine|cut|find)\\b");
	private static final Pattern COME_HERE = Pattern.compile(
		"\\b(come here|come to me|over here|get over here|come back|here boy|here girl|come over|to me|where are you)\\b");
	private static final Pattern FOLLOW = Pattern.compile("\\b(follow|come with|come along|with me|let'?s go|tag along|keep up|stick with me)\\b");
	private static final Pattern COME = Pattern.compile("\\bcome\\b");
	private static final Pattern STATUS = Pattern.compile(
		"\\b(status|what are you doing|what'?re you doing|whatcha doing|wyd|what'?s up|sup\\?|how are you|how r u|you ok|are you ok|you good|health|hungry|report|how'?s it going|what are you up to|doing\\?)\\b");
	private static final Pattern GREETING = Pattern.compile("^(hi|hello|hey|yo|sup|hiya|howdy|greetings|good (morning|evening|afternoon))\\b");
	private static final Pattern THANKS = Pattern.compile("\\b(thanks|thank you|thx|ty|good job|well done|nice work|great job|good (boy|girl|bot))\\b");

	private IntentParser() {
	}

	/** Result: which companions were addressed (names as given, or {@link #ALL}) and what was asked. */
	public record Parsed(List<String> addressed, Intent intent) {
	}

	public static Parsed parse(String message, Collection<String> companionNames) {
		String text = normalize(message);
		List<String> addressed = new ArrayList<>();
		String rest = text;
		for (String name : companionNames) {
			Pattern p = Pattern.compile("(^|\\W)@?" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(\\W|$)");
			Matcher m = p.matcher(rest);
			if (m.find()) {
				addressed.add(name);
				rest = m.replaceAll(" ");
			}
		}
		if (ALL_WORDS.matcher(rest).find() && addressed.isEmpty()) {
			addressed.add(ALL);
		}
		return new Parsed(addressed, intent(rest.trim()));
	}

	/** Lowercase, unify apostrophes, strip punctuation except ' and ?. */
	static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^a-z0-9'?@_ ]", " ").replaceAll("\\s+", " ").trim();
	}

	public static Intent intent(String text) {
		if (text.isEmpty()) {
			return Intent.of(Intent.Type.UNKNOWN);
		}
		if (DEFEND.matcher(text).find()) {
			return Intent.of(Intent.Type.DEFEND);
		}
		if (GIVE.matcher(text).find() && (ITEM_WORD.matcher(text).find() || text.contains("everything") || text.contains("stuff"))) {
			return new Intent(Intent.Type.GIVE, item(text, "logs"), count(text, 64));
		}
		boolean strong = GATHER_STRONG.matcher(text).find();
		boolean weak = GATHER_WEAK.matcher(text).find() && (ITEM_WORD.matcher(text).find() || NUMBER.matcher(text).find());
		if ((strong || weak) && !COME_HERE.matcher(text).find()) {
			return new Intent(Intent.Type.GATHER, item(text, "logs"), count(text, DEFAULT_COUNT));
		}
		if (STOP.matcher(text).find()) {
			return Intent.of(Intent.Type.STOP);
		}
		if (COME_HERE.matcher(text).find()) {
			return Intent.of(Intent.Type.COME_HERE);
		}
		if (FOLLOW.matcher(text).find()) {
			return Intent.of(Intent.Type.FOLLOW);
		}
		if (COME.matcher(text).find()) {
			return Intent.of(Intent.Type.COME_HERE);
		}
		if (STATUS.matcher(text).find()) {
			return Intent.of(Intent.Type.STATUS);
		}
		if (THANKS.matcher(text).find()) {
			return Intent.of(Intent.Type.THANKS);
		}
		if (GREETING.matcher(text).find()) {
			return Intent.of(Intent.Type.GREETING);
		}
		return Intent.of(Intent.Type.UNKNOWN);
	}

	private static String item(String text, String fallback) {
		Matcher m = ITEM_WORD.matcher(text);
		if (m.find()) {
			return m.group(1);
		}
		// "gather <something>": take the word after the verb so we can say we don't know it.
		Matcher v = Pattern.compile("\\b(?:gather|collect|get|fetch|bring|mine|find|harvest|farm)\\s+(?:me\\s+)?(?:some\\s+|\\d+\\s+)?([a-z]+)").matcher(text);
		if (v.find() && !NUMBER_WORDS.containsKey(v.group(1))) {
			return v.group(1);
		}
		return fallback;
	}

	private static int count(String text, int fallback) {
		Matcher m = NUMBER.matcher(text);
		if (m.find()) {
			return Math.max(1, Integer.parseInt(m.group(1)));
		}
		for (String w : text.split(" ")) {
			Integer n = NUMBER_WORDS.get(w);
			if (n != null && !w.equals("a") && !w.equals("an")) {
				return n;
			}
		}
		if (text.matches(".*\\b(a|an) (log|tree|block)\\b.*")) {
			return 1;
		}
		return fallback;
	}
}

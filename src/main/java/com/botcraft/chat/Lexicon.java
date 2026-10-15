package com.botcraft.chat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words and routines players taught the companions: "when I say lumber I mean logs",
 * "morning routine means get food then light the base". A taught phrase is swapped for its
 * meaning before a message is parsed, so a routine is just a longer meaning. Saved per world
 * (see LexiconStore); pure Java here, so it can be unit tested.
 */
public final class Lexicon {
	/** "when I say X I mean Y" / "if I say X, I mean Y". */
	private static final Pattern WHEN_I_SAY = Pattern.compile("^(?:from now on ,? )?(?:when|if|whenever) i say \"?(.+?)\"? ,? ?i (?:mean|want) \"?(.+?)\"?$");
	/** "X means Y" / "X is Y" (only short X). */
	private static final Pattern MEANS = Pattern.compile("^\"?([a-z][a-z0-9' ]{0,30}?)\"? (?:means|is short for|stands for) \"?(.+?)\"?$");
	/** "forget X" / "forget what X means". */
	private static final Pattern FORGET = Pattern.compile("^(?:forget|unlearn) (?:what )?\"?([a-z][a-z0-9' ]{0,30}?)\"?(?: means)?$");
	private static final int MAX_ENTRIES = 200;

	private static final Map<String, String> WORDS = new LinkedHashMap<>();

	private Lexicon() {
	}

	/** A teaching sentence: what to learn ("lumber" -> "logs"), or a word to forget (meaning null). */
	public record Lesson(String word, String meaning) {
		public boolean forget() {
			return meaning == null;
		}
	}

	/** If {@code text} (normalized, names removed) teaches a word, what it teaches; else null. */
	public static Lesson lesson(String text) {
		String t = text.trim().replaceAll("^(?:ok|okay|so|listen|hey) ,? ", "");
		Matcher m = WHEN_I_SAY.matcher(t);
		if (m.find()) {
			return clean(m.group(1), m.group(2));
		}
		m = FORGET.matcher(t);
		if (m.find() && !m.group(1).matches("(it|that|this|about it|everything)")) {
			String w = m.group(1).trim();
			return WORDS.containsKey(w) ? new Lesson(w, null) : null;
		}
		m = MEANS.matcher(t);
		if (m.find() && m.group(1).split(" ").length <= 3 && !m.group(1).matches("(what|that|this|it|which|who) .*|(what|that|this|it)")) {
			return clean(m.group(1), m.group(2));
		}
		return null;
	}

	private static Lesson clean(String word, String meaning) {
		String w = word.trim().replaceAll("^(the word|the phrase) ", "");
		String mean = meaning.trim().replaceAll("^(that you should|you should|to) ", "");
		if (w.isEmpty() || mean.isEmpty() || w.equals(mean) || w.length() > 32 || mean.length() > 160) {
			return null;
		}
		return new Lesson(w, mean);
	}

	/** Learn (or replace) a word. */
	public static void teach(String word, String meaning) {
		String key = word.toLowerCase(Locale.ROOT).trim();
		WORDS.remove(key);
		if (WORDS.size() >= MAX_ENTRIES) {
			WORDS.remove(WORDS.keySet().iterator().next()); // forget the oldest
		}
		WORDS.put(key, meaning.toLowerCase(Locale.ROOT).trim());
	}

	public static boolean forget(String word) {
		return WORDS.remove(word.toLowerCase(Locale.ROOT).trim()) != null;
	}

	/** Swap taught words for their meanings (whole words only; longest first; one pass, so no loops). */
	public static String apply(String text) {
		if (WORDS.isEmpty() || text.isEmpty()) {
			return text;
		}
		String out = text;
		java.util.List<String> keys = new java.util.ArrayList<>(WORDS.keySet());
		keys.sort((a, b) -> b.length() - a.length());
		StringBuilder pattern = new StringBuilder();
		for (String k : keys) {
			if (pattern.length() > 0) {
				pattern.append('|');
			}
			pattern.append(Pattern.quote(k));
		}
		Matcher m = Pattern.compile("(?<![a-z0-9'])(" + pattern + ")(?![a-z0-9'])").matcher(out);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, Matcher.quoteReplacement(WORDS.get(m.group(1))));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/** Everything taught so far (for saving and listing). */
	public static Map<String, String> all() {
		return java.util.Collections.unmodifiableMap(WORDS);
	}

	/** Replace everything (loading). */
	public static void load(Map<String, String> words) {
		WORDS.clear();
		if (words != null) {
			words.forEach(Lexicon::teach);
		}
	}

	public static void clear() {
		WORDS.clear();
	}
}

package com.mcfriends.chat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialogue lines, loaded from {@code mcfriends/dialogue.json} in the jar and optionally
 * overridden by {@code config/mcfriends-dialogue.json} (same format; a key there replaces the
 * default lines for that key). Placeholders like {player} are filled by the caller. Keys may
 * have ".brave" / ".timid" variants chosen by personality. No Minecraft classes, so it is unit
 * tested.
 */
public final class Templates {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
	private static final Map<String, List<String>> LINES = new HashMap<>();

	static {
		try (InputStream in = Templates.class.getResourceAsStream("/mcfriends/dialogue.json")) {
			if (in != null) {
				load(new InputStreamReader(in, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not load dialogue.json", e);
		}
	}

	private Templates() {
	}

	private static int load(Reader reader) {
		JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
		int n = 0;
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			if (e.getKey().startsWith("_") || !e.getValue().isJsonArray()) {
				continue;
			}
			List<String> lines = new ArrayList<>();
			for (JsonElement line : e.getValue().getAsJsonArray()) {
				lines.add(line.getAsString());
			}
			if (!lines.isEmpty()) {
				LINES.put(e.getKey(), lines);
				n++;
			}
		}
		return n;
	}

	/** Load the user's overrides if the file exists. @return number of keys loaded, or -1 if absent */
	public static int loadOverrides(Path file) throws IOException {
		if (!Files.exists(file)) {
			return -1;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return load(r);
		}
	}

	public static boolean has(String key) {
		return LINES.containsKey(key);
	}

	public static List<String> lines(String key) {
		return LINES.getOrDefault(key, List.of());
	}

	/** Back-compat helper: pick without repeat-avoidance. */
	public static String pick(String key, String variant, Map<String, String> vars, Random random) {
		return pick(key, variant, vars, random, l -> false);
	}

	/**
	 * Picks a line for the key (often preferring a personality variant when one exists),
	 * avoiding lines {@code avoid} rejects (recently said) when possible, and fills placeholders.
	 * Unknown placeholders are left as-is so missing data is visible in testing.
	 */
	public static String pick(String key, String variant, Map<String, String> vars, Random random, Predicate<String> avoid) {
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
		List<String> fresh = new ArrayList<>();
		for (String l : lines) {
			if (!avoid.test(l)) {
				fresh.add(l);
			}
		}
		List<String> from = fresh.isEmpty() ? lines : fresh;
		return from.get(random.nextInt(from.size()));
	}

	public static String fill(String line, Map<String, String> vars) {
		Matcher m = PLACEHOLDER.matcher(line);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = vars.get(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
		}
		m.appendTail(sb);
		String s = sb.toString().replaceAll("\\s+([.,!?])", "$1").replaceAll("\\s{2,}", " ").trim();
		// Capitalise the first letter (task descriptions start lowercase).
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}

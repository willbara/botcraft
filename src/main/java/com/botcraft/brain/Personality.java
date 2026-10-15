package com.botcraft.brain;

import com.botcraft.entity.CompanionData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Personality traits in [0, 1]. The first four are the original ones (stored as their own fields);
 * the rest live in {@link CompanionData#traits} and are filled in from the companion's UUID when
 * missing, so companions saved before they existed get a stable character too.
 *
 * <ul>
 *   <li><b>chattiness</b>: how often it speaks unprompted, template choice;</li>
 *   <li><b>bravery</b>: fight vs flee, how hurt before retreating;</li>
 *   <li><b>curiosity</b>: wandering and exploring;</li>
 *   <li><b>helpfulness</b>: eagerness to follow, work and answer requests;</li>
 *   <li><b>diligence</b>: sticking with a goal once started;</li>
 *   <li><b>aggression</b>: picking fights, chasing, holding grudges;</li>
 *   <li><b>caution</b>: how much risk and danger count when planning;</li>
 *   <li><b>sociability</b>: banter and seeking company;</li>
 *   <li><b>generosity</b>: how much it gives away;</li>
 *   <li><b>ambition</b>: weight on its own progression;</li>
 *   <li><b>loyalty</b>: sticking with its owner and faction;</li>
 *   <li><b>humor</b>: jokes and playful replies.</li>
 * </ul>
 * Pure Java (only CompanionData), so it can be unit tested.
 */
public record Personality(float chattiness, float bravery, float curiosity, float helpfulness,
	float diligence, float aggression, float caution, float sociability, float generosity, float ambition, float loyalty, float humor) {

	/** The traits kept in {@link CompanionData#traits}. */
	public static final List<String> EXTRA = List.of("diligence", "aggression", "caution", "sociability", "generosity", "ambition", "loyalty", "humor");

	public static Personality of(CompanionData d) {
		return new Personality(d.chattiness, d.bravery, d.curiosity, d.helpfulness,
			trait(d, "diligence"), trait(d, "aggression"), trait(d, "caution"), trait(d, "sociability"),
			trait(d, "generosity"), trait(d, "ambition"), trait(d, "loyalty"), trait(d, "humor"));
	}

	/** One of the extra traits, filled in (and saved) from the UUID if it's missing. */
	public static float trait(CompanionData d, String name) {
		if (d.traits == null) {
			d.traits = new LinkedHashMap<>();
		}
		Float v = d.traits.get(name);
		if (v == null) {
			v = seeded(d, name);
			d.traits.put(name, v);
		}
		return clamp(v);
	}

	/** A stable value for a trait: random, but the same for the same companion every time. */
	static float seeded(CompanionData d, String name) {
		long seed = (d.uuid == null ? (d.name == null ? 0 : d.name.hashCode()) : d.uuid.getMostSignificantBits() ^ d.uuid.getLeastSignificantBits())
			* 31 + name.hashCode();
		Random r = new Random(seed);
		r.nextFloat(); // the first value of a fresh Random varies little between nearby seeds
		return 0.2F + r.nextFloat() * 0.6F;
	}

	/** Every trait by name (for /companion info and archetypes). */
	public Map<String, Float> all() {
		Map<String, Float> m = new LinkedHashMap<>();
		m.put("chattiness", chattiness);
		m.put("bravery", bravery);
		m.put("curiosity", curiosity);
		m.put("helpfulness", helpfulness);
		m.put("diligence", diligence);
		m.put("aggression", aggression);
		m.put("caution", caution);
		m.put("sociability", sociability);
		m.put("generosity", generosity);
		m.put("ambition", ambition);
		m.put("loyalty", loyalty);
		m.put("humor", humor);
		return m;
	}

	public String summary() {
		return String.format(Locale.ROOT, "chatty %.1f, brave %.1f, curious %.1f, helpful %.1f, diligent %.1f, aggressive %.1f, cautious %.1f, "
			+ "sociable %.1f, generous %.1f, ambitious %.1f, loyal %.1f, funny %.1f", chattiness, bravery, curiosity, helpfulness,
			diligence, aggression, caution, sociability, generosity, ambition, loyalty, humor);
	}

	// ---- what the traits do (one place, so they're easy to tune) ---------------------------------

	/** Health share below which a companion retreats from a fight (brave ones hold on, cautious ones don't). */
	public float retreatHealth() {
		return 0.4F - bravery * 0.15F + (caution - 0.5F) * 0.1F;
	}

	/** Multiplier on how risky a method looks to the planner (0.85 .. 1.15). */
	public double riskWeight() {
		return 0.85 + 0.3 * caution;
	}

	/** Extra score a goal already being worked on gets, so diligent companions see things through. */
	public float commitment() {
		return 0.04F + 0.08F * diligence;
	}

	/** How much of something it keeps before giving any away (generous ones keep less). */
	public int reserve(boolean tool) {
		if (tool) {
			return 1;
		}
		return generosity > 0.7F ? 1 : generosity < 0.3F ? 4 : 2;
	}

	/** Banter chance multiplier (sociable companions chat with each other more). */
	public float banter() {
		return 0.6F + 0.8F * sociability;
	}

	// ---- archetypes ------------------------------------------------------------------------------

	/** Preset characters for /companion archetype: trait name -> value. */
	public static final Map<String, Map<String, Float>> ARCHETYPES = archetypes();

	private static Map<String, Map<String, Float>> archetypes() {
		Map<String, Map<String, Float>> m = new LinkedHashMap<>();
		m.put("miner", Map.of("diligence", 0.85F, "curiosity", 0.6F, "caution", 0.5F, "ambition", 0.7F, "chattiness", 0.35F));
		m.put("farmer", Map.of("diligence", 0.8F, "helpfulness", 0.8F, "caution", 0.7F, "aggression", 0.2F, "generosity", 0.75F));
		m.put("guard", Map.of("bravery", 0.9F, "aggression", 0.7F, "loyalty", 0.85F, "caution", 0.35F, "curiosity", 0.2F));
		m.put("scout", Map.of("curiosity", 0.95F, "bravery", 0.6F, "caution", 0.45F, "sociability", 0.4F));
		m.put("builder", Map.of("diligence", 0.85F, "helpfulness", 0.75F, "curiosity", 0.3F, "caution", 0.6F));
		m.put("trader", Map.of("sociability", 0.85F, "chattiness", 0.8F, "generosity", 0.6F, "humor", 0.6F));
		m.put("leader", Map.of("ambition", 0.85F, "loyalty", 0.7F, "sociability", 0.75F, "bravery", 0.7F, "helpfulness", 0.6F));
		m.put("hothead", Map.of("aggression", 0.95F, "bravery", 0.85F, "caution", 0.1F, "diligence", 0.3F, "humor", 0.5F));
		m.put("coward", Map.of("bravery", 0.1F, "caution", 0.9F, "aggression", 0.1F, "chattiness", 0.6F));
		m.put("joker", Map.of("humor", 0.95F, "chattiness", 0.9F, "sociability", 0.85F, "diligence", 0.35F));
		return m;
	}

	/** Apply an archetype (its traits; the others keep their values). @return false if unknown */
	public static boolean applyArchetype(CompanionData d, String name) {
		Map<String, Float> preset = ARCHETYPES.get(name.toLowerCase(Locale.ROOT));
		if (preset == null) {
			return false;
		}
		for (Map.Entry<String, Float> e : preset.entrySet()) {
			set(d, e.getKey(), e.getValue());
		}
		d.archetype = name.toLowerCase(Locale.ROOT);
		return true;
	}

	/** Set any trait by name. @return false if there's no such trait */
	public static boolean set(CompanionData d, String name, float value) {
		float v = clamp(value);
		switch (name) {
			case "chattiness" -> d.chattiness = v;
			case "bravery" -> d.bravery = v;
			case "curiosity" -> d.curiosity = v;
			case "helpfulness" -> d.helpfulness = v;
			default -> {
				if (!EXTRA.contains(name)) {
					return false;
				}
				if (d.traits == null) {
					d.traits = new LinkedHashMap<>();
				}
				d.traits.put(name, v);
			}
		}
		return true;
	}

	/**
	 * Experience nudges traits a little, never more than {@code DRIFT_LIMIT} from where they
	 * started (the first value seen is remembered in {@link CompanionData#traitBase}).
	 */
	public static void drift(CompanionData d, String name, float by) {
		Personality p = of(d);
		Float current = p.all().get(name);
		if (current == null) {
			return;
		}
		if (d.traitBase == null) {
			d.traitBase = new LinkedHashMap<>();
		}
		float base = d.traitBase.computeIfAbsent(name, k -> current);
		float next = Math.max(base - DRIFT_LIMIT, Math.min(base + DRIFT_LIMIT, current + by));
		set(d, name, next);
	}

	public static final float DRIFT_LIMIT = 0.2F;

	private static float clamp(float v) {
		return Math.max(0.0F, Math.min(1.0F, v));
	}
}

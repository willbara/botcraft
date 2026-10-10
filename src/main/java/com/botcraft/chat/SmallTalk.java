package com.botcraft.chat;

import com.botcraft.Companion;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chit-chat replies: jokes, compliments, "who are you", the time, favourite things and
 * opinions. Favourites and opinions are derived from the companion's name, so each companion
 * has stable tastes ("Bob hates creepers, loves cake") that it repeats consistently.
 */
public final class SmallTalk {
	private static final Map<String, List<String>> FAVORITES = Map.of(
		"food", List.of("cake", "cooked beef", "golden carrots", "pumpkin pie", "baked potatoes", "cookies", "sweet berries", "mushroom stew"),
		"block", List.of("oak planks", "diamond ore", "moss", "glowstone", "cherry leaves", "amethyst", "bookshelves", "honey blocks"),
		"mob", List.of("axolotls", "wolves", "cats", "bees", "foxes", "iron golems", "frogs", "allays"),
		"biome", List.of("cherry groves", "jungles", "mushroom fields", "taigas", "savannas", "the badlands", "flower forests", "snowy plains"),
		"color", List.of("green", "blue", "purple", "orange", "lime", "cyan", "red", "pink"),
		"tool", List.of("my pickaxe", "my trusty axe", "the shovel, honestly", "a good sword", "the fishing rod"));
	private static final List<String> LIKED = List.of("diamond", "emerald", "cat", "dog", "wolf", "wolves", "axolotl", "cake", "villager",
		"bee", "fox", "foxes", "horse", "iron golem", "torch", "bed", "you", "mining", "cookie", "music");
	private static final List<String> DISLIKED = List.of("creeper", "zombie", "skeleton", "spider", "phantom", "enderm", "witch",
		"pillager", "lava", "gravel", "drowned", "slime", "silverfish", "warden", "rain", "dark");

	private SmallTalk() {
	}

	private static int hash(Companion c, String s) {
		return Math.floorMod((c.data.uuid.getMostSignificantBits() + s.hashCode()) * 31 + c.name().hashCode(), 1 << 20);
	}

	/** Which favourites list a word refers to. */
	static String category(String thing) {
		String t = thing.toLowerCase(Locale.ROOT);
		if (t.matches(".*(food|snack|meal|dish|eat).*")) {
			return "food";
		}
		if (t.matches(".*(block|material).*")) {
			return "block";
		}
		if (t.matches(".*(mob|animal|creature|pet).*")) {
			return "mob";
		}
		if (t.matches(".*(biome|place|area).*")) {
			return "biome";
		}
		if (t.matches(".*(colou?r).*")) {
			return "color";
		}
		if (t.matches(".*(tool|weapon|item).*")) {
			return "tool";
		}
		return "block";
	}

	public static String favorite(Companion c, String thing) {
		List<String> list = FAVORITES.get(category(thing));
		return list.get(hash(c, category(thing)) % list.size());
	}

	/** "like", "dislike" or "meh" about a topic, consistent per companion. */
	static String opinion(Companion c, String topic) {
		String t = topic.toLowerCase(Locale.ROOT);
		for (String d : DISLIKED) {
			if (t.contains(d)) {
				return c.data.bravery > 0.8F && hash(c, t) % 3 == 0 ? "meh" : "dislike";
			}
		}
		for (String l : LIKED) {
			if (t.contains(l)) {
				return "like";
			}
		}
		return switch (hash(c, t) % 3) {
			case 0 -> "like";
			case 1 -> "dislike";
			default -> "meh";
		};
	}

	static String timePhrase(long timeOfDay, long totalTime) {
		long t = timeOfDay % 24000;
		String part;
		if (t < 1000) {
			part = "early morning";
		} else if (t < 6000) {
			part = "morning";
		} else if (t < 7000) {
			part = "around noon";
		} else if (t < 11000) {
			part = "afternoon";
		} else if (t < 13000) {
			part = "evening";
		} else if (t < 18000) {
			part = "night";
		} else if (t < 19000) {
			part = "midnight";
		} else if (t < 23000) {
			part = "late at night";
		} else {
			part = "almost dawn";
		}
		return part + " on day " + (totalTime / 24000 + 1);
	}

	/** Reply to a small-talk intent. */
	public static void reply(Companion c, @Nullable ServerPlayerEntity sender, Intent intent) {
		Speech s = c.speech();
		Map<String, String> vars = new HashMap<>();
		switch (intent.type()) {
			case JOKE -> s.reply("talk.joke", sender, vars);
			case COMPLIMENT -> s.reply("talk.compliment", sender, vars);
			case INSULT -> s.reply("talk.insult", sender, vars);
			case WHO -> s.reply("talk.who", sender, vars);
			case LOVE -> s.reply("talk.love", sender, vars);
			case BYE -> s.reply("talk.bye", sender, vars);
			case SORRY -> s.reply("talk.sorry", sender, vars);
			case FEELING -> s.reply("talk.feeling", sender, vars);
			case TIME -> {
				var w = c.player().getWorld();
				vars.put("time", timePhrase(w.getTimeOfDay(), w.getTimeOfDay()));
				s.reply("talk.time", sender, vars);
			}
			case FAVORITE -> {
				String thing = intent.item().isEmpty() ? "thing" : intent.item();
				vars.put("thing", thing);
				vars.put("fav", favorite(c, thing));
				s.reply("talk.favorite", sender, vars);
			}
			case OPINION -> {
				String topic = intent.item().isEmpty() ? "that" : intent.item();
				vars.put("topic", topic);
				s.reply("talk." + opinion(c, topic), sender, vars);
			}
			default -> {
			}
		}
	}
}

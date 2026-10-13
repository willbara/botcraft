package com.botcraft.entity;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Random two-word usernames for batches of companions ("AgentCreeper", "SneakyBadger"): a first
 * word and a second word in CamelCase, at most 16 characters (Minecraft's limit). About 150 x 150
 * pairs; when a name is taken a short number goes on the end, so there's always another one.
 */
public final class BatchNames {
	private static final List<String> FIRST = List.of(
		"Agent", "Sneaky", "Brave", "Lucky", "Sleepy", "Grumpy", "Happy", "Shy", "Bold", "Swift", "Silent", "Mighty", "Tiny", "Giant",
		"Clever", "Witty", "Rusty", "Shiny", "Golden", "Iron", "Stone", "Copper", "Diamond", "Emerald", "Redstone", "Obsidian", "Mossy",
		"Frosty", "Snowy", "Sunny", "Stormy", "Misty", "Cloudy", "Rainy", "Windy", "Dusty", "Sandy", "Muddy", "Leafy", "Woody",
		"Captain", "Doctor", "Professor", "Sir", "Lady", "Lord", "Chief", "Major", "Sergeant", "Baron", "Duke", "King", "Queen",
		"Prince", "Ninja", "Pirate", "Wizard", "Knight", "Ranger", "Scout", "Miner", "Crafty", "Builder", "Digger", "Farmer", "Hunter",
		"Night", "Dawn", "Dusk", "Moon", "Star", "Sky", "Ocean", "River", "Forest", "Desert", "Jungle", "Tundra", "Cave", "Nether",
		"Ender", "Lava", "Magma", "Crystal", "Amber", "Jade", "Ruby", "Onyx", "Silver", "Cobalt", "Crimson", "Azure", "Violet", "Scarlet",
		"Fuzzy", "Fluffy", "Bouncy", "Wobbly", "Jolly", "Zesty", "Spicy", "Salty", "Sweet", "Sour", "Crispy", "Toasty", "Chilly",
		"Hasty", "Lazy", "Busy", "Nifty", "Quirky", "Sly", "Wild", "Calm", "Fierce", "Gentle", "Noble", "Humble", "Proud", "Loyal",
		"Rapid", "Turbo", "Hyper", "Mega", "Ultra", "Super", "Pixel", "Cyber", "Robo", "Laser", "Rocket", "Thunder", "Blaze", "Frost",
		"Shadow", "Ghost", "Echo", "Lone", "Old", "Young", "Wise", "Odd", "Mystic", "Cosmic", "Atomic", "Sonic", "Epic", "Secret");
	private static final List<String> SECOND = List.of(
		"Creeper", "Zombie", "Skeleton", "Spider", "Enderman", "Slime", "Witch", "Blaze", "Ghast", "Golem", "Villager", "Pillager",
		"Piglin", "Strider", "Axolotl", "Allay", "Warden", "Phantom", "Guardian", "Shulker", "Wither", "Dragon", "Sniffer", "Camel",
		"Badger", "Otter", "Fox", "Wolf", "Bear", "Panda", "Llama", "Goat", "Sheep", "Cow", "Pig", "Chicken", "Rabbit", "Turtle",
		"Parrot", "Bee", "Frog", "Squid", "Dolphin", "Cod", "Salmon", "Ocelot", "Cat", "Horse", "Mule", "Donkey", "Bat", "Owl",
		"Falcon", "Hawk", "Raven", "Crow", "Sparrow", "Moose", "Bison", "Lynx", "Tiger", "Lion", "Mole", "Ferret", "Beaver", "Gecko",
		"Pickaxe", "Shovel", "Axe", "Sword", "Shield", "Bow", "Arrow", "Trident", "Anvil", "Furnace", "Chest", "Torch", "Lantern",
		"Bucket", "Compass", "Map", "Clock", "Saddle", "Lead", "Hopper", "Piston", "Lever", "Beacon", "Cauldron", "Barrel", "Bell",
		"Cookie", "Melon", "Pumpkin", "Carrot", "Potato", "Beetroot", "Apple", "Bread", "Cake", "Pie", "Stew", "Kelp", "Berry",
		"Pebble", "Boulder", "Nugget", "Ingot", "Gem", "Shard", "Brick", "Block", "Plank", "Log", "Stick", "Sapling", "Mushroom",
		"Cactus", "Fern", "Vine", "Moss", "Bamboo", "Coral", "Sponge", "Feather", "Bone", "String", "Wool", "Pearl", "Rod", "Dust",
		"Miner", "Crafter", "Builder", "Digger", "Smith", "Ranger", "Walker", "Runner", "Jumper", "Seeker", "Keeper", "Rider",
		"Breaker", "Maker", "Finder", "Hopper", "Dreamer", "Wanderer", "Sailor", "Scout", "Bandit", "Rogue", "Bard", "Monk", "Sage");

	private static final int MAX = 16;

	private BatchNames() {
	}

	/**
	 * A random name that {@code taken} doesn't reject (checked case-insensitively by the caller).
	 * Tries plain pairs first, then pairs with a number on the end.
	 */
	public static String next(Random random, Predicate<String> taken) {
		for (int attempt = 0; attempt < 200; attempt++) {
			String name = pair(random);
			if (name != null && !taken.test(name)) {
				return name;
			}
		}
		for (int attempt = 0; ; attempt++) {
			String base = pair(random);
			if (base == null) {
				continue;
			}
			int digits = 2 + attempt / 200;
			String suffix = String.valueOf(random.nextInt((int) Math.pow(10, Math.min(digits, 6))));
			String name = base.length() + suffix.length() <= MAX ? base + suffix : base.substring(0, MAX - suffix.length()) + suffix;
			if (!taken.test(name)) {
				return name;
			}
		}
	}

	private static String pair(Random random) {
		String a = FIRST.get(random.nextInt(FIRST.size()));
		String b = SECOND.get(random.nextInt(SECOND.size()));
		if (a.equals(b)) {
			return null;
		}
		String name = a + b;
		return name.length() <= MAX ? name : null;
	}

	/** How many plain pairs there are (for the docs and tests). */
	public static int pairs() {
		return FIRST.size() * SECOND.size();
	}
}

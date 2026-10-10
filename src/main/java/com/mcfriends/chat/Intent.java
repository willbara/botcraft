package com.mcfriends.chat;

/**
 * A parsed chat request. {@code item} is the raw item phrase ("wooden pickaxe", "coal", "") for
 * acquisition-type intents (GATHER/MINE/CRAFT/BRING/GIVE/FOUND); the server resolves it.
 * {@code count} is -1 when the player didn't say how many. {@code target} names another
 * companion/player the request is about ("go to steve", "give alex the logs"), or "".
 */
public record Intent(Type type, String item, int count, String target) {
	public Intent(Type type, String item, int count) {
		this(type, item, count, "");
	}

	public Intent withTarget(String t) {
		return new Intent(type, item, count, t);
	}

	public enum Type {
		FOLLOW, STOP, COME_HERE, DEFEND, CANCEL, FREE, SCATTER,
		GATHER, MINE, CRAFT, BRING, GIVE, HUNT, SMELT,
		HOME_SET, HOME_GO, SLEEP, CHEST_SET, DEPOSIT, STOCK, DANCE, LIGHT,
		WHERE, INVENTORY, STATS, FOUND, HELP, STATUS,
		JOKE, COMPLIMENT, INSULT, WHO, LOVE, BYE, TIME, FAVORITE, OPINION, FEELING, SORRY,
		GREETING, THANKS, UNKNOWN;

		/** Chit-chat handled by SmallTalk. */
		public boolean isSmallTalk() {
			return switch (this) {
				case JOKE, COMPLIMENT, INSULT, WHO, LOVE, BYE, TIME, FAVORITE, OPINION, FEELING, SORRY -> true;
				default -> false;
			};
		}

		/** Orders change behaviour; only the owner may give them. */
		public boolean isOrder() {
			return switch (this) {
				case FOLLOW, STOP, COME_HERE, DEFEND, CANCEL, FREE, SCATTER, GATHER, MINE, CRAFT, BRING, GIVE, HUNT, SMELT, HOME_SET, HOME_GO, SLEEP,
					CHEST_SET, DEPOSIT, LIGHT -> true;
				default -> false;
			};
		}
	}

	public static Intent of(Type type) {
		return new Intent(type, "", -1, "");
	}
}

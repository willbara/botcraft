package com.mcfriends.chat;

/**
 * A parsed chat request. {@code item} is the raw item phrase ("wooden pickaxe", "coal", "") for
 * acquisition-type intents (GATHER/MINE/CRAFT/BRING/GIVE/FOUND); the server resolves it.
 * {@code count} is -1 when the player didn't say how many.
 */
public record Intent(Type type, String item, int count) {
	public enum Type {
		FOLLOW, STOP, COME_HERE, DEFEND, CANCEL, FREE,
		GATHER, MINE, CRAFT, BRING, GIVE, HUNT, SMELT, FARM, HARVEST,
		HOME_SET, HOME_GO, SLEEP,
		WHERE, INVENTORY, STATS, FOUND, HELP, STATUS,
		GREETING, THANKS, UNKNOWN;

		/** Orders change behaviour; only the owner may give them. */
		public boolean isOrder() {
			return switch (this) {
				case FOLLOW, STOP, COME_HERE, DEFEND, CANCEL, FREE, GATHER, MINE, CRAFT, BRING, GIVE, HUNT, SMELT, FARM, HARVEST, HOME_SET, HOME_GO, SLEEP -> true;
				default -> false;
			};
		}
	}

	public static Intent of(Type type) {
		return new Intent(type, "", -1);
	}
}

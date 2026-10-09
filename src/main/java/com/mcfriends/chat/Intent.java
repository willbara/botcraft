package com.mcfriends.chat;

/** A parsed chat request. {@code item}/{@code count} are only set for GATHER and GIVE. */
public record Intent(Type type, String item, int count) {
	public enum Type {
		FOLLOW, STOP, COME_HERE, GATHER, GIVE, DEFEND, STATUS, GREETING, THANKS, UNKNOWN;

		/** Orders change behaviour; only the owner may give them. */
		public boolean isOrder() {
			return this == FOLLOW || this == STOP || this == COME_HERE || this == GATHER || this == GIVE || this == DEFEND;
		}
	}

	public static Intent of(Type type) {
		return new Intent(type, "", 0);
	}
}

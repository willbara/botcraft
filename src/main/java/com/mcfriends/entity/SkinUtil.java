package com.mcfriends.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Offline skins. Without network access we cannot fetch signed skin textures, but the 1.20.1
 * client picks one of 18 built-in skins from {@code floorMod(uuid.hashCode(), 18)} when a
 * profile has no textures (DefaultSkinHelper). We choose the UUID so the hash lands on the
 * requested skin. Order below mirrors DefaultSkinHelper.SKINS in 1.20.1: 9 slim, then 9 wide.
 */
public final class SkinUtil {
	public static final List<String> NAMES = List.of("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");
	private static final int SKIN_COUNT = NAMES.size() * 2;

	private SkinUtil() {
	}

	/** All accepted skin ids, e.g. "steve", "steve_slim", "kai_wide". */
	public static List<String> skinIds() {
		List<String> ids = new ArrayList<>();
		for (String n : NAMES) {
			ids.add(n);
			ids.add(n + "_slim");
			ids.add(n + "_wide");
		}
		return ids;
	}

	/**
	 * Index into the client's default skin table, or -1 if unknown. A bare name uses the model
	 * that character is best known for (alex slim, everyone else wide).
	 */
	public static int skinIndex(String skin) {
		String s = skin.toLowerCase(Locale.ROOT);
		boolean slim;
		if (s.endsWith("_slim")) {
			slim = true;
			s = s.substring(0, s.length() - 5);
		} else if (s.endsWith("_wide")) {
			slim = false;
			s = s.substring(0, s.length() - 5);
		} else {
			slim = s.equals("alex");
		}
		int i = NAMES.indexOf(s);
		if (i < 0) {
			return -1;
		}
		return slim ? i : i + NAMES.size();
	}

	public static int indexForUuid(UUID uuid) {
		return Math.floorMod(uuid.hashCode(), SKIN_COUNT);
	}

	/**
	 * Stable UUID for a companion name whose default skin is {@code skinIndex} (or any skin if
	 * negative). Name-based (v3) in our own namespace so it never collides with vanilla offline
	 * player UUIDs ("OfflinePlayer:" + name).
	 */
	public static UUID uuidFor(String name, int skinIndex) {
		UUID base = UUID.nameUUIDFromBytes(("MCFriends:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
		if (skinIndex < 0) {
			return base;
		}
		long msb = base.getMostSignificantBits();
		long lsb = base.getLeastSignificantBits();
		// Only touch the low bits of the least significant half; version/variant bits stay intact.
		for (long i = 0; i < 4096; i++) {
			UUID candidate = new UUID(msb, lsb ^ i);
			if (indexForUuid(candidate) == skinIndex) {
				return candidate;
			}
		}
		return base; // unreachable in practice: 4096 tries over 18 buckets
	}
}

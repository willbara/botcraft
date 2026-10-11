package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every companion can see of the others, like players glancing at each other: where each
 * one is, what it's doing, and the next few blocks of the path it's walking; plus which dropped
 * items someone is already going for. Each companion publishes itself a few times a second.
 * Path finding steers around the cells others stand in or are about to walk through, and item
 * pickers leave claimed items alone, so companions working side by side stop getting in each
 * other's way. ({@link Claims} still says where each one is <em>working</em>.)
 */
public final class Blackboard {
	/** One companion as the others see it. {@code path} holds the BlockPos longs of its next steps. */
	public record Entry(String name, String dim, BlockPos feet, String doing, long at, long[] path) {
	}

	/** How many upcoming path cells each companion publishes. */
	public static final int PATH_CELLS = 4;
	/** Entries older than this are ignored (the companion left, died, or stopped publishing). */
	private static final long STALE_TICKS = 40;
	/** An item claim lapses unless renewed (the claimer gave up or got distracted). */
	private static final long ITEM_CLAIM_TICKS = 200;

	private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
	private record ItemClaim(String by, long until) {
	}
	private static final Map<UUID, ItemClaim> ITEMS = new ConcurrentHashMap<>();

	private Blackboard() {
	}

	/** Publish where we are, what we're doing and where we're going (called every few ticks). */
	public static void publish(Companion c) {
		CompanionPlayer p = c.player();
		if (p == null) {
			ENTRIES.remove(c.name());
			return;
		}
		List<BlockPos> next = c.nav().upcoming(PATH_CELLS);
		long[] path = new long[next.size()];
		for (int i = 0; i < path.length; i++) {
			path[i] = next.get(i).asLong();
		}
		ENTRIES.put(c.name(), new Entry(c.name(), c.dimension(), com.botcraft.nav.Navigator.feet(p), c.brain().describe(), c.now(), path));
	}

	public static void leave(Companion c) {
		ENTRIES.remove(c.name());
		ITEMS.values().removeIf(cl -> cl.by().equals(c.name()));
	}

	/** The others in our dimension within {@code radius}, nearest first. */
	public static List<Entry> others(Companion self, BlockPos near, double radius) {
		List<Entry> out = new ArrayList<>();
		double r2 = radius * radius;
		for (Entry e : ENTRIES.values()) {
			if (!e.name().equals(self.name()) && self.now() - e.at() <= STALE_TICKS && e.dim().equals(self.dimension())
				&& e.feet().getSquaredDistance(near) <= r2) {
				out.add(e);
			}
		}
		out.sort(Comparator.comparingDouble(e -> e.feet().getSquaredDistance(near)));
		return out;
	}

	/** Everyone, for /companion colony. */
	public static List<Entry> all(long now) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ENTRIES.values()) {
			if (now - e.at() <= STALE_TICKS) {
				out.add(e);
			}
		}
		out.sort(Comparator.comparing(Entry::name));
		return out;
	}

	/**
	 * Feet cells others stand in or are about to step into, for path finding to treat as busy:
	 * passable, but better avoided.
	 */
	public static LongOpenHashSet busyCells(Companion self, BlockPos near, double radius) {
		LongOpenHashSet out = new LongOpenHashSet();
		for (Entry e : others(self, near, radius)) {
			out.add(e.feet().asLong());
			for (long cell : e.path()) {
				out.add(cell);
			}
		}
		return out;
	}

	// ---- dropped items -------------------------------------------------------------------------

	/** Going for this item: others leave it be for a while. */
	public static void claimItem(Companion c, ItemEntity e) {
		ITEMS.put(e.getUuid(), new ItemClaim(c.name(), c.now() + ITEM_CLAIM_TICKS));
	}

	/** Someone else is already going for this item. */
	public static boolean claimedByOther(Companion c, ItemEntity e) {
		if (!com.botcraft.util.Experiments.on("itemClaims")) {
			return false;
		}
		ItemClaim cl = ITEMS.get(e.getUuid());
		if (cl == null) {
			return false;
		}
		if (c.now() > cl.until() || !e.isAlive()) {
			ITEMS.remove(e.getUuid());
			return false;
		}
		return !cl.by().equals(c.name());
	}

	/** For tests: forget everything. */
	public static void clear() {
		ENTRIES.clear();
		ITEMS.clear();
	}
}

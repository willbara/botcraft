package com.botcraft.faction;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import com.botcraft.util.Experiments;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Land, the way a board game of conquest has it. Each faction with a base holds the chunks round
 * it ({@link #CORE_RADIUS} chunks each way to start) and spreads out over time into unclaimed
 * chunks on its frontier, faster the more members it has, up to a size its numbers can hold.
 * <ul>
 *   <li><b>Borders:</b> when two factions' land first touches, they meet: the leaders size each
 *       other up and it's war or a truce ({@link Diplomacy#contact}). Wars, raids and declarations
 *       of war only happen between factions whose borders touch.</li>
 *   <li><b>Occupation:</b> at war, a border chunk of the enemy's with our fighters standing in it
 *       and none of theirs becomes ours (never the chunks round their base).</li>
 *   <li><b>Peace terms:</b> the side that sues for peace gives up border land
 *       ({@link #cede}).</li>
 * </ul>
 * Claims are saved with the faction. Off with {@code territory: false}: then everyone is everyone's
 * neighbour, as before.
 */
public final class Territory {
	private static final long CHECK_TICKS = 20 * 90;
	/** Chunks each way round the base held from the start (and never lost). */
	public static final int CORE_RADIUS = 2;
	/** Land stops this many chunks from the base. */
	private static final int MAX_REACH = 14;
	private static final Random RANDOM = new Random();
	/** Chunk -> faction holding it, rebuilt when claims change. */
	private static final Map<Long, String> OWNER = new HashMap<>();
	private static boolean indexDirty = true;

	private Territory() {
	}

	public static boolean on() {
		return Factions.enabled() && CompanionConfig.get().territory && Experiments.on("territory");
	}

	/** Called every server tick by the manager. */
	public static void tick(CompanionManager m, long now) {
		if (now % CHECK_TICKS != 37 || !on()) {
			return;
		}
		index();
		for (Faction f : Factions.groups()) {
			Colony colony = Colony.of(f.id);
			if (colony == null || !colony.hasBase() || colony.world() == null) {
				continue;
			}
			try {
				settle(f, colony);
				grow(f, colony);
				occupy(f, colony, m);
			} catch (RuntimeException e) {
				com.botcraft.Botcraft.LOG.error("Error updating {}'s land", f.id, e);
			}
		}
		contacts(now);
	}

	// ---- reading -------------------------------------------------------------------------------------

	/** Who holds the chunk this block is in (null: nobody, or territory is off). */
	@Nullable
	public static String owner(ServerWorld w, BlockPos pos) {
		if (!on()) {
			return null;
		}
		index();
		String id = OWNER.get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
		Faction f = id == null ? null : Factions.get(id);
		return f != null && f.claimDim.equals(dim(w)) ? id : null;
	}

	/** This block is on the faction's land. */
	public static boolean inside(String faction, ServerWorld w, BlockPos pos) {
		return faction.equals(owner(w, pos));
	}

	/** Chunks held. */
	public static int size(Faction f) {
		return f.claims.size();
	}

	/** The two factions' land touches (or territory is off, when everyone counts as a neighbour). */
	public static boolean neighbours(String a, String b) {
		if (!on()) {
			return true;
		}
		Faction fa = Factions.get(a);
		Faction fb = Factions.get(b);
		return fa != null && fb != null && (fa.met.contains(b) && touching(fa, fb));
	}

	private static boolean touching(Faction a, Faction b) {
		if (!a.claimDim.equals(b.claimDim) || a.claims.isEmpty() || b.claims.isEmpty()) {
			return false;
		}
		Set<Long> theirs = new HashSet<>(b.claims);
		for (long c : a.claims) {
			int x = ChunkPos.getPackedX(c);
			int z = ChunkPos.getPackedZ(c);
			if (theirs.contains(ChunkPos.toLong(x + 1, z)) || theirs.contains(ChunkPos.toLong(x - 1, z))
				|| theirs.contains(ChunkPos.toLong(x, z + 1)) || theirs.contains(ChunkPos.toLong(x, z - 1))) {
				return true;
			}
		}
		return false;
	}

	// ---- changing ------------------------------------------------------------------------------------

	/** A new base (or one moved to another dimension): hold the chunks round it. */
	private static void settle(Faction f, Colony colony) {
		String dim = dim(colony.world());
		BlockPos base = colony.base();
		ChunkPos home = new ChunkPos(base);
		if (f.claimDim.equals(dim) && f.claims.contains(home.toLong())) {
			return;
		}
		f.claims.clear();
		f.claimDim = dim;
		index();
		for (int dx = -CORE_RADIUS; dx <= CORE_RADIUS; dx++) {
			for (int dz = -CORE_RADIUS; dz <= CORE_RADIUS; dz++) {
				long c = ChunkPos.toLong(home.x + dx, home.z + dz);
				String other = OWNER.get(c);
				if (other == null || other.equals(f.id) || Factions.get(other) == null || !Factions.get(other).claimDim.equals(dim)) {
					f.claims.add(c);
				}
			}
		}
		changed();
		AiLog.log("faction", "{} holds {} chunks round its base at {}", f.id, f.claims.size(), base.toShortString());
	}

	/** Most land the faction's numbers can hold. */
	static int cap(int members) {
		return 25 + 8 * members;
	}

	/** Spread into a few unclaimed chunks on the frontier, nearest the base first. */
	private static void grow(Faction f, Colony colony) {
		int members = Factions.members(f.id).size();
		if (members == 0 || f.claims.size() >= cap(members)) {
			return;
		}
		ChunkPos home = new ChunkPos(colony.base());
		int n = 1 + members / 4;
		for (int i = 0; i < n && f.claims.size() < cap(members); i++) {
			Long best = null;
			double bestScore = Double.NEGATIVE_INFINITY;
			Set<Long> mine = new HashSet<>(f.claims);
			for (long c : frontier(f)) {
				if (OWNER.containsKey(c)) {
					continue;
				}
				int x = ChunkPos.getPackedX(c);
				int z = ChunkPos.getPackedZ(c);
				double d = Math.sqrt((x - home.x) * (x - home.x) + (z - home.z) * (z - home.z));
				if (d > MAX_REACH) {
					continue;
				}
				int around = 0;
				for (long nb : neighboursOf(c)) {
					if (mine.contains(nb)) {
						around++;
					}
				}
				double score = -d + 0.6 * around + RANDOM.nextDouble() * 1.5;
				if (score > bestScore) {
					bestScore = score;
					best = c;
				}
			}
			if (best == null) {
				return;
			}
			f.claims.add(best);
			OWNER.put(best, f.id);
		}
		Factions.markDirty();
	}

	/** Chunks next to ours that aren't ours. */
	private static List<Long> frontier(Faction f) {
		Set<Long> mine = new HashSet<>(f.claims);
		Set<Long> out = new HashSet<>();
		for (long c : f.claims) {
			for (long nb : neighboursOf(c)) {
				if (!mine.contains(nb)) {
					out.add(nb);
				}
			}
		}
		return new ArrayList<>(out);
	}

	private static long[] neighboursOf(long c) {
		int x = ChunkPos.getPackedX(c);
		int z = ChunkPos.getPackedZ(c);
		return new long[]{ChunkPos.toLong(x + 1, z), ChunkPos.toLong(x - 1, z), ChunkPos.toLong(x, z + 1), ChunkPos.toLong(x, z - 1)};
	}

	/**
	 * At war: enemy border chunks (touching our land, not round their base) where our members
	 * stand and none of theirs do become ours.
	 */
	private static void occupy(Faction f, Colony colony, CompanionManager m) {
		Map<Long, Integer> ours = new HashMap<>();
		Map<Long, Set<String>> others = new HashMap<>();
		for (Companion c : m.all()) {
			CompanionPlayer p = c.player();
			if (p == null || !c.isOnline() || p.isDead() || p.getServerWorld() != colony.world()) {
				continue;
			}
			long chunk = ChunkPos.toLong(p.getBlockX() >> 4, p.getBlockZ() >> 4);
			String id = Factions.idOf(c);
			if (id.equals(f.id)) {
				ours.merge(chunk, 1, Integer::sum);
			} else {
				others.computeIfAbsent(chunk, k -> new HashSet<>()).add(id);
			}
		}
		Set<Long> mine = new HashSet<>(f.claims);
		for (Map.Entry<Long, Integer> e : ours.entrySet()) {
			long chunk = e.getKey();
			String holder = OWNER.get(chunk);
			if (holder == null || holder.equals(f.id) || !Factions.hostile(f.id, holder)) {
				continue;
			}
			Set<String> present = others.getOrDefault(chunk, Set.of());
			if (present.contains(holder)) {
				continue; // contested: they're still here
			}
			boolean border = false;
			for (long nb : neighboursOf(chunk)) {
				border |= mine.contains(nb);
			}
			Faction loser = Factions.get(holder);
			Colony them = Colony.of(holder);
			if (!border || loser == null || (them != null && them.hasBase() && core(them, chunk))) {
				continue;
			}
			transfer(loser, f, chunk);
			mine.add(chunk);
			AiLog.log("faction", "{} took a chunk of {}'s land ({} chunks now, they have {})", f.id, holder, f.claims.size(), loser.claims.size());
			Companion speaker = Diplomacy.spokesperson(f.id);
			if (speaker != null) {
				speaker.speech().event("faction.took_land", Map.of("faction", loser.name), 20 * 60 * 3, 0.8F);
			}
		}
	}

	/** Within {@link #CORE_RADIUS} chunks of the base: never given up. */
	private static boolean core(Colony colony, long chunk) {
		ChunkPos home = new ChunkPos(colony.base());
		return Math.abs(ChunkPos.getPackedX(chunk) - home.x) <= CORE_RADIUS && Math.abs(ChunkPos.getPackedZ(chunk) - home.z) <= CORE_RADIUS;
	}

	private static void transfer(Faction from, Faction to, long chunk) {
		from.claims.remove(chunk);
		to.claims.add(chunk);
		OWNER.put(chunk, to.id);
		Factions.markDirty();
	}

	/** Peace terms: {@code loser} hands over up to {@code n} chunks on its border with {@code winner}. Returns how many. */
	public static int cede(String loser, String winner, int n) {
		Faction l = Factions.get(loser);
		Faction w = Factions.get(winner);
		Colony lc = Colony.of(loser);
		if (!on() || l == null || w == null || !l.claimDim.equals(w.claimDim)) {
			return 0;
		}
		int given = 0;
		for (int i = 0; i < n; i++) {
			Set<Long> theirs = new HashSet<>(w.claims);
			Long pick = null;
			for (long c : l.claims) {
				if (lc != null && lc.hasBase() && core(lc, c)) {
					continue;
				}
				for (long nb : neighboursOf(c)) {
					if (theirs.contains(nb)) {
						pick = c;
						break;
					}
				}
				if (pick != null) {
					break;
				}
			}
			if (pick == null) {
				break;
			}
			transfer(l, w, pick);
			given++;
		}
		return given;
	}

	/** Two factions became one: the land of {@code from} is now {@code to}'s (if it's in the same dimension). */
	public static void absorb(String from, String to) {
		Faction f = Factions.get(from);
		Faction t = Factions.get(to);
		if (f == null || t == null) {
			return;
		}
		if (t.claimDim.isEmpty() || t.claimDim.equals(f.claimDim)) {
			t.claimDim = t.claimDim.isEmpty() ? f.claimDim : t.claimDim;
			for (long c : f.claims) {
				if (!t.claims.contains(c)) {
					t.claims.add(c);
				}
			}
		}
		f.claims.clear();
		changed();
	}

	/** A faction is gone (or lost its base): its land is free again. */
	public static void release(String faction) {
		Faction f = Factions.get(faction);
		if (f != null) {
			f.claims.clear();
			changed();
		}
		for (Faction o : Factions.all()) {
			o.met.remove(faction);
		}
	}

	/** First contact: pairs whose land touches and who haven't met. */
	private static void contacts(long now) {
		List<Faction> all = Factions.groups();
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				Faction a = all.get(i);
				Faction b = all.get(j);
				if (a.met.contains(b.id) || !touching(a, b)) {
					continue;
				}
				a.met.add(b.id);
				b.met.add(a.id);
				Factions.markDirty();
				Diplomacy.contact(a.id, b.id, now);
			}
		}
	}

	private static void changed() {
		indexDirty = true;
		Factions.markDirty();
		index();
	}

	private static void index() {
		if (!indexDirty) {
			return;
		}
		OWNER.clear();
		for (Faction f : Factions.all()) {
			for (long c : f.claims) {
				OWNER.put(c, f.id);
			}
		}
		indexDirty = false;
	}

	/** Claims were edited from outside (loaded, a faction removed). */
	public static void invalidate() {
		indexDirty = true;
	}

	private static String dim(ServerWorld w) {
		return w.getRegistryKey().getValue().toString();
	}

	public static void clear() {
		OWNER.clear();
		indexDirty = true;
	}
}

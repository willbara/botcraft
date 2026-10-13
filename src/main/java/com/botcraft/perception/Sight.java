package com.botcraft.perception;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How far a companion sees: as far as the server lets players see (its view distance), at most
 * {@link #MAX_CHUNKS} chunks. Path searches may span it, and the surface within it is visible:
 * a companion on a beach sees the forest across the water, like a player would. What's inside
 * the ground (ore, caves) is still only found close by.
 *
 * <p>Seeing the surface is cheap: only the top block of every other column, from the heightmap,
 * summed up per chunk and shared by everyone for a few minutes.
 */
public final class Sight {
	public static final int MAX_CHUNKS = 12;
	/** Before this mod followed the view distance, path searches reached this far. */
	private static final int OLD_RANGE = 96;
	/** A chunk's summary is reused for this long (trees get chopped, saplings grow). */
	private static final long TTL = 20 * 60 * 5;
	/** Top-of-column samples showing a tree crown, for a chunk to count as having trees. */
	private static final int MIN_CANOPY = 4;

	private record Summary(long at, int canopy, @Nullable BlockPos tree) {
	}

	private static final Map<String, Map<Long, Summary>> SURFACE = new HashMap<>();

	private Sight() {
	}

	/** View distance in chunks, as the server has it, at most {@link #MAX_CHUNKS}. */
	public static int chunks(MinecraftServer server) {
		int vd = server.getPlayerManager() == null ? 8 : server.getPlayerManager().getViewDistance();
		return Math.max(2, Math.min(MAX_CHUNKS, (int) com.botcraft.util.Experiments.num("sightChunks", vd)));
	}

	/** How far (in blocks) a companion sees and a path search may reach. */
	public static int blocks(MinecraftServer server) {
		return com.botcraft.util.Experiments.on("sightRange") ? chunks(server) * 16 : OLD_RANGE;
	}

	/**
	 * The nearest trees in sight (the top of a tree crown in the nearest chunk with a few), or
	 * null. Only loaded chunks are looked at; {@code skip} rules places out (just searched,
	 * known to be no good).
	 */
	@Nullable
	public static BlockPos nearestTrees(ServerWorld world, BlockPos from, Predicate<BlockPos> skip) {
		if (!com.botcraft.util.Experiments.on("surfaceSight")) {
			return null;
		}
		int r = chunks(world.getServer());
		ChunkPos center = new ChunkPos(from);
		long now = world.getTime();
		Map<Long, Summary> cache = SURFACE.computeIfAbsent(world.getRegistryKey().getValue().toString(), k -> new HashMap<>());
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		// Ring by ring outward; past the ring holding the best so far, nothing can be nearer.
		for (int ring = 0; ring <= r; ring++) {
			if (best != null && (ring - 1) * 16.0 > Math.sqrt(bestD)) {
				break;
			}
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					int cx = center.x + dx;
					int cz = center.z + dz;
					Summary s = summary(world, cache, cx, cz, now);
					if (s == null || s.canopy() < MIN_CANOPY || s.tree() == null || skip.test(s.tree())) {
						continue;
					}
					double d = s.tree().getSquaredDistance(from);
					if (d < bestD) {
						bestD = d;
						best = s.tree();
					}
				}
			}
		}
		return best;
	}

	@Nullable
	private static Summary summary(ServerWorld world, Map<Long, Summary> cache, int cx, int cz, long now) {
		long key = ChunkPos.toLong(cx, cz);
		Summary s = cache.get(key);
		if (s != null && now - s.at() < TTL && now >= s.at()) {
			return s;
		}
		WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz); // never loads
		if (chunk == null) {
			return null;
		}
		int canopy = 0;
		BlockPos tree = null;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int lx = 0; lx < 16; lx += 2) {
			for (int lz = 0; lz < 16; lz += 2) {
				int top = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, lx, lz);
				BlockState st = chunk.getBlockState(m.set(lx, top, lz));
				boolean crown = (st.getBlock() instanceof LeavesBlock && !st.get(LeavesBlock.PERSISTENT)) || st.isIn(BlockTags.LOGS);
				if (crown) {
					canopy++;
					if (tree == null) {
						tree = new BlockPos(cx * 16 + lx, top, cz * 16 + lz);
					}
				}
			}
		}
		s = new Summary(now, canopy, tree);
		cache.put(key, s);
		return s;
	}

	/** Server stopped (or for tests): forget what was seen. */
	public static void clear() {
		SURFACE.clear();
	}
}

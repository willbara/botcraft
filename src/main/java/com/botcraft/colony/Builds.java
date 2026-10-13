package com.botcraft.colony;

import com.botcraft.Botcraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Who made a block: nature, a builder (a player, or a companion setting up the base), or a
 * companion getting somewhere (a throwaway bridge or pillar block: litter, see
 * {@link Colony#placedByCompanion}).
 *
 * <p>Vanilla doesn't record who placed what, so this is two things together:
 * <ul>
 *   <li>a ledger of every block placed by a player since the mod was installed, and of base
 *       blocks companions put down (chests, furnaces, beds...), saved in
 *       {@code <world>/botcraft/built.dat}; a block broken by anyone leaves it;</li>
 *   <li>for everything older, what the block is: planks, bricks, glass, stairs, fences, wool,
 *       doors, block entities and so on never occur in nature, and a log only counts as a tree if
 *       it's part of a trunk topped with tree-grown leaves (a log cabin has none).</li>
 * </ul>
 * Companions only take resources from natural blocks, and never break built ones (unless
 * griefing is on). Builds the companions make later will be recorded the same way.
 */
public final class Builds {
	public enum Origin { NATURAL, BUILT, LITTER }

	/** Built blocks per dimension (BlockPos longs). */
	private static final Map<String, LongOpenHashSet> LEDGER = new HashMap<>();
	private static boolean dirty;

	private Builds() {
	}

	// ---- the ledger ---------------------------------------------------------------------------------

	public static void record(ServerWorld world, BlockPos pos) {
		if (LEDGER.computeIfAbsent(dim(world), d -> new LongOpenHashSet()).add(pos.asLong())) {
			dirty = true;
		}
	}

	public static void forget(ServerWorld world, BlockPos pos) {
		LongOpenHashSet set = LEDGER.get(dim(world));
		if (set != null && set.remove(pos.asLong())) {
			dirty = true;
		}
	}

	/** Someone built this block (it's in the ledger). */
	public static boolean recorded(ServerWorld world, BlockPos pos) {
		LongOpenHashSet set = LEDGER.get(dim(world));
		return set != null && set.contains(pos.asLong());
	}

	public static int recordedCount() {
		return LEDGER.values().stream().mapToInt(LongOpenHashSet::size).sum();
	}

	private static String dim(ServerWorld world) {
		return world.getRegistryKey().getValue().toString();
	}

	// ---- what a block is ----------------------------------------------------------------------------

	public static Origin origin(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.isAir()) {
			return Origin.NATURAL;
		}
		if (com.botcraft.nav.BreakPolicy.placedByCompanion(world, pos, state)) {
			return Origin.LITTER;
		}
		if (recorded(world, pos) || builtMaterial(state)) {
			return Origin.BUILT;
		}
		if (state.isIn(BlockTags.LOGS) && !partOfTree(world, pos)) {
			return Origin.BUILT;
		}
		return Origin.NATURAL;
	}

	public static boolean isBuilt(ServerWorld world, BlockPos pos, BlockState state) {
		return origin(world, pos, state) == Origin.BUILT;
	}

	public static boolean isNatural(ServerWorld world, BlockPos pos, BlockState state) {
		return origin(world, pos, state) == Origin.NATURAL;
	}

	/** Name parts of blocks that don't occur in nature (outside generated structures). */
	private static final String[] BUILT_WORDS = {"planks", "brick", "polished", "stripped", "smooth", "cut_", "chiseled", "concrete",
		"glass", "wool", "carpet", "fence", "wall", "door", "stairs", "slab", "lantern", "torch", "sign", "banner", "glazed",
		"bookshelf", "rail", "button", "pressure_plate", "lever", "ladder", "scaffolding", "chain", "bars", "candle", "flower_pot",
		"cobble", "quartz", "terracotta", "lamp", "hay_block"};
	/** ...except these, which nature makes. */
	private static final Set<String> NATURAL_EXCEPTIONS = Set.of("smooth_basalt", "moss_carpet", "terracotta", "white_terracotta",
		"orange_terracotta", "yellow_terracotta", "brown_terracotta", "red_terracotta", "light_gray_terracotta");
	private static final Map<Block, Boolean> MATERIAL = new java.util.concurrent.ConcurrentHashMap<>();

	/** A block that never occurs in nature: planks, bricks, glass, wool, doors, any block entity... */
	public static boolean builtMaterial(BlockState state) {
		if (state.hasBlockEntity() || state.isIn(BlockTags.BEDS)) {
			return true;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return state.get(LeavesBlock.PERSISTENT);
		}
		return MATERIAL.computeIfAbsent(state.getBlock(), b -> {
			String id = Registries.BLOCK.getId(b).getPath();
			if (NATURAL_EXCEPTIONS.contains(id) || id.endsWith("_ore")) {
				return false;
			}
			for (String w : BUILT_WORDS) {
				if (id.contains(w)) {
					return true;
				}
			}
			return false;
		});
	}

	/**
	 * A log that's part of a tree: its trunk (the logs it touches, at most 64) has tree-grown
	 * leaves near its top. Logs in a cabin wall, a stack of logs or a log floor have none.
	 */
	public static boolean partOfTree(ServerWorld world, BlockPos log) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		q.add(log.toImmutable());
		seen.add(log.toImmutable());
		BlockPos top = log;
		while (!q.isEmpty() && seen.size() < 64) {
			BlockPos cur = q.poll();
			if (cur.getY() > top.getY()) {
				top = cur;
			}
			for (BlockPos n : BlockPos.iterate(cur.add(-1, -1, -1), cur.add(1, 1, 1))) {
				BlockPos im = n.toImmutable();
				if (!seen.contains(im) && world.getBlockState(im).isIn(BlockTags.LOGS) && !recorded(world, im)) {
					seen.add(im);
					q.add(im);
				}
			}
		}
		int natural = 0;
		for (BlockPos pos : BlockPos.iterate(top.add(-3, -2, -3), top.add(3, 3, 3))) {
			BlockState s = world.getBlockState(pos);
			if (s.getBlock() instanceof LeavesBlock && !s.get(LeavesBlock.PERSISTENT) && ++natural >= 4) {
				return true;
			}
		}
		return false;
	}

	// ---- saving -------------------------------------------------------------------------------------

	private static Path file(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("botcraft").resolve("built.dat");
	}

	public static void load(MinecraftServer server) {
		LEDGER.clear();
		dirty = false;
		Path f = file(server);
		if (!Files.exists(f)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f)))) {
			int dims = in.readInt();
			for (int d = 0; d < dims; d++) {
				String dim = in.readUTF();
				int n = in.readInt();
				LongOpenHashSet set = new LongOpenHashSet(n);
				for (int i = 0; i < n; i++) {
					set.add(in.readLong());
				}
				LEDGER.put(dim, set);
			}
		} catch (IOException e) {
			Botcraft.LOG.error("Could not read {}", f, e);
		}
	}

	public static void save(MinecraftServer server) {
		if (!dirty) {
			return;
		}
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("built.dat.tmp");
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
				out.writeInt(LEDGER.size());
				for (Map.Entry<String, LongOpenHashSet> e : LEDGER.entrySet()) {
					out.writeUTF(e.getKey());
					out.writeInt(e.getValue().size());
					for (long l : e.getValue()) {
						out.writeLong(l);
					}
				}
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			Botcraft.LOG.error("Could not write {}", f, e);
		}
	}

	/** Server stopped: forget everything (the next world has its own). */
	public static void unload() {
		LEDGER.clear();
		dirty = false;
	}

	/** For tests. */
	@Nullable
	public static LongOpenHashSet ledger(ServerWorld world) {
		return LEDGER.get(dim(world));
	}
}

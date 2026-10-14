package com.botcraft.colony;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A shared picture of the ground around the base, {@link #RADIUS} blocks each way: how high the
 * ground is in each column, what it's made of, and where the base's furniture is (chests,
 * furnaces, crafting tables, beds, torches). A few columns are read each server tick, so the
 * whole map refreshes every few seconds at a small, steady cost, whoever is at home.
 *
 * <p>Everyone reads the same map: companions at the base use the furnace and table that are
 * already there instead of putting down new ones, and base planning (zones) works from it.
 * Only loaded chunks are read; columns in unloaded ones keep what was last seen.
 */
public final class BaseMap {
	public static final int RADIUS = 40;
	private static final int SIZE = RADIUS * 2 + 1;
	private static final int COLUMNS_PER_TICK = 48;

	/** What the ground is in a column. */
	public enum Ground { UNKNOWN, SOIL, STONE, SAND, WATER, LAVA, TREE, BUILT, OTHER }

	/** Base furniture worth knowing about. */
	public enum Feature { CHEST, FURNACE, CRAFTING_TABLE, BED, TORCH }

	/** One map per faction's base (see faction.Scope). */
	private static final Map<String, BaseMap> INSTANCES = new java.util.HashMap<>();

	private final BlockPos center;
	private final String dim;
	private final short[] groundY = new short[SIZE * SIZE];
	private final byte[] ground = new byte[SIZE * SIZE];
	private int next;
	private boolean complete;
	/** Features found in the sweep under way, and in the last finished one (which readers see). */
	private Map<Feature, List<BlockPos>> building = emptyFeatures();
	private Map<Feature, List<BlockPos>> features = emptyFeatures();

	private BaseMap(BlockPos center, String dim) {
		this.center = center.toImmutable();
		this.dim = dim;
	}

	/** The map of the current base (of the faction in scope), or null before its base is set. */
	@Nullable
	public static BaseMap get() {
		return of(com.botcraft.faction.Scope.current());
	}

	/** The map of a faction's base, or null before it has one. */
	@Nullable
	public static BaseMap of(String faction) {
		return INSTANCES.get(faction);
	}

	/** Called every server tick: follow the base if it moved, and read the next few columns. */
	public static void tick(Colony colony) {
		ServerWorld world = colony.world();
		BlockPos base = colony.base();
		if (world == null || base == null) {
			INSTANCES.remove(colony.id());
			return;
		}
		String dim = world.getRegistryKey().getValue().toString();
		BaseMap instance = INSTANCES.get(colony.id());
		if (instance == null || !instance.center.equals(base) || !instance.dim.equals(dim)) {
			instance = new BaseMap(base, dim);
			INSTANCES.put(colony.id(), instance);
		}
		instance.scan(world);
	}

	private static Map<Feature, List<BlockPos>> emptyFeatures() {
		Map<Feature, List<BlockPos>> m = new EnumMap<>(Feature.class);
		for (Feature f : Feature.values()) {
			m.put(f, new ArrayList<>());
		}
		return m;
	}

	private void scan(ServerWorld world) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int n = 0; n < COLUMNS_PER_TICK; n++) {
			int i = next;
			int x = center.getX() - RADIUS + i % SIZE;
			int z = center.getZ() - RADIUS + i / SIZE;
			if (world.isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z))) {
				readColumn(world, m, i, x, z);
			}
			if (++next >= SIZE * SIZE) {
				next = 0;
				complete = true;
				features = building;
				building = emptyFeatures();
			}
		}
	}

	private void readColumn(ServerWorld world, BlockPos.Mutable m, int i, int x, int z) {
		int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		// Furniture stands on the ground (and is the top block itself); torches don't block motion,
		// so they sit just above it. Look a little down and up for both.
		int y = top;
		for (int dy = 1; dy >= -2; dy--) {
			m.set(x, top + dy, z);
			BlockState s = world.getBlockState(m);
			Feature f = feature(s);
			if (f != null) {
				building.get(f).add(m.toImmutable());
				if (dy == 0) {
					y = top - 1; // the ground is under the furniture
				}
			}
		}
		m.set(x, y, z);
		groundY[i] = (short) y;
		ground[i] = (byte) classify(world.getBlockState(m)).ordinal();
	}

	@Nullable
	private static Feature feature(BlockState s) {
		if (s.getBlock() instanceof ChestBlock || s.isOf(Blocks.BARREL)) {
			return Feature.CHEST;
		}
		if (s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE) || s.isOf(Blocks.SMOKER)) {
			return Feature.FURNACE;
		}
		if (s.isOf(Blocks.CRAFTING_TABLE)) {
			return Feature.CRAFTING_TABLE;
		}
		if (s.getBlock() instanceof BedBlock && s.get(BedBlock.PART) == BedPart.HEAD) {
			return Feature.BED;
		}
		if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH)) {
			return Feature.TORCH;
		}
		return null;
	}

	private static Ground classify(BlockState s) {
		FluidState f = s.getFluidState();
		if (f.isIn(FluidTags.WATER)) {
			return Ground.WATER;
		}
		if (f.isIn(FluidTags.LAVA)) {
			return Ground.LAVA;
		}
		if (s.isIn(BlockTags.LOGS)) {
			return Ground.TREE;
		}
		if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.PODZOL)
			|| s.isOf(Blocks.ROOTED_DIRT) || s.isOf(Blocks.MUD) || s.isOf(Blocks.FARMLAND) || s.isOf(Blocks.DIRT_PATH)) {
			return Ground.SOIL;
		}
		if (s.isIn(BlockTags.SAND) || s.isOf(Blocks.GRAVEL)) {
			return Ground.SAND;
		}
		if (com.botcraft.nav.BreakPolicy.isNaturalTerrain(s)) {
			return Ground.STONE;
		}
		if (s.isIn(BlockTags.PLANKS) || s.isOf(Blocks.COBBLESTONE) || s.isIn(BlockTags.SLABS) || s.isIn(BlockTags.STAIRS)
			|| s.isIn(BlockTags.WOOL) || s.hasBlockEntity()) {
			return Ground.BUILT;
		}
		return Ground.OTHER;
	}

	// ---- reading -------------------------------------------------------------------------------

	public BlockPos center() {
		return center;
	}

	/** At least one whole sweep done (until then features are empty and ground is partly unknown). */
	public boolean complete() {
		return complete;
	}

	private int index(int x, int z) {
		int dx = x - center.getX() + RADIUS;
		int dz = z - center.getZ() + RADIUS;
		return dx < 0 || dz < 0 || dx >= SIZE || dz >= SIZE ? -1 : dz * SIZE + dx;
	}

	/** Ground kind of a column (UNKNOWN outside the map or not read yet). */
	public Ground ground(int x, int z) {
		int i = index(x, z);
		return i < 0 ? Ground.UNKNOWN : Ground.values()[ground[i]];
	}

	/** Height of the ground block in a column (meaningful once its kind isn't UNKNOWN). */
	public int groundY(int x, int z) {
		int i = index(x, z);
		return i < 0 ? Integer.MIN_VALUE : groundY[i];
	}

	/** Where the base's furniture of a kind is, as of the last full sweep. */
	public List<BlockPos> features(Feature f) {
		return features.get(f);
	}

	/** The nearest of a kind of furniture within {@code radius} of a spot (and 4 up or down), or null. */
	@Nullable
	public BlockPos nearest(Feature f, BlockPos from, double radius) {
		return features.get(f).stream().filter(p -> p.getSquaredDistance(from) <= radius * radius && Math.abs(p.getY() - from.getY()) <= 4)
			.min(Comparator.comparingDouble(p -> p.getSquaredDistance(from))).orElse(null);
	}

	/** For tests: start over. */
	public static void clear() {
		INSTANCES.clear();
	}
}

package com.botcraft.colony;

import com.botcraft.Botcraft;
import com.botcraft.colony.Colony.Zone;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Laying out the base, the way a player would: a core around the home spot for chests,
 * furnaces and the crafting table; a row of beds beside it; a farm on flat soil (near water if
 * there is some) and an animal pen on other flat ground, each a short walk away; and paths from
 * the core to them. Zones are planned from the {@link BaseMap} once it has seen the whole area,
 * saved in colony.json, and never replace one the player set by hand.
 *
 * <p>What uses them: new chests go in the core, beds go in the bed row, and nothing else gets
 * put down on the bed row, the farm, the pen or the paths.
 *
 * <p>Around all of them is the <b>home</b> zone: the base and everything it uses, plus a
 * margin. At home companions don't mine or dig for dirt, avoid leaving bridges and pillars
 * behind, and keep things tidy (see {@link Upkeep}); the other zones are kept level.
 */
public final class Zones {
	public static final String CORE = "core";
	public static final String BEDS = "beds";
	public static final String FARM = "farm";
	public static final String PEN = "pen";
	public static final String HOME = "home";
	/** The farm and the plots added next to it as the base grows (see {@link #expandFarms}). */
	public static final List<String> FARMS = List.of(FARM, "farm2", "farm3", "farm4");
	public static final List<String> KINDS = List.of(CORE, BEDS, FARM, "farm2", "farm3", "farm4", PEN, HOME);
	/** The zones inside home, which are kept level. */
	public static final List<String> AREAS = List.of(CORE, BEDS, FARM, "farm2", "farm3", "farm4", PEN);
	/** Farm plots are 9x9 so that one water block in the middle wets all of it (water reaches 4 blocks). */
	public static final int HYDRATION = 4;
	/** Plots this much planted (of their farmland and soil) before another is laid out. */
	static final double FULL = 0.75;
	private static final Map<String, Long> LAST_EXPANSION = new HashMap<>();
	private static final long EXPANSION_TICKS = 20 * 60 * 3;

	/** Home reaches at least this far from the base spot, and this far past the other zones. */
	public static final int HOME_MIN_HALF = 16;
	private static final int HOME_MARGIN = 3;

	/** Core: the base spot and this many blocks each way. */
	private static final int CORE_HALF = 3;
	private static final int FIELD = 9;
	private static final int BED_ROW = 11;

	private Zones() {
	}

	/** Plan whatever zones are missing, once the base map has seen the whole area. Cheap if nothing is. */
	public static void plan(Colony colony, @Nullable BaseMap map) {
		BlockPos base = colony.base();
		if (base == null || map == null || !map.complete() || !map.center().equals(base)) {
			return;
		}
		boolean changed = false;
		if (colony.zone(CORE) == null) {
			colony.setZone(new Zone(CORE, base.getX() - CORE_HALF, base.getZ() - CORE_HALF, base.getX() + CORE_HALF, base.getZ() + CORE_HALF,
				base.getY(), false));
			changed = true;
		}
		if (colony.zone(BEDS) == null) {
			Zone beds = bedRow(colony, map, base);
			if (beds != null) {
				colony.setZone(beds);
				changed = true;
			}
		}
		for (String kind : List.of(FARM, PEN)) {
			if (colony.zone(kind) == null) {
				Zone field = field(colony, map, base, kind);
				if (field != null) {
					colony.setZone(field);
					changed = true;
				}
			}
		}
		Zone home = colony.zone(HOME);
		if (home == null || !home.manual) {
			Zone want = homeAround(colony, base);
			if (home == null || home.x1 != want.x1 || home.z1 != want.z1 || home.x2 != want.x2 || home.z2 != want.z2) {
				colony.setZone(want);
				changed = true;
			}
		}
		if (colony.zone(FARM) != null && com.botcraft.config.CompanionConfig.get().expandFarms && com.botcraft.util.Experiments.on("expandFarms")
			&& expandFarms(colony, map)) {
			Zone want = homeAround(colony, base);
			if (colony.zone(HOME) == null || !colony.zone(HOME).manual) {
				colony.setZone(want);
			}
			changed = true;
		}
		if (changed) {
			Botcraft.LOG.info("Base zones planned: {}", colony.zones());
		}
	}

	// ---- farms --------------------------------------------------------------------------------

	public static boolean isFarm(String kind) {
		return FARMS.contains(kind);
	}

	/** The base's farm plots, the first one first. */
	public static List<Zone> farms(Colony colony) {
		List<Zone> out = new ArrayList<>();
		for (String kind : FARMS) {
			Zone z = colony.zone(kind);
			if (z != null) {
				out.add(z);
			}
		}
		return out;
	}

	/** Where a plot's water goes: the middle, at the farmland's height. */
	public static BlockPos waterSpot(Zone farm) {
		BlockPos c = farm.center();
		return new BlockPos(c.getX(), farm.y - 1, c.getZ());
	}

	/** This column is the middle of a farm plot (kept for water, never tilled or filled in). */
	public static boolean isWaterSpot(Colony colony, int x, int z) {
		for (Zone f : farms(colony)) {
			BlockPos w = waterSpot(f);
			if (w.getX() == x && w.getZ() == z) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A plot's middle wants water: it isn't water yet, and some of the plot is further than
	 * {@link #HYDRATION} blocks from any water at the farmland's height (or one above).
	 */
	public static boolean needsWater(ServerWorld w, Zone farm) {
		BlockPos spot = waterSpot(farm);
		if (!w.isChunkLoaded(spot) || w.getFluidState(spot).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
			return false;
		}
		List<BlockPos> water = new ArrayList<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = farm.x1 - HYDRATION; x <= farm.x2 + HYDRATION; x++) {
			for (int z = farm.z1 - HYDRATION; z <= farm.z2 + HYDRATION; z++) {
				for (int y = spot.getY(); y <= spot.getY() + 1; y++) {
					if (w.getFluidState(m.set(x, y, z)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
						water.add(m.toImmutable());
					}
				}
			}
		}
		for (int x = farm.x1; x <= farm.x2; x++) {
			for (int z = farm.z1; z <= farm.z2; z++) {
				boolean wet = false;
				for (BlockPos b : water) {
					if (Math.abs(b.getX() - x) <= HYDRATION && Math.abs(b.getZ() - z) <= HYDRATION) {
						wet = true;
						break;
					}
				}
				if (!wet) {
					return true;
				}
			}
		}
		return false;
	}

	/** The first plot that wants water, or null. */
	@Nullable
	public static Zone dryFarm(Colony colony, ServerWorld w) {
		for (Zone f : farms(colony)) {
			if (needsWater(w, f)) {
				return f;
			}
		}
		return null;
	}

	/** How many farm plots a faction this size gets: one, and another for every four members (four at most). */
	public static int farmsFor(int members) {
		return Math.max(1, Math.min(FARMS.size(), 1 + members / 4));
	}

	/**
	 * Lay out another plot next to one we have, once the plots we have are mostly planted and the
	 * base is big enough for more: the best of the flat soil one plot over (a block apart, for a
	 * path) on any side of any plot, near water and the core if it can. True if one was added.
	 */
	static boolean expandFarms(Colony colony, BaseMap map) {
		long now = colony.world() == null ? 0 : colony.world().getTime();
		if (now - LAST_EXPANSION.getOrDefault(colony.id(), -EXPANSION_TICKS) < EXPANSION_TICKS) {
			return false;
		}
		LAST_EXPANSION.put(colony.id(), now);
		List<Zone> farms = farms(colony);
		int members = com.botcraft.faction.Factions.members(colony.id()).size();
		if (farms.size() >= farmsFor(members) || colony.world() == null) {
			return false;
		}
		for (Zone f : farms) {
			if (planted(colony.world(), f) < FULL) {
				return false;
			}
		}
		String kind = null;
		for (String k : FARMS) {
			if (colony.zone(k) == null) {
				kind = k;
				break;
			}
		}
		if (kind == null) {
			return false;
		}
		BlockPos base = colony.base();
		Zone best = null;
		double bestScore = -1;
		int step = FIELD + 1;
		for (Zone f : farms) {
			for (int[] d : new int[][]{{step, 0}, {-step, 0}, {0, step}, {0, -step}}) {
				Zone z = new Zone(kind, f.x1 + d[0], f.z1 + d[1], f.x2 + d[0], f.z2 + d[1], f.y, false);
				int y = levelOf(map, z.x1, z.z1, z.x2, z.z2);
				if (y == Integer.MIN_VALUE || Math.abs(y + 1 - f.y) > 2) {
					continue; // off the map, or up or down a hill from the plot next to it
				}
				z.y = y + 1;
				boolean clash = false;
				for (Zone o : colony.zones()) {
					if (!o.kind.equals(HOME) && o.overlaps(z, isFarm(o.kind) ? 0 : 2)) {
						clash = true;
						break;
					}
				}
				if (clash || (colony.zone(CORE) != null && onPathThrough(colony, z))) {
					continue;
				}
				double dist = base == null ? 0 : Math.sqrt(z.center().getSquaredDistance(base.getX(), z.y, base.getZ()));
				double score = usable(map, z, y, true) - dist / 100.0 + (waterNear(map, z) ? 0.2 : 0);
				if (score > bestScore) {
					bestScore = score;
					best = z;
				}
			}
		}
		if (best == null || bestScore < 0.5) {
			return false;
		}
		colony.setZone(best);
		com.botcraft.util.AiLog.log("colony", "{}: farm grows: new plot {} ({} plot(s) for {} member(s))", colony.id(), best, farms.size() + 1, members);
		return true;
	}

	/** A path from the core to the first farm or the pen crosses this rectangle. */
	private static boolean onPathThrough(Colony colony, Zone z) {
		for (int x = z.x1; x <= z.x2; x++) {
			for (int zz = z.z1; zz <= z.z2; zz++) {
				if (onPath(colony, x, zz)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Share of a plot's farmland and soil that has a crop on it. */
	static double planted(ServerWorld w, Zone farm) {
		int crops = 0;
		int ground = 0;
		for (int x = farm.x1; x <= farm.x2; x++) {
			for (int z = farm.z1; z <= farm.z2; z++) {
				if (!w.isChunkLoaded(x >> 4, z >> 4)) {
					return 0;
				}
				int top = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				var above = w.getBlockState(new BlockPos(x, top, z));
				var below = w.getBlockState(new BlockPos(x, top - 1, z));
				if (above.getBlock() instanceof net.minecraft.block.CropBlock || below.getBlock() instanceof net.minecraft.block.CropBlock) {
					crops++;
					ground++;
				} else if (below.isOf(net.minecraft.block.Blocks.FARMLAND) || below.isOf(net.minecraft.block.Blocks.GRASS_BLOCK)
					|| below.isOf(net.minecraft.block.Blocks.DIRT)) {
					ground++;
				}
			}
		}
		return ground == 0 ? 0 : (double) crops / ground;
	}

	public static void clear() {
		LAST_EXPANSION.clear();
	}

	/** The base spot and every other zone, with a margin. */
	private static Zone homeAround(Colony colony, BlockPos base) {
		int x1 = base.getX() - HOME_MIN_HALF;
		int z1 = base.getZ() - HOME_MIN_HALF;
		int x2 = base.getX() + HOME_MIN_HALF;
		int z2 = base.getZ() + HOME_MIN_HALF;
		for (Zone z : colony.zones()) {
			if (!z.kind.equals(HOME)) {
				x1 = Math.min(x1, z.x1 - HOME_MARGIN);
				z1 = Math.min(z1, z.z1 - HOME_MARGIN);
				x2 = Math.max(x2, z.x2 + HOME_MARGIN);
				z2 = Math.max(z2, z.z2 + HOME_MARGIN);
			}
		}
		return new Zone(HOME, x1, z1, x2, z2, base.getY(), false);
	}

	/** The flattest of the four strips just outside the core. */
	@Nullable
	private static Zone bedRow(Colony colony, BaseMap map, BlockPos base) {
		int bx = base.getX();
		int bz = base.getZ();
		int half = BED_ROW / 2;
		int out = CORE_HALF + 2;
		List<Zone> options = List.of(
			new Zone(BEDS, bx - half, bz - out - 1, bx + half, bz - out, base.getY(), false),
			new Zone(BEDS, bx - half, bz + out, bx + half, bz + out + 1, base.getY(), false),
			new Zone(BEDS, bx - out - 1, bz - half, bx - out, bz + half, base.getY(), false),
			new Zone(BEDS, bx + out, bz - half, bx + out + 1, bz + half, base.getY(), false));
		Zone best = null;
		double bestScore = -1;
		for (Zone z : options) {
			double score = usable(map, z, base.getY() - 1, false);
			if (score > bestScore) {
				bestScore = score;
				best = z;
			}
		}
		return bestScore > 0.5 ? best : null;
	}

	/**
	 * A flat 9x9 a short walk from the core, clear of the other zones. In a wood there may be no
	 * open ground at all: then the best plot that would do once its trees are down (and its bumps
	 * levelled) is taken anyway. The home zone grows round it, so its trees get felled like any at
	 * home (see Upkeep), and the farm or pen starts as the ground clears.
	 */
	@Nullable
	private static Zone field(Colony colony, BaseMap map, BlockPos base, String kind) {
		Zone open = field(colony, map, base, kind, false);
		return open != null ? open : field(colony, map, base, kind, true);
	}

	@Nullable
	private static Zone field(Colony colony, BaseMap map, BlockPos base, String kind, boolean rough) {
		Zone best = null;
		double bestScore = -1;
		int half = FIELD / 2;
		for (int dx = -26; dx <= 26; dx += 3) {
			for (int dz = -26; dz <= 26; dz += 3) {
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist < 10 || dist > 28) {
					continue;
				}
				int cx = base.getX() + dx;
				int cz = base.getZ() + dz;
				int y = levelOf(map, cx - half, cz - half, cx + half, cz + half);
				if (y == Integer.MIN_VALUE) {
					continue;
				}
				Zone z = new Zone(kind, cx - half, cz - half, cx + half, cz + half, y + 1, false);
				if (colony.zones().stream().anyMatch(o -> !o.kind.equals(HOME) && o.overlaps(z, 2))) {
					continue;
				}
				double score = (rough ? clearable(map, z, y) : usable(map, z, y, kind.equals(FARM))) - dist / 100.0;
				if (kind.equals(FARM) && waterNear(map, z)) {
					score += 0.3;
				}
				if (score > bestScore) {
					bestScore = score;
					best = z;
				}
			}
		}
		return bestScore > 0.6 ? best : null;
	}

	/**
	 * Share of the zone's columns that are good ground at height {@code groundY}: open ground,
	 * not water, lava, trees, buildings or base furniture (soil only counts fully for a farm).
	 */
	private static double usable(BaseMap map, Zone z, int groundY, boolean soilOnly) {
		int good = 0;
		int all = 0;
		for (int x = z.x1; x <= z.x2; x++) {
			for (int zz = z.z1; zz <= z.z2; zz++) {
				all++;
				BaseMap.Ground g = map.ground(x, zz);
				if (map.groundY(x, zz) != groundY) {
					continue;
				}
				if (g == BaseMap.Ground.SOIL) {
					good++;
				} else if (!soilOnly && (g == BaseMap.Ground.STONE || g == BaseMap.Ground.SAND || g == BaseMap.Ground.OTHER)) {
					good++;
				}
			}
		}
		for (BaseMap.Feature f : BaseMap.Feature.values()) {
			for (BlockPos p : map.features(f)) {
				if (f != BaseMap.Feature.TORCH && z.contains(p)) {
					good -= 3;
				}
			}
		}
		return all == 0 ? 0 : (double) good / all;
	}

	/**
	 * Share of the zone that would be good ground once cleared: open ground at the height (or a
	 * block off, which levelling fixes), and trees (they get felled). Not water, lava or buildings.
	 */
	static double clearable(BaseMap map, Zone z, int groundY) {
		double good = 0;
		int all = 0;
		for (int x = z.x1; x <= z.x2; x++) {
			for (int zz = z.z1; zz <= z.z2; zz++) {
				all++;
				BaseMap.Ground g = map.ground(x, zz);
				int dy = Math.abs(map.groundY(x, zz) - groundY);
				if (g == BaseMap.Ground.TREE) {
					good += 0.8;
				} else if ((g == BaseMap.Ground.SOIL || g == BaseMap.Ground.STONE || g == BaseMap.Ground.SAND || g == BaseMap.Ground.OTHER) && dy <= 1) {
					good += dy == 0 ? 1.0 : 0.7;
				}
			}
		}
		for (BaseMap.Feature f : BaseMap.Feature.values()) {
			for (BlockPos p : map.features(f)) {
				if (f != BaseMap.Feature.TORCH && z.contains(p)) {
					good -= 3;
				}
			}
		}
		return all == 0 ? 0 : good / all;
	}

	/** The most common ground height in a rectangle, or MIN_VALUE if the map doesn't cover it. */
	private static int levelOf(BaseMap map, int x1, int z1, int x2, int z2) {
		Map<Integer, Integer> counts = new HashMap<>();
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				BaseMap.Ground g = map.ground(x, z);
				if (g == BaseMap.Ground.UNKNOWN) {
					return Integer.MIN_VALUE;
				}
				if (g != BaseMap.Ground.TREE) { // (a tree's column reads as its trunk's top)
					counts.merge(map.groundY(x, z), 1, Integer::sum);
				}
			}
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Integer.MIN_VALUE);
	}

	private static boolean waterNear(BaseMap map, Zone z) {
		for (int x = z.x1 - 4; x <= z.x2 + 4; x++) {
			for (int zz = z.z1 - 4; zz <= z.z2 + 4; zz++) {
				if (map.ground(x, zz) == BaseMap.Ground.WATER) {
					return true;
				}
			}
		}
		return false;
	}

	// ---- reading --------------------------------------------------------------------------------

	/** The zone a column is in (not counting home, which holds them all), or null. */
	@Nullable
	public static Zone at(Colony colony, int x, int z) {
		for (Zone zone : colony.zones()) {
			if (!zone.kind.equals(HOME) && zone.contains(x, z)) {
				return zone;
			}
		}
		return null;
	}

	/**
	 * At home: inside the home zone (before it's planned, within {@link Colony#PROTECTED_RADIUS}
	 * of the base spot), in the base's dimension.
	 */
	public static boolean inHome(Colony colony, ServerWorld world, BlockPos pos) {
		if (world != colony.world() || colony.base() == null) {
			return false;
		}
		Zone home = colony.zone(HOME);
		if (home != null) {
			return home.contains(pos);
		}
		BlockPos b = colony.base();
		int dx = pos.getX() - b.getX();
		int dz = pos.getZ() - b.getZ();
		return dx * dx + dz * dz <= Colony.PROTECTED_RADIUS * Colony.PROTECTED_RADIUS;
	}

	/**
	 * How high the ground should be in a column: the zone's ground for the core, bed row, farm
	 * and pen (kept level); otherwise the usual height of the ground around it (the middle of
	 * the 5x5 columns around it, so a pillar or a one-block hole doesn't count), or MIN_VALUE if
	 * the base map hasn't seen it.
	 */
	public static int groundLevel(Colony colony, @Nullable BaseMap map, int x, int z) {
		Zone zone = at(colony, x, z);
		if (zone != null) {
			return zone.y - 1;
		}
		if (map == null) {
			return Integer.MIN_VALUE;
		}
		int[] ys = new int[25];
		int n = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BaseMap.Ground g = map.ground(x + dx, z + dz);
				if (g != BaseMap.Ground.UNKNOWN) {
					ys[n++] = map.groundY(x + dx, z + dz);
				}
			}
		}
		if (n < 5) {
			return Integer.MIN_VALUE;
		}
		java.util.Arrays.sort(ys, 0, n);
		return ys[n / 2];
	}

	/** A path from the core to the farm or the pen runs through this column (1 block either side). */
	public static boolean onPath(Colony colony, int x, int z) {
		Zone core = colony.zone(CORE);
		if (core == null) {
			return false;
		}
		BlockPos from = core.center();
		for (String kind : List.of(FARM, PEN)) {
			Zone to = colony.zone(kind);
			if (to != null && distToSegment(x, z, from.getX(), from.getZ(), to.center().getX(), to.center().getZ()) <= 1.0) {
				return true;
			}
		}
		return false;
	}

	private static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
		double vx = bx - ax;
		double vz = bz - az;
		double len = vx * vx + vz * vz;
		double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len));
		double dx = px - (ax + t * vx);
		double dz = pz - (az + t * vz);
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Kept clear: nothing but what the zone is for goes here (the bed row, the farm, the pen and
	 * the paths between them). The core takes furniture, so it isn't reserved.
	 */
	public static boolean reserved(Colony colony, ServerWorld world, BlockPos pos) {
		if (!com.botcraft.util.Experiments.on("zonePlacement") || world != colony.world() || !colony.near(world, pos, BaseMap.RADIUS)) {
			return false;
		}
		Zone z = at(colony, pos.getX(), pos.getZ());
		return (z != null && !z.kind.equals(CORE)) || (z == null && onPath(colony, pos.getX(), pos.getZ()));
	}

	/** A bed's place in the bed row: head and foot blocks, and the way the bed faces (foot to head). */
	public record BedSlot(BlockPos head, BlockPos foot, Direction facing) {
	}

	/** Every bed slot in the row, heads on the side nearer the core. */
	public static List<BedSlot> bedSlots(Colony colony) {
		Zone z = colony.zone(BEDS);
		Zone core = colony.zone(CORE);
		List<BedSlot> out = new ArrayList<>();
		if (z == null || core == null) {
			return out;
		}
		BlockPos c = core.center();
		boolean alongX = z.x2 - z.x1 > z.z2 - z.z1;
		if (alongX) {
			boolean north = z.z2 < c.getZ();
			int headZ = north ? z.z2 : z.z1;
			int footZ = north ? z.z1 : z.z2;
			for (int x = z.x1; x <= z.x2; x += 2) {
				out.add(new BedSlot(new BlockPos(x, z.y, headZ), new BlockPos(x, z.y, footZ), north ? Direction.SOUTH : Direction.NORTH));
			}
		} else {
			boolean west = z.x2 < c.getX();
			int headX = west ? z.x2 : z.x1;
			int footX = west ? z.x1 : z.x2;
			for (int zz = z.z1; zz <= z.z2; zz += 2) {
				out.add(new BedSlot(new BlockPos(headX, z.y, zz), new BlockPos(footX, z.y, zz), west ? Direction.EAST : Direction.WEST));
			}
		}
		return out;
	}

	/** The nearest bed slot that's free (both blocks open, solid ground under them), or null. */
	@Nullable
	public static BedSlot freeBedSlot(Colony colony, ServerWorld world, BlockPos from) {
		BedSlot best = null;
		double bestD = Double.MAX_VALUE;
		for (BedSlot s : bedSlots(colony)) {
			if (open(world, s.head()) && open(world, s.foot())) {
				double d = s.foot().getSquaredDistance(from);
				if (d < bestD) {
					bestD = d;
					best = s;
				}
			}
		}
		return best;
	}

	private static boolean open(ServerWorld world, BlockPos p) {
		return world.getBlockState(p).isReplaceable() && world.getFluidState(p).isEmpty()
			&& world.getBlockState(p.down()).isSideSolidFullSquare(world, p.down(), Direction.UP);
	}
}

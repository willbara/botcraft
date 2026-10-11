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
 */
public final class Zones {
	public static final String CORE = "core";
	public static final String BEDS = "beds";
	public static final String FARM = "farm";
	public static final String PEN = "pen";
	public static final List<String> KINDS = List.of(CORE, BEDS, FARM, PEN);

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
		if (changed) {
			Botcraft.LOG.info("Base zones planned: {}", colony.zones());
		}
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

	/** A flat 9x9 a short walk from the core, clear of the other zones. */
	@Nullable
	private static Zone field(Colony colony, BaseMap map, BlockPos base, String kind) {
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
				if (colony.zones().stream().anyMatch(o -> o.overlaps(z, 2))) {
					continue;
				}
				double score = usable(map, z, y, kind.equals(FARM)) - dist / 100.0;
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

	/** The most common ground height in a rectangle, or MIN_VALUE if the map doesn't cover it. */
	private static int levelOf(BaseMap map, int x1, int z1, int x2, int z2) {
		Map<Integer, Integer> counts = new HashMap<>();
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				if (map.ground(x, z) == BaseMap.Ground.UNKNOWN) {
					return Integer.MIN_VALUE;
				}
				counts.merge(map.groundY(x, z), 1, Integer::sum);
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

	/** The zone a column is in, or null. */
	@Nullable
	public static Zone at(Colony colony, int x, int z) {
		for (Zone zone : colony.zones()) {
			if (zone.contains(x, z)) {
				return zone;
			}
		}
		return null;
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

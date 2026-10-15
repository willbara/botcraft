package com.botcraft.colony;

import com.botcraft.colony.Colony.Zone;
import com.botcraft.nav.BreakPolicy;
import com.botcraft.util.Experiments;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Looking after home: what needs doing to keep it as it should be. Found every few seconds and
 * put on the request board as one "tidy up the base" request ({@code TidyTask} does it).
 * <ul>
 *   <li><b>Litter:</b> throwaway blocks companions left at home (bridges, pillars, a tower
 *       for chopping a tall tree) are taken down. One that has become part of the ground (it
 *       filled a hole) is kept and stops counting as litter; one holding something up is
 *       left alone.</li>
 *   <li><b>Holes:</b> ground a companion dug out at home (escaping, a shelter, digging for
 *       dirt) is filled back in. Holes players dig are theirs and left alone.</li>
 *   <li><b>Level ground:</b> the core, bed row, farm and pen are kept at their ground height:
 *       natural bumps up to {@link #MAX_STEP} blocks high are dug away, dips that deep filled.
 *       Never a built block, a tree, water, or anything under the base's furniture.</li>
 *   <li><b>Clear ground</b> around home (the home zone and {@link #MARGIN} blocks past it):
 *       pillars and lone blocks of cobblestone or dirt sticking up out of flat ground, from
 *       before litter was recorded (or recorded dirt that has since grown grass), and the
 *       stumps a felled tree leaves (logs with no leaves on them) are taken down; one-block pits
 *       are filled. Nothing a player built is touched.</li>
 *   <li><b>Fences</b> ({@code fenceHome}, once the base has wood to spare): a fence round the
 *       home zone, with gates where the paths cross it and in the middle of each side.</li>
 * </ul>
 */
public final class Upkeep {
	/** In the order they get done: clearing first, fences last. */
	public enum Kind { LITTER, FELL, CLEAR, DIG, FILL, FENCE, GATE }

	/** Clear ground this far past the home zone. */
	public static final int MARGIN = 12;
	/** Pillars and stumps up to this tall are cleared (towers for felling a tree are about this). */
	private static final int MAX_PILLAR = 6;

	public record Work(Kind kind, BlockPos pos) {
	}

	/** Bumps or dips deeper than this in a zone are left as they are (that's landscaping, not upkeep). */
	public static final int MAX_STEP = 3;
	/**
	 * Upkeep looks after the ground, not what's under it: litter and holes more than this far
	 * under home's ground (in a cave below the base) are left as they are.
	 */
	public static final int MAX_DEPTH = 4;
	/** Most jobs in one go. */
	private static final int MAX_WORK = 64;

	/** Per faction (see faction.Scope): what was found last, and when. */
	private static final java.util.Map<String, List<Work>> LATEST = new java.util.HashMap<>();
	private static final java.util.Map<String, Long> LATEST_AT = new java.util.HashMap<>();

	private Upkeep() {
	}

	/** What needs doing at home, as last found. */
	public static List<Work> latest() {
		return LATEST.getOrDefault(com.botcraft.faction.Scope.current(), List.of());
	}

	/** What needs doing at home now (found again if the last look is older than {@code maxAge} ticks). */
	public static List<Work> current(Colony colony, long now, long maxAge) {
		long latestAt = LATEST_AT.getOrDefault(colony.id(), Long.MIN_VALUE);
		if (latestAt == Long.MIN_VALUE || now - latestAt > maxAge || now < latestAt) {
			LATEST.put(colony.id(), find(colony));
			LATEST_AT.put(colony.id(), now);
		}
		return LATEST.getOrDefault(colony.id(), List.of());
	}

	/** Look around home for work. */
	public static List<Work> find(Colony colony) {
		ServerWorld w = colony.world();
		BaseMap map = BaseMap.of(colony.id());
		Zone home = colony.zone(Zones.HOME);
		if (w == null || home == null || map == null || !map.complete() || !Experiments.on("upkeep")) {
			return List.of();
		}
		String dim = w.getRegistryKey().getValue().toString();
		List<Work> out = new ArrayList<>();
		Zone area = tidyArea(home, map);
		litter(colony, w, map, area, dim, out);
		holes(colony, w, home, dim, out);
		for (String kind : Zones.AREAS) {
			Zone z = colony.zone(kind);
			if (z != null) {
				level(colony, w, map, z, out);
			}
		}
		if (Experiments.on("clearGround")) {
			clearGround(colony, w, map, area, out);
		}
		if (com.botcraft.config.CompanionConfig.get().clearTrees && Experiments.on("clearTrees")) {
			trees(w, map, home, out);
		}
		if (com.botcraft.config.CompanionConfig.get().fenceHome && Experiments.on("fenceHome") && woodToSpare(colony)) {
			fences(colony, w, map, home, out);
		}
		BlockPos base = colony.base();
		// Litter and bumps from the top down, holes from the bottom up; nearest the base first.
		out.sort(Comparator.comparingInt((Work k) -> k.kind().ordinal())
			.thenComparingInt(k -> k.kind() == Kind.FILL ? k.pos().getY() : -k.pos().getY())
			.thenComparingDouble(k -> base == null ? 0 : k.pos().getSquaredDistance(base)));
		return out.size() > MAX_WORK ? List.copyOf(out.subList(0, MAX_WORK)) : out;
	}

	/** The home zone and a margin round it, inside what the base map covers. */
	static Zone tidyArea(Zone home, BaseMap map) {
		BlockPos c = map.center();
		int r = BaseMap.RADIUS - 1;
		return new Zone(Zones.HOME, Math.max(home.x1 - MARGIN, c.getX() - r), Math.max(home.z1 - MARGIN, c.getZ() - r),
			Math.min(home.x2 + MARGIN, c.getX() + r), Math.min(home.z2 + MARGIN, c.getZ() + r), home.y, false);
	}

	/** A block a companion would have put down: a throwaway block, or its dirt grown over with grass. */
	public static boolean throwaway(BlockState s) {
		return s.isOf(net.minecraft.block.Blocks.GRASS_BLOCK)
			|| com.botcraft.action.Inventories.isThrowaway(new net.minecraft.item.ItemStack(s.getBlock().asItem()));
	}

	/** A log that's no longer part of a tree (what a felled tree leaves behind). */
	public static boolean stump(ServerWorld w, BlockPos p, BlockState s) {
		return s.isIn(net.minecraft.registry.tag.BlockTags.LOGS) && !Builds.recorded(w, p) && !Builds.partOfTree(w, p);
	}

	/**
	 * Unrecorded litter round home: columns standing up out of flat ground (higher than all four
	 * neighbours, which are within a block of each other) made of throwaway blocks, or a stump.
	 * Taken down to the neighbours' height. And one-block pits in otherwise flat ground, filled.
	 */
	private static void clearGround(Colony colony, ServerWorld w, BaseMap map, Zone area, List<Work> out) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = area.x1; x <= area.x2; x++) {
			for (int z = area.z1; z <= area.z2; z++) {
				BaseMap.Ground g = map.ground(x, z);
				if (g == BaseMap.Ground.UNKNOWN || g == BaseMap.Ground.WATER || g == BaseMap.Ground.LAVA || !w.isChunkLoaded(m.set(x, 0, z))) {
					continue;
				}
				int gy = map.groundY(x, z);
				int lo = Integer.MAX_VALUE;
				int hi = Integer.MIN_VALUE;
				boolean known = true;
				for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					BaseMap.Ground ng = map.ground(x + d[0], z + d[1]);
					if (ng == BaseMap.Ground.UNKNOWN || ng == BaseMap.Ground.WATER) {
						known = false;
						break;
					}
					int ny = map.groundY(x + d[0], z + d[1]);
					lo = Math.min(lo, ny);
					hi = Math.max(hi, ny);
				}
				if (!known || hi - lo > 1) {
					continue; // a slope or the water's edge: leave it be
				}
				if (gy > hi && gy - hi <= MAX_PILLAR) {
					// Standing up out of flat ground: take it down while it's throwaway blocks or stump.
					for (int y = gy; y > hi; y--) {
						BlockPos p = new BlockPos(x, y, z);
						BlockState s = w.getBlockState(p);
						if (Builds.recorded(w, p) || !(throwaway(s) || stump(w, p, s)) || Upkeep.holdsUpAnything(w, p)) {
							break;
						}
						out.add(new Work(Kind.CLEAR, p));
					}
				} else if (gy == lo - 1 && Zones.at(colony, x, z) == null) {
					// A one-block pit (not in a zone: zones are levelled on their own).
					BlockPos p = new BlockPos(x, gy + 1, z);
					BlockState s = w.getBlockState(p);
					if (s.isReplaceable() && s.getFluidState().isEmpty()) {
						out.add(new Work(Kind.FILL, p));
					}
				}
			}
		}
	}

	private static boolean holdsUpAnything(ServerWorld w, BlockPos p) {
		BlockState up = w.getBlockState(p.up());
		return holdsUp(w, p) || up.hasBlockEntity() || up.isOf(net.minecraft.block.Blocks.TORCH);
	}

	/**
	 * Natural trees standing in the home zone (their trunks' lowest logs): they take the room the
	 * zones need and hide the ground monsters spawn on. Felled with the tidying.
	 */
	private static void trees(ServerWorld w, BaseMap map, Zone home, List<Work> out) {
		java.util.Set<BlockPos> bases = new java.util.HashSet<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = home.x1; x <= home.x2; x++) {
			for (int z = home.z1; z <= home.z2; z++) {
				if (map.ground(x, z) != BaseMap.Ground.TREE || !w.isChunkLoaded(m.set(x, 0, z))) {
					continue;
				}
				int y = map.groundY(x, z);
				m.set(x, y, z);
				if (!w.getBlockState(m).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
					continue; // already felled since the map looked
				}
				for (int i = 0; i < 32 && w.getBlockState(m.down()).isIn(net.minecraft.registry.tag.BlockTags.LOGS); i++) {
					m.move(0, -1, 0);
				}
				BlockPos base = m.toImmutable();
				if (bases.size() < MAX_TREES && !Builds.recorded(w, base) && Builds.partOfTree(w, base) && bases.add(base)) {
					out.add(new Work(Kind.FELL, base));
				}
			}
		}
	}

	/** Trees listed at once (the felling job clears them all anyway). */
	private static final int MAX_TREES = 16;

	/** Enough wood in the chests for fences (a fence round home takes a few stacks of planks). */
	private static boolean woodToSpare(Colony colony) {
		return colony.count(com.botcraft.action.Inventories::isLog) >= 24;
	}

	/** Fence posts round the home zone (gates where paths cross it and mid-side), where the ground is open. */
	private static void fences(Colony colony, ServerWorld w, BaseMap map, Zone home, List<Work> out) {
		int midX = (home.x1 + home.x2) / 2;
		int midZ = (home.z1 + home.z2) / 2;
		List<int[]> ring = new ArrayList<>();
		for (int x = home.x1; x <= home.x2; x++) {
			ring.add(new int[]{x, home.z1});
			ring.add(new int[]{x, home.z2});
		}
		for (int z = home.z1 + 1; z < home.z2; z++) {
			ring.add(new int[]{home.x1, z});
			ring.add(new int[]{home.x2, z});
		}
		for (int[] xz : ring) {
			int x = xz[0];
			int z = xz[1];
			BaseMap.Ground g = map.ground(x, z);
			if (g == BaseMap.Ground.UNKNOWN || g == BaseMap.Ground.WATER || g == BaseMap.Ground.LAVA || g == BaseMap.Ground.TREE) {
				continue;
			}
			BlockPos p = new BlockPos(x, map.groundY(x, z) + 1, z);
			if (!fenceSpot(w, p)) {
				continue;
			}
			boolean gate = Zones.onPath(colony, x, z) || x == midX || z == midZ;
			out.add(new Work(gate ? Kind.GATE : Kind.FENCE, p));
		}
	}

	/** Open ground for a fence post or gate: room, no fluid, something solid under it. */
	public static boolean fenceSpot(ServerWorld w, BlockPos p) {
		BlockState s = w.getBlockState(p);
		return s.isReplaceable() && s.getFluidState().isEmpty() && w.getBlockState(p.down()).isSideSolidFullSquare(w, p.down(), net.minecraft.util.math.Direction.UP);
	}

	private static void litter(Colony colony, ServerWorld w, BaseMap map, Zone home, String dim, List<Work> out) {
		for (BlockPos p : colony.placedIn(dim)) {
			if (!home.contains(p) || !w.isChunkLoaded(p)) {
				continue;
			}
			BlockState s = w.getBlockState(p);
			if (!BreakPolicy.placedByCompanion(w, p, s)) {
				if (s.isAir()) {
					colony.forgetPlaced(dim, p);
				}
				continue;
			}
			int level = Zones.groundLevel(colony, map, p.getX(), p.getZ());
			if (level == Integer.MIN_VALUE || p.getY() < level - MAX_DEPTH) {
				continue; // not known yet, or down a cave
			}
			if (p.getY() <= level) {
				colony.forgetPlaced(dim, p); // part of the ground now
				continue;
			}
			if (holdsUp(w, p)) {
				continue;
			}
			out.add(new Work(Kind.LITTER, p));
		}
	}

	/** Something built rests on this block (a chest put down on a pillar): taking it away would leave it floating. */
	private static boolean holdsUp(ServerWorld w, BlockPos p) {
		BlockPos up = p.up();
		return Builds.recorded(w, up) || Builds.builtMaterial(w.getBlockState(up));
	}

	private static void holes(Colony colony, ServerWorld w, Zone home, String dim, List<Work> out) {
		for (BlockPos p : colony.dugIn(dim)) {
			if (!w.isChunkLoaded(p)) {
				continue;
			}
			BlockState s = w.getBlockState(p);
			if (!home.contains(p) || !s.isReplaceable() || Zones.isWaterSpot(colony, p.getX(), p.getZ())) {
				colony.forgetDug(dim, p); // filled (or home moved, or it's for a farm's water)
				continue;
			}
			if (s.getFluidState().isEmpty()) {
				out.add(new Work(Kind.FILL, p));
			}
		}
	}

	private static void level(Colony colony, ServerWorld w, BaseMap map, Zone z, List<Work> out) {
		int level = z.y - 1;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = z.x1; x <= z.x2; x++) {
			for (int zz = z.z1; zz <= z.z2; zz++) {
				BaseMap.Ground g = map.ground(x, zz);
				if (g == BaseMap.Ground.UNKNOWN || g == BaseMap.Ground.WATER || g == BaseMap.Ground.LAVA || g == BaseMap.Ground.TREE
					|| g == BaseMap.Ground.BUILT || !w.isChunkLoaded(m.set(x, level, zz)) || Zones.isWaterSpot(colony, x, zz)) {
					continue;
				}
				int gy = map.groundY(x, zz);
				if (gy > level && gy - level <= MAX_STEP && (g == BaseMap.Ground.SOIL || g == BaseMap.Ground.STONE || g == BaseMap.Ground.SAND)) {
					// Nothing built on it, and every block of the bump natural ground.
					if (holdsUp(w, m.set(x, gy, zz)) || Builds.builtMaterial(w.getBlockState(m.set(x, gy + 2, zz)))) {
						continue;
					}
					boolean natural = true;
					for (int y = level + 1; y <= gy && natural; y++) {
						BlockState s = w.getBlockState(m.set(x, y, zz));
						natural = BreakPolicy.isNaturalTerrain(s) && Builds.isNatural(w, m, s);
					}
					if (natural) {
						for (int y = gy; y > level; y--) {
							out.add(new Work(Kind.DIG, new BlockPos(x, y, zz)));
						}
					}
				} else if (gy < level && level - gy <= MAX_STEP) {
					boolean open = true;
					for (int y = gy + 1; y <= level && open; y++) {
						BlockState s = w.getBlockState(m.set(x, y, zz));
						open = s.isReplaceable() && s.getFluidState().isEmpty();
					}
					if (open) {
						for (int y = gy + 1; y <= level; y++) {
							out.add(new Work(Kind.FILL, new BlockPos(x, y, zz)));
						}
					}
				}
			}
		}
	}

	/**
	 * A companion is breaking a block: if it's home's natural ground (at or under the usual
	 * ground height there), remember the hole so it gets filled back in.
	 */
	public static void breaking(ServerWorld w, BlockPos pos, BlockState state) {
		Colony colony = Colony.get();
		if (colony == null || !Experiments.on("upkeep") || !Zones.inHome(colony, w, pos) || !BreakPolicy.isNaturalTerrain(state)
			|| BreakPolicy.placedByCompanion(w, pos, state) || Builds.recorded(w, pos)) {
			return;
		}
		int level = Zones.groundLevel(colony, BaseMap.of(colony.id()), pos.getX(), pos.getZ());
		BlockPos base = colony.base();
		if (level == Integer.MIN_VALUE && base != null) {
			level = base.getY() - 1;
		}
		if (pos.getY() <= level && pos.getY() >= level - MAX_DEPTH) {
			colony.recordDug(w.getRegistryKey().getValue().toString(), pos.toImmutable());
		}
	}

	/** In words, for /companion colony. */
	@Nullable
	public static String summary() {
		List<Work> latest = latest();
		if (latest.isEmpty()) {
			return null;
		}
		int litter = 0;
		int dig = 0;
		int fill = 0;
		int fence = 0;
		for (Work k : latest) {
			switch (k.kind()) {
				case LITTER, CLEAR, FELL -> litter++;
				case DIG -> dig++;
				case FILL -> fill++;
				case FENCE, GATE -> fence++;
			}
		}
		return litter + " litter block(s) to take down, " + dig + " bump block(s) to level, " + fill + " hole(s) to fill"
			+ (fence > 0 ? ", " + fence + " fence post(s) to put up" : "");
	}

	/** For tests. */
	public static void clear() {
		LATEST.clear();
		LATEST_AT.clear();
	}
}

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
 * </ul>
 */
public final class Upkeep {
	public enum Kind { LITTER, DIG, FILL }

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

	private static List<Work> latest = List.of();
	private static long latestAt = Long.MIN_VALUE;

	private Upkeep() {
	}

	/** What needs doing at home, as last found. */
	public static List<Work> latest() {
		return latest;
	}

	/** What needs doing at home now (found again if the last look is older than {@code maxAge} ticks). */
	public static List<Work> current(Colony colony, long now, long maxAge) {
		if (latestAt == Long.MIN_VALUE || now - latestAt > maxAge || now < latestAt) {
			latest = find(colony);
			latestAt = now;
		}
		return latest;
	}

	/** Look around home for work. */
	public static List<Work> find(Colony colony) {
		ServerWorld w = colony.world();
		BaseMap map = BaseMap.get();
		Zone home = colony.zone(Zones.HOME);
		if (w == null || home == null || map == null || !map.complete() || !Experiments.on("upkeep")) {
			return List.of();
		}
		String dim = w.getRegistryKey().getValue().toString();
		List<Work> out = new ArrayList<>();
		litter(colony, w, map, home, dim, out);
		holes(colony, w, home, dim, out);
		for (String kind : Zones.AREAS) {
			Zone z = colony.zone(kind);
			if (z != null) {
				level(w, map, z, out);
			}
		}
		BlockPos base = colony.base();
		// Litter and bumps from the top down, holes from the bottom up; nearest the base first.
		out.sort(Comparator.comparingInt((Work k) -> k.kind().ordinal())
			.thenComparingInt(k -> k.kind() == Kind.FILL ? k.pos().getY() : -k.pos().getY())
			.thenComparingDouble(k -> base == null ? 0 : k.pos().getSquaredDistance(base)));
		return out.size() > MAX_WORK ? List.copyOf(out.subList(0, MAX_WORK)) : out;
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
			if (!home.contains(p) || !s.isReplaceable()) {
				colony.forgetDug(dim, p); // filled (or home moved)
				continue;
			}
			if (s.getFluidState().isEmpty()) {
				out.add(new Work(Kind.FILL, p));
			}
		}
	}

	private static void level(ServerWorld w, BaseMap map, Zone z, List<Work> out) {
		int level = z.y - 1;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = z.x1; x <= z.x2; x++) {
			for (int zz = z.z1; zz <= z.z2; zz++) {
				BaseMap.Ground g = map.ground(x, zz);
				if (g == BaseMap.Ground.UNKNOWN || g == BaseMap.Ground.WATER || g == BaseMap.Ground.LAVA || g == BaseMap.Ground.TREE
					|| g == BaseMap.Ground.BUILT || !w.isChunkLoaded(m.set(x, level, zz))) {
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
		int level = Zones.groundLevel(colony, BaseMap.get(), pos.getX(), pos.getZ());
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
		if (latest.isEmpty()) {
			return null;
		}
		int litter = 0;
		int dig = 0;
		int fill = 0;
		for (Work k : latest) {
			switch (k.kind()) {
				case LITTER -> litter++;
				case DIG -> dig++;
				case FILL -> fill++;
			}
		}
		return litter + " litter block(s) to take down, " + dig + " bump block(s) to level, " + fill + " hole(s) to fill";
	}

	/** For tests. */
	public static void clear() {
		latest = List.of();
		latestAt = Long.MIN_VALUE;
	}
}

package com.botcraft.colony;

import com.botcraft.Companion;
import com.botcraft.faction.Factions;
import com.botcraft.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing where a faction settles, the way a player would size up a spot: mostly flat ground
 * (room for the core, beds, farm and pen), water and trees nearby, no lava, not in the sea, not
 * on top of somebody's buildings, and not too close to another faction's base (peaceful factions
 * keep further away). Candidates are the founder's spot and rings around it; a sparse look at
 * each (a column every 4 blocks), only in loaded chunks.
 */
public final class SiteSurvey {
	/** Half-size of the area looked at around a candidate. */
	private static final int RADIUS = 20;
	private static final int STEP = 4;
	/** Never settle closer than this to another faction's base (factions want room to grow). */
	public static final double MIN_DISTANCE = 160;
	/** Room from other bases counts for more, up to this far. */
	private static final double ROOM = 320;
	/** Never settle within this many chunks of another faction's land. */
	private static final int LAND_GAP = 3;

	public record Site(BlockPos pos, double score, String why) {
	}

	private SiteSurvey() {
	}

	/** The best place for {@code founder}'s faction to settle near where it is, or null if nowhere will do. */
	@Nullable
	public static Site choose(Companion founder) {
		var p = founder.player();
		if (p == null) {
			return null;
		}
		ServerWorld w = p.getServerWorld();
		BlockPos from = p.getBlockPos();
		String faction = Factions.idOf(founder);
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(from);
		for (int ring : new int[]{24, 48, 72}) {
			for (int i = 0; i < 8; i++) {
				double a = i * Math.PI / 4 + ring * 0.01;
				candidates.add(from.add((int) (Math.cos(a) * ring), 0, (int) (Math.sin(a) * ring)));
			}
		}
		Site best = null;
		for (BlockPos c : candidates) {
			Site s = score(w, c, faction, from);
			if (s != null && (best == null || s.score() > best.score())) {
				best = s;
			}
		}
		if (best != null) {
			AiLog.log(founder.name(), "site survey: best {} score {} ({})", best.pos().toShortString(), String.format("%.2f", best.score()), best.why());
		}
		return best != null && best.score() > 0.25 ? best : null;
	}

	/** How good a spot is (null if it can't be judged: unloaded, lava, the sea, too near an enemy). */
	@Nullable
	public static Site score(ServerWorld w, BlockPos center, String faction, BlockPos from) {
		List<Integer> heights = new ArrayList<>();
		int water = 0;
		int trees = 0;
		int built = 0;
		int columns = 0;
		for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
			for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!w.isChunkLoaded(x >> 4, z >> 4)) {
					return null;
				}
				columns++;
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
				BlockState s = w.getBlockState(new BlockPos(x, top, z));
				if (s.getFluidState().isIn(FluidTags.LAVA)) {
					return null;
				}
				if (s.getFluidState().isIn(FluidTags.WATER)) {
					water++;
					continue;
				}
				if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)) {
					trees++;
					int ground = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					heights.add(ground);
					continue;
				}
				if (Builds.builtMaterial(s)) {
					built++;
				}
				heights.add(top);
			}
		}
		if (columns == 0 || heights.size() < columns / 2) {
			return null; // mostly water
		}
		heights.sort(Integer::compare);
		int median = heights.get(heights.size() / 2);
		int flat = 0;
		for (int h : heights) {
			if (Math.abs(h - median) <= 1) {
				flat++;
			}
		}
		double flatness = flat / (double) columns;
		double waterShare = water / (double) columns;
		// Open ground on the site itself (the zones need it; trees there have to be felled first),
		// with water and woods close by but not on it: a clearing by a lake beats the middle of a wood.
		double open = (columns - water - trees) / (double) columns;
		int[] around = surroundings(w, center);
		int waterNear = water + around[0];
		int treesNear = around[1];
		double score = 0.45 * flatness + 0.25 * open
			+ 0.15 * Math.min(1.0, waterNear / 4.0) - (waterShare > 0.3 ? 0.4 : 0.0)
			+ 0.15 * Math.min(1.0, (trees + treesNear) / 8.0)
			- 0.6 * built / (double) columns
			- 0.002 * Math.sqrt(center.getSquaredDistance(from));
		// Room from other factions' bases.
		double nearest = Double.MAX_VALUE;
		for (Colony other : Colony.all()) {
			if (!other.id().equals(faction) && other.hasBase() && other.world() == w) {
				nearest = Math.min(nearest, Math.sqrt(other.base().getSquaredDistance(center)));
			}
		}
		if (nearest < MIN_DISTANCE || nearLand(w, center, faction)) {
			return null;
		}
		if (nearest < Double.MAX_VALUE) {
			score += 0.2 * Math.min(1.0, nearest / ROOM);
		}
		BlockPos at = new BlockPos(center.getX(), median + 1, center.getZ());
		String why = String.format(java.util.Locale.ROOT, "flat %.0f%%, open %.0f%%, water %d (+%d near), trees %d (+%d near), built %d%s",
			flatness * 100, open * 100, water, around[0], trees, around[1], built,
			nearest < Double.MAX_VALUE ? String.format(java.util.Locale.ROOT, ", %.0f from the nearest base", nearest) : "");
		return new Site(at, score, why);
	}

	/** Water and tree columns in a ring just outside the site (a lake or a wood a short walk away). */
	private static int[] surroundings(ServerWorld w, BlockPos center) {
		int water = 0;
		int trees = 0;
		for (int dx = -RING; dx <= RING; dx += 6) {
			for (int dz = -RING; dz <= RING; dz += 6) {
				if (Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS) {
					continue;
				}
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!w.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				BlockState s = w.getBlockState(new BlockPos(x, w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1, z));
				if (s.getFluidState().isIn(FluidTags.WATER)) {
					water++;
				} else if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)) {
					trees++;
				}
			}
		}
		return new int[]{water, trees};
	}

	/** How far out the ring looked at for water and woods reaches. */
	private static final int RING = 38;

	/** Another faction holds land within {@link #LAND_GAP} chunks of here. */
	private static boolean nearLand(ServerWorld w, BlockPos center, String faction) {
		for (int dx = -LAND_GAP; dx <= LAND_GAP; dx++) {
			for (int dz = -LAND_GAP; dz <= LAND_GAP; dz++) {
				String owner = com.botcraft.faction.Territory.owner(w, center.add(dx * 16, 0, dz * 16));
				if (owner != null && !owner.equals(faction)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Nowhere here will do (too close to others, or no good ground): a spot to walk to and look
	 * again, {@code step} blocks on, away from the nearest other base (or any way, if there is none).
	 */
	public static BlockPos scoutFrom(ServerWorld w, BlockPos from, String faction, int step, java.util.Random random) {
		BlockPos nearest = null;
		double bestD = Double.MAX_VALUE;
		for (Colony other : Colony.all()) {
			if (!other.id().equals(faction) && other.hasBase() && other.world() == w) {
				double d = other.base().getSquaredDistance(from);
				if (d < bestD) {
					bestD = d;
					nearest = other.base();
				}
			}
		}
		double angle;
		if (nearest == null || nearest.equals(from)) {
			angle = random.nextDouble() * Math.PI * 2;
		} else {
			angle = Math.atan2(from.getZ() - nearest.getZ(), from.getX() - nearest.getX()) + (random.nextDouble() - 0.5) * Math.PI / 2;
		}
		int x = from.getX() + (int) (Math.cos(angle) * step);
		int z = from.getZ() + (int) (Math.sin(angle) * step);
		int y = w.isChunkLoaded(x >> 4, z >> 4) ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) : from.getY();
		return new BlockPos(x, y, z);
	}
}

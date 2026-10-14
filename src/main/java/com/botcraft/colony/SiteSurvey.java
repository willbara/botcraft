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
	/** Never settle closer than this to another faction's base. */
	public static final double MIN_DISTANCE = 64;

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
		double score = 0.55 * flatness
			+ 0.15 * Math.min(1.0, water / 4.0) - (waterShare > 0.3 ? 0.4 : 0.0)
			+ 0.15 * Math.min(1.0, trees / 8.0) - (trees / (double) columns > 0.6 ? 0.2 : 0.0)
			- 0.6 * built / (double) columns
			- 0.002 * Math.sqrt(center.getSquaredDistance(from));
		// Room from other factions' bases.
		double nearest = Double.MAX_VALUE;
		for (Colony other : Colony.all()) {
			if (!other.id().equals(faction) && other.hasBase() && other.world() == w) {
				nearest = Math.min(nearest, Math.sqrt(other.base().getSquaredDistance(center)));
			}
		}
		if (nearest < MIN_DISTANCE) {
			return null;
		}
		if (nearest < Double.MAX_VALUE) {
			score += 0.1 * Math.min(1.0, nearest / 256.0);
		}
		BlockPos at = new BlockPos(center.getX(), median + 1, center.getZ());
		String why = String.format(java.util.Locale.ROOT, "flat %.0f%%, water %d, trees %d, built %d%s", flatness * 100, water, trees, built,
			nearest < Double.MAX_VALUE ? String.format(java.util.Locale.ROOT, ", %.0f from the nearest base", nearest) : "");
		return new Site(at, score, why);
	}
}

package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Terrain;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/** Finding blocks and free spots around the companion. */
final class Spots {
	private Spots() {
	}

	/** An empty spot next to us, on solid ground, within reach, not where we stand. */
	@Nullable
	static BlockPos placeSpot(Companion c) {
		return placeSpot(c, java.util.Set.of());
	}

	/** Like {@link #placeSpot(Companion)}, skipping spots that already failed. */
	@Nullable
	static BlockPos placeSpot(Companion c, java.util.Set<BlockPos> exclude) {
		CompanionPlayer p = c.player();
		ServerWorld world = p.getServerWorld();
		Terrain t = new Terrain(world);
		BlockPos feet = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos spot : BlockPos.iterate(feet.add(-3, -1, -3), feet.add(3, 1, 3))) {
			// The block under it must have a solid top face: leaves, glass panes, fences don't take a block.
			BlockPos under = spot.down();
			if (world.getBlockState(spot).isReplaceable() && world.getFluidState(spot).isEmpty() && t.placeableAgainst(under)
				&& world.getBlockState(under).isSideSolidFullSquare(world, under, net.minecraft.util.math.Direction.UP)
				&& !exclude.contains(spot) && !p.getBoundingBox().intersects(new Box(spot)) && !reserved(world, spot) && c.actions().canReachBlock(spot)
				&& c.actions().placement(spot) != null
				// Anyone standing there (another companion, a sheep...) blocks placement.
				&& world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new Box(spot), e -> e.isAlive()).isEmpty()) {
				// Same level first, then one up/down; nearest within that.
				double d = spot.getSquaredDistance(feet) + Math.abs(spot.getY() - feet.getY()) * 20;
				if (d < bestD && spot.getSquaredDistance(feet) >= 1) {
					bestD = d;
					best = spot.toImmutable();
				}
			}
		}
		return best;
	}

	/** Part of the base kept clear (bed row, farm, pen, paths): nothing gets put down there. */
	private static boolean reserved(ServerWorld world, BlockPos spot) {
		var colony = com.botcraft.colony.Colony.get();
		return colony != null && com.botcraft.colony.Zones.reserved(colony, world, spot);
	}

	/**
	 * Somewhere nearby to stand on real ground (not leaves) with room around it, for when the
	 * current spot is too cramped to put a block down: a tree top, a pillar, a ledge.
	 */
	static BlockPos roomySpot(Companion c, java.util.Random random) {
		CompanionPlayer p = c.player();
		ServerWorld world = p.getServerWorld();
		BlockPos feet = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -10; dx <= 10; dx += 2) {
			for (int dz = -10; dz <= 10; dz += 2) {
				int x = feet.getX() + dx;
				int z = feet.getZ() + dz;
				int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos stand = new BlockPos(x, y, z);
				BlockPos under = stand.down();
				if (Math.abs(y - feet.getY()) > 24 || !world.getFluidState(under).isEmpty()
					|| !world.getBlockState(under).isSideSolidFullSquare(world, under, net.minecraft.util.math.Direction.UP)
					|| !world.getBlockState(stand).isReplaceable() || !world.getBlockState(stand.up()).isReplaceable()) {
					continue;
				}
				double d = stand.getSquaredDistance(feet) + random.nextInt(8);
				if (dx * dx + dz * dz >= 9 && d < bestD) {
					bestD = d;
					best = stand;
				}
			}
		}
		return best != null ? best : feet.add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
	}

	/** Nearest block of a type within a horizontal radius (and 4 up/down), or null. */
	@Nullable
	static BlockPos nearest(CompanionPlayer p, Block block, int radius) {
		BlockPos center = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-radius, -4, -radius), center.add(radius, 4, radius))) {
			if (p.getWorld().getBlockState(pos).isOf(block)) {
				double d = pos.getSquaredDistance(center);
				if (d < bestD) {
					bestD = d;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}
}

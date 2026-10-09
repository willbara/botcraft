package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Terrain;
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
			if (world.getBlockState(spot).isReplaceable() && world.getFluidState(spot).isEmpty() && t.placeableAgainst(spot.down())
				&& !exclude.contains(spot) && !p.getBoundingBox().intersects(new Box(spot)) && c.actions().canReachBlock(spot)
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

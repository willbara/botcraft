package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Terrain;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/** Finding blocks and free spots around the companion. */
final class Spots {
	private Spots() {
	}

	/** An empty spot next to us, on solid ground, within reach, not where we stand. */
	@Nullable
	static BlockPos placeSpot(Companion c) {
		CompanionPlayer p = c.player();
		ServerWorld world = p.getServerWorld();
		Terrain t = new Terrain(world);
		BlockPos feet = p.getBlockPos();
		for (int dist = 1; dist <= 2; dist++) {
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos spot = feet.offset(d, dist);
				if (world.getBlockState(spot).isReplaceable() && world.getFluidState(spot).isEmpty() && t.placeableAgainst(spot.down())
					&& c.actions().canReachBlock(spot) && !p.getBoundingBox().intersects(new Box(spot))) {
					return spot;
				}
			}
		}
		return null;
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

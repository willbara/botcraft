package com.mcfriends.entity;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/** Finding a dry, solid place to put a companion (respawning, rejoining). */
public final class SafeSpot {
	private SafeSpot() {
	}

	/** The nearest spot within {@code radius} to stand: solid floor, two clear dry blocks, no lava next to it. */
	@Nullable
	public static BlockPos near(ServerWorld world, BlockPos center, int radius) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.iterate(center.add(-radius, -3, -radius), center.add(radius, 3, radius))) {
			if (ok(world, pos)) {
				double d = pos.getSquaredDistance(center);
				if (d < bestD) {
					bestD = d;
					best = pos.toImmutable();
				}
			}
		}
		return best;
	}

	public static boolean ok(ServerWorld world, BlockPos feet) {
		BlockPos below = feet.down();
		if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) {
			return false;
		}
		for (BlockPos b : new BlockPos[]{feet, feet.up()}) {
			var s = world.getBlockState(b);
			if (!s.getCollisionShape(world, b).isEmpty() || !s.getFluidState().isEmpty()) {
				return false;
			}
		}
		for (Direction d : Direction.values()) {
			if (world.getFluidState(feet.offset(d)).isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
				return false;
			}
		}
		return true;
	}
}

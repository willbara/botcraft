package com.mcfriends.nav;

import net.minecraft.util.math.BlockPos;

/** A* goal over feet positions. */
public interface Goal {
	boolean isGoal(BlockPos feet);

	double heuristic(BlockPos feet);

	/** Within {@code radius} (horizontal) and 2 blocks (vertical) of a point. */
	static Goal near(BlockPos target, double radius) {
		return new Near(target.toImmutable(), radius);
	}

	/** Stand somewhere the eyes can reach the block (for mining / using it). */
	static Goal reach(BlockPos block, double reach) {
		return new Reach(block.toImmutable(), reach);
	}

	static double octile(int dx, int dz) {
		dx = Math.abs(dx);
		dz = Math.abs(dz);
		return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
	}

	record Near(BlockPos target, double radius) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			int dx = feet.getX() - target.getX();
			int dz = feet.getZ() - target.getZ();
			return dx * dx + dz * dz <= radius * radius && Math.abs(feet.getY() - target.getY()) <= 2;
		}

		@Override
		public double heuristic(BlockPos feet) {
			double d = octile(feet.getX() - target.getX(), feet.getZ() - target.getZ()) - radius;
			return Math.max(0, d) + Math.abs(feet.getY() - target.getY()) * 0.5;
		}
	}

	record Reach(BlockPos block, double reach) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			if (feet.equals(block) || feet.up().equals(block)) {
				return false;
			}
			double ex = feet.getX() + 0.5 - (block.getX() + 0.5);
			double ey = feet.getY() + 1.62 - (block.getY() + 0.5);
			double ez = feet.getZ() + 0.5 - (block.getZ() + 0.5);
			return ex * ex + ey * ey + ez * ez <= reach * reach;
		}

		@Override
		public double heuristic(BlockPos feet) {
			double d = octile(feet.getX() - block.getX(), feet.getZ() - block.getZ()) - reach * 0.7;
			return Math.max(0, d) + Math.abs(feet.getY() + 1 - block.getY()) * 0.5;
		}
	}
}

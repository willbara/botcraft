package com.botcraft.nav;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

/**
 * A* goal over feet positions. Heuristics are in ticks, Baritone's way: the horizontal part is
 * the octile distance at sprint speed ({@link PathFinder#H_PER_BLOCK}), going up costs a jump a
 * block, going down half a two-block fall a block.
 */
public interface Goal {
	boolean isGoal(BlockPos feet);

	double heuristic(BlockPos feet);

	/** Within {@code radius} (horizontal) and 2 blocks (vertical) of a point. */
	static Goal near(BlockPos target, double radius) {
		return new Near(target.toImmutable(), radius, 2, 2);
	}

	/** Within {@code radius} (horizontal), at most {@code below} blocks under and {@code above} over the point. */
	static Goal near(BlockPos target, double radius, int below, int above) {
		return new Near(target.toImmutable(), radius, below, above);
	}

	/** Stand somewhere the eyes can reach the block (for mining / using it). */
	static Goal reach(BlockPos block, double reach) {
		return new Reach(block.toImmutable(), reach, null);
	}

	/**
	 * Like {@link #reach} but also requires a clear line from the eyes to the block, ignoring
	 * the standing spot's own two blocks (the path will clear those).
	 */
	static Goal reachVisible(BlockView world, BlockPos block, double reach) {
		return new Reach(block.toImmutable(), reach, world);
	}

	/**
	 * Get the feet or the head into the block (Baritone's GoalTwoBlocks), or stand two below it
	 * when nothing would fall on us (GoalThreeBlocks): the path's last move mines the block, so
	 * reaching the goal is mining it. How Baritone's MineProcess paths to ore.
	 * @param shaft whether standing two below also counts (false when the block above would fall)
	 */
	static Goal into(BlockPos block, boolean shaft) {
		return new Into(block.toImmutable(), shaft);
	}

	/**
	 * Any of several goals (Baritone's GoalComposite): A* reaches whichever is cheapest to get
	 * to, which is how a target is chosen among many candidates without flip-flopping.
	 */
	static Goal anyOf(java.util.List<Goal> goals) {
		return new AnyOf(java.util.List.copyOf(goals));
	}

	/**
	 * Anywhere under open sky: the way out of a cave, whichever way it is. The heuristic is the
	 * height still to climb.
	 */
	static Goal openSky(net.minecraft.world.WorldView world) {
		return new OpenSky(world);
	}

	/** Baritone's GoalXZ: octile distance at the heuristic's ticks per block. */
	static double xz(int dx, int dz) {
		dx = Math.abs(dx);
		dz = Math.abs(dz);
		int diagonal = Math.min(dx, dz);
		int straight = Math.max(dx, dz) - diagonal;
		return (diagonal * PathFinder.SQRT2 + straight) * PathFinder.H_PER_BLOCK;
	}

	/** Baritone's GoalYLevel: a jump per block up, half a two-block fall per block down. */
	static double y(int dy) {
		if (dy > 0) {
			return dy * PathFinder.JUMP_ONE_BLOCK;
		}
		if (dy < 0) {
			return -dy * PathFinder.fallTicks(2) / 2;
		}
		return 0;
	}

	/** Baritone's GoalBlock: xz plus y, with dy the climb still to do (positive = up). */
	static double block(int dx, int dy, int dz) {
		return xz(dx, dz) + y(dy);
	}

	record OpenSky(net.minecraft.world.WorldView world) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			return Terrain.openSky(world, feet.up());
		}

		@Override
		public double heuristic(BlockPos feet) {
			int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
			return y(Math.max(0, top - feet.getY()));
		}
	}

	/** For composite goals: the index of the sub-goal satisfied at this spot, or -1. */
	default int which(BlockPos feet) {
		return isGoal(feet) ? 0 : -1;
	}

	record AnyOf(java.util.List<Goal> goals) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			for (Goal g : goals) {
				if (g.isGoal(feet)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public double heuristic(BlockPos feet) {
			double best = Double.MAX_VALUE;
			for (Goal g : goals) {
				best = Math.min(best, g.heuristic(feet));
			}
			return best;
		}

		@Override
		public int which(BlockPos feet) {
			for (int i = 0; i < goals.size(); i++) {
				if (goals.get(i).isGoal(feet)) {
					return i;
				}
			}
			return -1;
		}
	}

	record Near(BlockPos target, double radius, int below, int above) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			int dx = feet.getX() - target.getX();
			int dz = feet.getZ() - target.getZ();
			int dy = feet.getY() - target.getY();
			return dx * dx + dz * dz <= radius * radius && dy >= -below && dy <= above;
		}

		@Override
		public double heuristic(BlockPos feet) {
			double flat = Math.max(0, xz(feet.getX() - target.getX(), feet.getZ() - target.getZ()) - radius * PathFinder.H_PER_BLOCK);
			int climb = target.getY() - feet.getY(); // positive: still below the target
			return flat + y(climb > below ? climb - below : climb < -above ? climb + above : 0);
		}
	}

	record Into(BlockPos block, boolean shaft) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			if (feet.getX() != block.getX() || feet.getZ() != block.getZ()) {
				return false;
			}
			int dy = feet.getY() - block.getY();
			return dy == 0 || dy == -1 || (shaft && dy == -2);
		}

		@Override
		public double heuristic(BlockPos feet) {
			int dy = block.getY() - feet.getY();
			// Feet one below (head in the block) is as good as feet in it; two below too, for a shaft.
			int climb = dy > 0 ? (shaft ? Math.max(0, dy - 2) : Math.max(0, dy - 1)) : dy;
			return Goal.block(feet.getX() - block.getX(), climb, feet.getZ() - block.getZ());
		}
	}

	record Reach(BlockPos block, double reach, @Nullable BlockView world) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			if (feet.equals(block) || feet.up().equals(block)) {
				return false;
			}
			double ex = feet.getX() + 0.5 - (block.getX() + 0.5);
			double ey = feet.getY() + 1.62 - (block.getY() + 0.5);
			double ez = feet.getZ() + 0.5 - (block.getZ() + 0.5);
			if (ex * ex + ey * ey + ez * ez > reach * reach) {
				return false;
			}
			return world == null || clearLine(feet);
		}

		/** Sample the eye->block line; any solid block on it (other than our body cells) blocks it. */
		private boolean clearLine(BlockPos feet) {
			Vec3d from = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
			Vec3d to = Vec3d.ofCenter(block);
			Vec3d d = to.subtract(from);
			int steps = (int) Math.ceil(d.length() / 0.2);
			BlockPos.Mutable m = new BlockPos.Mutable();
			for (int i = 1; i < steps; i++) {
				Vec3d pt = from.add(d.multiply((double) i / steps));
				m.set(pt.x, pt.y, pt.z);
				if (m.equals(block) || m.equals(feet) || m.equals(feet.up())) {
					continue;
				}
				if (!world.getBlockState(m).getCollisionShape(world, m).isEmpty()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public double heuristic(BlockPos feet) {
			double flat = Math.max(0, xz(feet.getX() - block.getX(), feet.getZ() - block.getZ()) - reach * 0.7 * PathFinder.H_PER_BLOCK);
			int dy = block.getY() - (feet.getY() + 1);
			return flat + y(dy > 2 ? dy - 2 : dy < -2 ? dy + 2 : 0);
		}
	}
}

package com.botcraft.nav;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

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
	 * Any of several goals (Baritone's GoalComposite): A* reaches whichever is cheapest to get
	 * to, which is how a target is chosen among many candidates without flip-flopping.
	 */
	static Goal anyOf(java.util.List<Goal> goals) {
		return new AnyOf(java.util.List.copyOf(goals));
	}

	/**
	 * Anywhere under open sky: the way out of a cave, whichever way it is. The heuristic is the
	 * height still to climb, so a companion boxed in digs straight up (Navigator's shaft).
	 */
	static Goal openSky(net.minecraft.world.WorldView world) {
		return new OpenSky(world);
	}

	record OpenSky(net.minecraft.world.WorldView world) implements Goal {
		@Override
		public boolean isGoal(BlockPos feet) {
			return Terrain.openSky(world, feet.up());
		}

		@Override
		public double heuristic(BlockPos feet) {
			int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
			return Math.max(0, top - feet.getY()) * 0.5;
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
			double d = octile(feet.getX() - block.getX(), feet.getZ() - block.getZ()) - reach * 0.7;
			return Math.max(0, d) + Math.abs(feet.getY() + 1 - block.getY()) * 0.5;
		}
	}
}

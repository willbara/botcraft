package com.mcfriends.perception;

import com.mcfriends.util.TickBudget;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds caves near a spot: places well below the surface where there is open air with a floor
 * to stand on. Columns are sampled on a grid and the work is spread over ticks (bounded by the
 * shared tick budget). Only loaded chunks are read.
 *
 * <p>Used by mining ("look for a cave before digging") and by free-mode exploring.
 */
public class CaveFinder {
	private static final int STEP = 3;
	private static final int MIN_DEPTH = 6;
	private static final int MAX_DEPTH = 64;

	private ServerWorld world;
	private BlockPos center = BlockPos.ORIGIN;
	private int radius;
	private int index;
	private int side;
	private final List<BlockPos> found = new ArrayList<>();
	private boolean running;

	public void start(ServerWorld world, BlockPos center, int radius) {
		this.world = world;
		this.center = center.toImmutable();
		this.radius = radius;
		this.side = radius * 2 / STEP + 1;
		this.index = 0;
		this.found.clear();
		this.running = true;
	}

	public boolean running() {
		return running;
	}

	/** @return true when finished */
	public boolean tick() {
		if (!running) {
			return true;
		}
		int total = side * side;
		int done = 0;
		while (index < total) {
			if (done > 0 && (done & 7) == 0 && !TickBudget.hasTime()) {
				return false;
			}
			int ix = index % side;
			int iz = index / side;
			index++;
			done++;
			int x = center.getX() - radius + ix * STEP;
			int z = center.getZ() - radius + iz * STEP;
			if (!world.isChunkLoaded(x >> 4, z >> 4)) {
				continue;
			}
			BlockPos cave = scanColumn(x, z);
			if (cave != null) {
				found.add(cave);
			}
		}
		found.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(center)));
		running = false;
		return true;
	}

	/** Topmost cave floor in this column, at least MIN_DEPTH below the surface. */
	private BlockPos scanColumn(int x, int z) {
		int surface = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
		int bottom = Math.max(world.getBottomY() + 1, surface - MAX_DEPTH);
		BlockPos.Mutable m = new BlockPos.Mutable(x, 0, z);
		for (int y = surface - MIN_DEPTH; y > bottom; y--) {
			BlockState here = world.getBlockState(m.setY(y));
			if (!here.isAir()) {
				continue;
			}
			BlockState above = world.getBlockState(m.setY(y + 1));
			BlockState below = world.getBlockState(m.setY(y - 1));
			if (above.isAir() && !below.isAir() && below.getFluidState().isEmpty()
				&& !below.getCollisionShape(world, m).isEmpty()) {
				return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	/** Cave floors found, nearest to the scan centre first. */
	public List<BlockPos> results() {
		return found;
	}
}

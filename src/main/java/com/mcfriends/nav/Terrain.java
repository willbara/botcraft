package com.mcfriends.nav;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

/**
 * Block classification for path finding, cached per search. Only reads chunks that are
 * already loaded; unloaded chunks are treated as impassable (never triggers chunk loads).
 */
public class Terrain {
	public static final byte AIR = 0;      // nothing to collide with
	public static final byte SOLID = 1;    // has collision
	public static final byte WATER = 2;
	public static final byte LAVA = 3;
	public static final byte DOOR = 4;     // door / fence gate we can open by hand
	public static final byte DANGER = 5;   // fire, berry bush, cobweb, powder snow, ...
	public static final byte UNLOADED = 6;

	private final ServerWorld world;
	private final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
	private final Long2DoubleOpenHashMap tops = new Long2DoubleOpenHashMap();
	private final BlockPos.Mutable mutable = new BlockPos.Mutable();

	public Terrain(ServerWorld world) {
		this.world = world;
		kinds.defaultReturnValue((byte) -1);
		tops.defaultReturnValue(Double.NaN);
	}

	public ServerWorld world() {
		return world;
	}

	public boolean isLoaded(BlockPos pos) {
		return world.isChunkLoaded(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()));
	}

	public BlockState state(BlockPos pos) {
		return world.getBlockState(pos);
	}

	public byte kind(BlockPos pos) {
		long key = pos.asLong();
		byte k = kinds.get(key);
		if (k < 0) {
			k = classify(pos);
			kinds.put(key, k);
		}
		return k;
	}

	private byte classify(BlockPos pos) {
		if (pos.getY() < world.getBottomY() || pos.getY() >= world.getTopY()) {
			return pos.getY() < world.getBottomY() ? SOLID : AIR;
		}
		if (!isLoaded(pos)) {
			return UNLOADED;
		}
		BlockState state = world.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if (fluid.isIn(FluidTags.LAVA)) {
			return LAVA;
		}
		Block b = state.getBlock();
		if (b instanceof AbstractFireBlock || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW
			|| b == Blocks.WITHER_ROSE || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE
			|| b == Blocks.SOUL_CAMPFIRE || b == Blocks.POINTED_DRIPSTONE) {
			return DANGER;
		}
		if ((b instanceof DoorBlock && DoorBlock.canOpenByHand(state)) || b instanceof FenceGateBlock) {
			return DOOR;
		}
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (!shape.isEmpty()) {
			return SOLID;
		}
		if (fluid.isIn(FluidTags.WATER)) {
			return WATER;
		}
		return AIR;
	}

	/** Top of the collision shape (0..1.5) of the block at pos; 0 if no collision. */
	public double top(BlockPos pos) {
		long key = pos.asLong();
		double t = tops.get(key);
		if (Double.isNaN(t)) {
			if (kind(pos) == UNLOADED) {
				t = 0;
			} else {
				VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
				t = shape.isEmpty() ? 0 : shape.getMax(Direction.Axis.Y);
			}
			tops.put(key, t);
		}
		return t;
	}

	/** The body can be in this block (air, water, plants, open-able doors). */
	public boolean passable(BlockPos pos) {
		byte k = kind(pos);
		if (k != AIR && k != WATER && k != DOOR) {
			return false;
		}
		// Fences and walls below stick 0.5 into this block.
		BlockPos below = pos.down();
		return kind(below) != SOLID || top(below) <= 1.0;
	}

	public boolean clearBody(BlockPos feet) {
		return passable(feet) && passable(feet.up());
	}

	/** Something solid to stand on (not a fence top, not magma/fire/cactus). */
	public boolean support(BlockPos pos) {
		byte k = kind(pos);
		if (k != SOLID) {
			return false;
		}
		double t = top(pos);
		return t > 0 && t <= 1.0;
	}

	public boolean isWater(BlockPos pos) {
		return kind(pos) == WATER;
	}

	public boolean isLava(BlockPos pos) {
		return kind(pos) == LAVA;
	}

	public boolean isDoor(BlockPos pos) {
		return kind(pos) == DOOR;
	}

	/** Feet can rest here: body clear and something under it, or swimming. */
	public boolean standable(BlockPos feet) {
		if (isWater(feet)) {
			return passable(feet.up()) || isWater(feet.up());
		}
		return clearBody(feet) && support(feet.down());
	}

	/** Exact feet height when standing in this node. */
	public double floorY(BlockPos feet) {
		if (isWater(feet)) {
			return feet.getY();
		}
		return feet.getY() - 1 + top(feet.down());
	}

	/** Extra cost for nearby dangers. */
	public double hazard(BlockPos feet) {
		double cost = 0;
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos n = feet.offset(d);
			byte k = kind(n);
			byte kb = kind(n.down());
			if (k == LAVA || kb == LAVA) {
				cost += 8;
			} else if (k == DANGER || kb == DANGER) {
				cost += 2;
			}
		}
		if (kind(feet.down()) == LAVA) {
			cost += 20;
		}
		if (isWater(feet)) {
			cost += 2; // swimming is slow
		}
		return cost;
	}

	/** Cost of breaking the block for navigation, or +inf if not allowed. */
	public double breakCost(BlockPos pos) {
		byte k = kind(pos);
		if (k == AIR || k == WATER || k == DOOR) {
			return 0;
		}
		if (k != SOLID) {
			return Double.POSITIVE_INFINITY;
		}
		BlockState s = world.getBlockState(pos);
		if (!BreakPolicy.canBreakForNav(world, mutable.set(pos), s)) {
			return Double.POSITIVE_INFINITY;
		}
		return 2.0 + s.getHardness(world, pos) * 4.0;
	}

	/** A full-face solid block we can click to place against. */
	public boolean placeableAgainst(BlockPos pos) {
		return kind(pos) == SOLID && Block.isShapeFullCube(world.getBlockState(pos).getCollisionShape(world, pos));
	}

	/** Forget cached info (the world changed). */
	public void invalidate(BlockPos pos) {
		for (int dy = -1; dy <= 1; dy++) {
			kinds.remove(pos.up(dy).asLong());
			tops.remove(pos.up(dy).asLong());
		}
	}
}

package com.botcraft.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block classification and movement costs for path finding, cached per search. Only reads
 * chunks that are already loaded; unloaded chunks are impassable (never triggers chunk loads).
 *
 * <p>Costs are in game ticks, following Baritone's model: walking one block takes ~4.6 ticks,
 * breaking a block takes as long as it would really take with the best tool we carry. Natural
 * terrain may always be dug through (what may be broken is {@link BreakPolicy}'s business); the
 * mining time is what keeps paths out of the rock when there's a way round.
 */
public class Terrain {
	public static final byte AIR = 0;      // nothing to collide with
	public static final byte SOLID = 1;    // has collision
	public static final byte WATER = 2;
	public static final byte LAVA = 3;
	public static final byte DOOR = 4;     // door / fence gate we can open by hand
	public static final byte DANGER = 5;   // fire, berry bush, cobweb, powder snow, ...
	public static final byte UNLOADED = 6;
	public static final byte CLIMBABLE = 7; // ladders, vines, scaffolding
	/** Stored next to the kind in the section cache: the block is leaves (kind SOLID). */
	private static final byte LEAF_FLAG = 0x40;

	/** Extra cost near lava, fire and the like (off: Baritone has none, it only refuses to walk into them). */
	private static final boolean HAZARD = com.botcraft.util.Experiments.on("navHazard");
	/** Extra cost per broken block on top of mining time (Baritone: breakBlockAdditionalCost). */
	public static final double BREAK_EXTRA = 2.0;
	/** A block that would take longer than this to mine (obsidian by hand, 20 s) is not in a path's way. */
	public static final double MAX_BREAK_TICKS = 400;

	private final ServerWorld world;
	/**
	 * Block kinds, one array per 16x16x16 section (-1 = not looked at yet). Searches ask about
	 * the same few sections over and over, so this is a hash lookup per section change rather
	 * than per block, which used to be the biggest single cost of path finding.
	 */
	private final Long2ObjectOpenHashMap<byte[]> kindSections = new Long2ObjectOpenHashMap<>();
	private long lastSectionKey = Long.MIN_VALUE;
	private byte[] lastSection;
	/** Loaded chunks by position (null value = not loaded), so each block read skips the chunk manager. */
	private final Long2ObjectOpenHashMap<WorldChunk> chunks = new Long2ObjectOpenHashMap<>();
	private long lastChunkKey = Long.MIN_VALUE;
	@Nullable
	private WorldChunk lastChunk;
	private final Long2DoubleOpenHashMap hazards = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap tops = new Long2DoubleOpenHashMap();
	private final Long2DoubleOpenHashMap breaks = new Long2DoubleOpenHashMap();
	private final BlockPos.Mutable mutable = new BlockPos.Mutable();
	private final boolean dig;
	/** Digging for a mining job (not escaping): the base's ground is off limits. */
	private boolean spareBase;
	private final List<ItemStack> tools = new ArrayList<>();

	public Terrain(ServerWorld world) {
		this(world, false, null);
	}

	public Terrain(ServerWorld world, boolean dig) {
		this(world, dig, null);
	}

	/**
	 * @param dig       allow digging through natural terrain (false only for tests of walking alone)
	 * @param inventory the tools we carry (for realistic break times); null = bare hands
	 */
	public Terrain(ServerWorld world, boolean dig, PlayerInventory inventory) {
		this.world = world;
		this.dig = dig;
		hazards.defaultReturnValue(Double.NaN);
		tops.defaultReturnValue(Double.NaN);
		breaks.defaultReturnValue(Double.NaN);
		if (inventory != null) {
			for (int i = 0; i < inventory.main.size(); i++) {
				ItemStack s = inventory.getStack(i);
				if (!s.isEmpty() && (s.isDamageable() || s.getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) > 1.0F)) {
					tools.add(s);
				}
			}
		}
	}

	public ServerWorld world() {
		return world;
	}

	/** Leave the ground the base stands on alone (see Colony#protectsGround). */
	public Terrain spareBase() {
		this.spareBase = true;
		return this;
	}

	public boolean digMode() {
		return dig;
	}

	/** The colony's home zone, while it applies to this search (see {@link #homeRules}). */
	@Nullable
	private com.botcraft.colony.Colony.Zone home;

	/** Apply home's rules to this search: blocks put down at home cost extra (litter). */
	public Terrain homeRules() {
		var colony = com.botcraft.colony.Colony.get();
		if (colony != null && colony.world() == world && com.botcraft.util.Experiments.on("homeLitter")) {
			home = colony.zone(com.botcraft.colony.Zones.HOME);
		}
		return this;
	}

	/** In the home zone (only once {@link #homeRules} applied it). */
	public boolean inHome(BlockPos pos) {
		return home != null && home.contains(pos);
	}

	@Nullable
	private WorldChunk chunk(BlockPos pos) {
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		long key = ChunkPos.toLong(cx, cz);
		if (key == lastChunkKey) {
			return lastChunk;
		}
		WorldChunk c = chunks.get(key);
		if (c == null && !chunks.containsKey(key)) {
			c = world.getChunkManager().getWorldChunk(cx, cz); // null if not loaded; never loads
			chunks.put(key, c);
		}
		lastChunkKey = key;
		lastChunk = c;
		return c;
	}

	/**
	 * Out under the sky, not counting leaves: the forest floor is outdoors, not underground.
	 * (isSkyVisible is false under a tree, which used to send companions into escape mode and
	 * up into the canopy.)
	 */
	public static boolean openSky(net.minecraft.world.WorldView world, BlockPos pos) {
		if (!com.botcraft.util.Experiments.on("leafSky")) {
			return world.isSkyVisible(pos);
		}
		return pos.getY() >= world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
	}

	public boolean isLoaded(BlockPos pos) {
		return chunk(pos) != null;
	}

	public BlockState state(BlockPos pos) {
		WorldChunk c = chunk(pos);
		return c == null ? Blocks.VOID_AIR.getDefaultState() : c.getBlockState(pos);
	}

	public byte kind(BlockPos pos) {
		return (byte) (raw(pos) & ~LEAF_FLAG);
	}

	/** Leaves: solid to walk on, but nothing a player would make a road of. */
	public boolean isLeaves(BlockPos pos) {
		return (raw(pos) & LEAF_FLAG) != 0;
	}

	private byte raw(BlockPos pos) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		long sk = ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4);
		byte[] sec;
		if (sk == lastSectionKey) {
			sec = lastSection;
		} else {
			sec = kindSections.get(sk);
			if (sec == null) {
				sec = new byte[4096];
				Arrays.fill(sec, (byte) -1);
				kindSections.put(sk, sec);
			}
			lastSectionKey = sk;
			lastSection = sec;
		}
		int i = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
		byte k = sec[i];
		if (k < 0) {
			k = classify(pos);
			sec[i] = k;
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
		BlockState state = state(pos);
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
		if (state.isIn(BlockTags.CLIMBABLE) && b != Blocks.SCAFFOLDING) {
			return CLIMBABLE;
		}
		if ((b instanceof DoorBlock && DoorBlock.canOpenByHand(state)) || b instanceof FenceGateBlock) {
			return DOOR;
		}
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (!shape.isEmpty()) {
			return b instanceof LeavesBlock ? (byte) (SOLID | LEAF_FLAG) : SOLID;
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
			byte k = kind(pos);
			if (k == UNLOADED || k == CLIMBABLE) {
				t = 0;
			} else {
				VoxelShape shape = state(pos).getCollisionShape(world, pos);
				t = shape.isEmpty() ? 0 : shape.getMax(Direction.Axis.Y);
			}
			tops.put(key, t);
		}
		return t;
	}

	/** The body can be in this block (air, water, plants, ladders, open-able doors). */
	public boolean passable(BlockPos pos) {
		byte k = kind(pos);
		if (k != AIR && k != WATER && k != DOOR && k != CLIMBABLE) {
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

	/**
	 * Water pouring down (a waterfall, or water spilling over an edge): it drags whatever is in it
	 * downward, so there's no swimming or climbing up through it.
	 */
	public boolean isFallingWater(BlockPos pos) {
		if (kind(pos) != WATER) {
			return false;
		}
		var f = state(pos).getFluidState();
		return !f.isStill() && f.contains(net.minecraft.fluid.FlowableFluid.FALLING) && f.get(net.minecraft.fluid.FlowableFluid.FALLING);
	}

	/**
	 * Moving water (a stream, the edge of a waterfall): Baritone won't walk or fall through it,
	 * since the current pushes the body off its line.
	 */
	public boolean isFlowingWater(BlockPos pos) {
		return kind(pos) == WATER && !state(pos).getFluidState().isStill();
	}

	/** Falling water here or right beside it (it catches our body as we climb past). */
	public boolean nearFallingWater(BlockPos pos) {
		if (isFallingWater(pos)) {
			return true;
		}
		for (Direction d : Direction.Type.HORIZONTAL) {
			if (isFallingWater(pos.offset(d))) {
				return true;
			}
		}
		return false;
	}

	public boolean isLava(BlockPos pos) {
		return kind(pos) == LAVA;
	}

	public boolean isDoor(BlockPos pos) {
		return kind(pos) == DOOR;
	}

	public boolean isClimbable(BlockPos pos) {
		return kind(pos) == CLIMBABLE;
	}

	/** Sand, gravel, concrete powder...: falls into a hole when its support is mined. */
	public boolean isFalling(BlockPos pos) {
		return kind(pos) == SOLID && state(pos).getBlock() instanceof FallingBlock;
	}

	/** Feet can rest here: body clear and something under it, swimming, or holding a ladder. */
	public boolean standable(BlockPos feet) {
		if (isWater(feet)) {
			return passable(feet.up()) || isWater(feet.up());
		}
		if (isClimbable(feet)) {
			return passable(feet.up());
		}
		return clearBody(feet) && support(feet.down());
	}

	/** Exact feet height when standing in this node. */
	public double floorY(BlockPos feet) {
		if (isWater(feet) || isClimbable(feet) || isClimbable(feet.down())) {
			return feet.getY(); // (above a ladder's top rung we hold on at this height)
		}
		return feet.getY() - 1 + top(feet.down());
	}

	/** Extra cost for nearby dangers (in ticks). */
	public double hazard(BlockPos feet) {
		if (!HAZARD) {
			return 0;
		}
		long key = feet.asLong();
		double cached = hazards.get(key);
		if (!Double.isNaN(cached)) {
			return cached;
		}
		double cost = computeHazard(feet);
		hazards.put(key, cost);
		return cost;
	}

	private double computeHazard(BlockPos feet) {
		double cost = 0;
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos n = feet.offset(d);
			byte k = kind(n);
			byte kb = kind(n.down());
			if (k == LAVA || kb == LAVA) {
				cost += 40;
			} else if (k == DANGER || kb == DANGER) {
				cost += 10;
			}
		}
		if (kind(feet.down()) == LAVA) {
			cost += 100;
		}
		return cost;
	}

	/**
	 * Ticks to clear this block for walking through: 0 if already passable, +inf if it may not
	 * be broken (policy) or is unbreakable. Includes the time for falling blocks above to be
	 * dug out as well.
	 */
	public double breakCost(BlockPos pos) {
		long key = pos.asLong();
		double c = breaks.get(key);
		if (!Double.isNaN(c)) {
			return c;
		}
		byte k = kind(pos);
		if (k == AIR || k == WATER || k == DOOR || k == CLIMBABLE) {
			c = 0;
		} else if (k != SOLID) {
			c = Double.POSITIVE_INFINITY;
		} else {
			BlockState s = state(pos);
			var colony = spareBase ? com.botcraft.colony.Colony.get() : null;
			if (!BreakPolicy.canBreakForNav(world, mutable.set(pos), s, dig)
				|| (colony != null && !BreakPolicy.placedByCompanion(world, pos, s) && colony.protectsGround(world, pos))) {
				c = Double.POSITIVE_INFINITY;
			} else {
				c = miningTicks(s, pos) + BREAK_EXTRA;
				if (c > MAX_BREAK_TICKS) {
					c = Double.POSITIVE_INFINITY;
				} else if (isFalling(pos.up())) {
					c += 40; // gravel above will drop in and need digging again
				}
			}
		}
		breaks.put(key, c);
		return c;
	}

	/** Like vanilla calcBlockBreakingDelta, with the best tool we carry (Efficiency counted). */
	private double miningTicks(BlockState s, BlockPos pos) {
		float hardness = s.getHardness(world, pos);
		if (hardness <= 0) {
			return 1;
		}
		float speed = 1.0F;
		boolean harvest = !s.isToolRequired();
		for (ItemStack t : tools) {
			float toolSpeed = t.getMiningSpeedMultiplier(s);
			if (toolSpeed > 1.0F) {
				// Efficiency, as vanilla's PlayerEntity#getBlockBreakingSpeed (and Baritone's ToolSet) count it.
				int eff = net.minecraft.enchantment.EnchantmentHelper.getLevel(net.minecraft.enchantment.Enchantments.EFFICIENCY, t);
				if (eff > 0) {
					toolSpeed += eff * eff + 1;
				}
			}
			speed = Math.max(speed, toolSpeed);
			harvest |= t.isSuitableFor(s);
		}
		double delta = speed / hardness / (harvest ? 30.0 : 100.0);
		return Math.ceil(1.0 / delta);
	}

	/** Passable now, or breakable (finite cost). */
	public boolean clearable(BlockPos pos) {
		return passable(pos) || (kind(pos) == SOLID && breakCost(pos) < Double.POSITIVE_INFINITY);
	}

	/**
	 * A full-face solid block we can click to place against. Placing needs solid faces, not just
	 * a full collision box: leaves have one but no faces to place on (they used to get bridges
	 * planned off them from tree tops, which could then never be built).
	 */
	public boolean placeableAgainst(BlockPos pos) {
		if (kind(pos) != SOLID) {
			return false;
		}
		BlockState s = state(pos);
		return Block.isShapeFullCube(s.getCollisionShape(world, pos)) && s.isSideSolidFullSquare(world, pos, Direction.UP)
			&& s.isSideSolidFullSquare(world, pos, Direction.NORTH);
	}

	/** Air (or replaceable plants/water) where a block could be placed. */
	public boolean replaceable(BlockPos pos) {
		byte k = kind(pos);
		return (k == AIR || k == WATER) && state(pos).isReplaceable();
	}

	/** Forget cached info (the world changed). */
	public void invalidate(BlockPos pos) {
		hazards.clear();
		for (int dy = -1; dy <= 1; dy++) {
			BlockPos p = pos.up(dy);
			byte[] sec = kindSections.get(ChunkSectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
			if (sec != null) {
				sec[(p.getY() & 15) << 8 | (p.getZ() & 15) << 4 | (p.getX() & 15)] = -1;
			}
			tops.remove(pos.up(dy).asLong());
			breaks.remove(pos.up(dy).asLong());
		}
	}
}

package com.botcraft.nav;

import com.botcraft.config.CompanionConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Which blocks a companion may break while navigating.
 *
 * <p>With griefing off a companion breaks natural terrain (stone, dirt, ores, tree-grown
 * leaves: LeavesBlock.PERSISTENT is false for those and true for placed ones), its own throwaway
 * blocks, and nothing else; the built ledger (colony/Builds) keeps what players put down safe
 * even when it's made of natural blocks. Block entities (chests, furnaces, beds, signs...) are
 * never broken, griefing or not.
 */
public final class BreakPolicy {
	private BreakPolicy() {
	}

	/**
	 * Natural terrain a path may dig through ({@code dig}, which every Navigator search has):
	 * stone types, deepslate, dirt, sand, gravel, ores. Built blocks (cobblestone, bricks, planks,
	 * glass...) are not on the list.
	 */
	public static boolean isNaturalTerrain(BlockState state) {
		return state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER) || state.isIn(BlockTags.DIRT)
			|| state.isIn(BlockTags.SAND) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY) || state.isOf(Blocks.CALCITE)
			|| state.isOf(Blocks.DRIPSTONE_BLOCK) || state.isOf(Blocks.SANDSTONE) || state.isOf(Blocks.RED_SANDSTONE)
			|| state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.COPPER_ORES)
			|| state.isIn(BlockTags.GOLD_ORES) || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
			|| state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES) || state.isOf(Blocks.SNOW_BLOCK)
			|| state.isOf(Blocks.SNOW) || state.isOf(Blocks.POWDER_SNOW);
	}

	public static boolean canBreakForNav(ServerWorld world, BlockPos pos, BlockState state) {
		return canBreakForNav(world, pos, state, false);
	}

	public static boolean canBreakForNav(ServerWorld world, BlockPos pos, BlockState state, boolean dig) {
		if (state.isAir() || state.hasBlockEntity()) {
			return false;
		}
		if (state.getHardness(world, pos) < 0) {
			return false; // bedrock, barriers (how long a block takes is Terrain's business)
		}
		if (touchesFluid(world, pos)) {
			return false; // don't flood ourselves or release lava
		}
		if (com.botcraft.colony.Builds.recorded(world, pos) && !CompanionConfig.get().griefing) {
			return false; // someone built this (see colony/Builds)
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return !state.get(LeavesBlock.PERSISTENT) || CompanionConfig.get().griefing;
		}
		if (placedByCompanion(world, pos, state)) {
			return true; // a bridge, pillar or shelter roof one of us left: ours to take down
		}
		if (dig && isNaturalTerrain(state)) {
			return true;
		}
		if (!CompanionConfig.get().griefing) {
			return false;
		}
		return !state.isIn(BlockTags.DOORS) && !state.isIn(BlockTags.BEDS) && !state.isIn(BlockTags.FENCE_GATES);
	}

	/** A throwaway block (dirt, cobblestone...) that a companion put down here. */
	public static boolean placedByCompanion(ServerWorld world, BlockPos pos, BlockState state) {
		var colony = com.botcraft.colony.Colony.get();
		return colony != null && com.botcraft.util.Experiments.on("placedLedger") && com.botcraft.colony.Upkeep.throwaway(state)
			&& colony.placedByCompanion(world.getRegistryKey().getValue().toString(), pos);
	}

	/** Remember a throwaway block we just put down (see {@link #placedByCompanion}). */
	public static void recordPlaced(ServerWorld world, BlockPos pos) {
		var colony = com.botcraft.colony.Colony.get();
		if (colony != null) {
			colony.recordPlaced(world.getRegistryKey().getValue().toString(), pos.toImmutable());
		}
	}

	private static boolean touchesFluid(ServerWorld world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) {
				continue;
			}
			if (!world.getFluidState(pos.offset(d)).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}

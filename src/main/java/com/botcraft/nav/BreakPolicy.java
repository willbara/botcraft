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
 * <p>Vanilla does not record who placed a block, so "never break player-placed blocks" is
 * enforced conservatively: with griefing off the only blocks broken for navigation are natural
 * leaves (LeavesBlock.PERSISTENT is false for tree-grown leaves and true for placed ones).
 * Block entities (chests, furnaces, beds, signs...) are never broken, griefing or not.
 */
public final class BreakPolicy {
	private BreakPolicy() {
	}

	/**
	 * Natural terrain a companion may dig through while carrying out an explicit mining order
	 * ("dig mode"): stone types, deepslate, dirt, sand, gravel, ores. Even with griefing off this
	 * is allowed, since the player asked for mining; built blocks (cobblestone, bricks, planks,
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
		float hardness = state.getHardness(world, pos);
		if (hardness < 0 || hardness > (dig ? 4.5F : 3.0F)) {
			return false;
		}
		if (touchesFluid(world, pos)) {
			return false; // don't flood ourselves or release lava
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return !state.get(LeavesBlock.PERSISTENT) || CompanionConfig.get().griefing;
		}
		if (dig && isNaturalTerrain(state)) {
			return true;
		}
		if (!CompanionConfig.get().griefing) {
			return false;
		}
		return !state.isIn(BlockTags.DOORS) && !state.isIn(BlockTags.BEDS) && !state.isIn(BlockTags.FENCE_GATES);
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

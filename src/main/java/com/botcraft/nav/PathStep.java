package com.botcraft.nav;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One node of a path. {@code pos} is the block the feet occupy; {@code floorY} is the exact
 * height the feet rest at (e.g. pos.y - 0.5 when standing on a bottom slab below).
 * {@code breaks} are blocks to mine (in order) before the move, {@code place} a block to place
 * (bridge/pillar support).
 */
public record PathStep(BlockPos pos, MoveType type, double floorY, @Nullable BlockPos place, List<BlockPos> breaks) {
	public Vec3d center() {
		return new Vec3d(pos.getX() + 0.5, floorY, pos.getZ() + 0.5);
	}
}

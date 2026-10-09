package com.mcfriends.nav;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * One node of a path. {@code pos} is the block the feet occupy; {@code floorY} is the exact
 * height the feet rest at (e.g. pos.y - 0.5 when standing on a bottom slab below).
 * {@code aux} is the block to place for BRIDGE steps.
 */
public record PathStep(BlockPos pos, MoveType type, double floorY, @Nullable BlockPos aux) {
	public Vec3d center() {
		return new Vec3d(pos.getX() + 0.5, floorY, pos.getZ() + 0.5);
	}
}

package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Torches, like a player: underground, a companion drops one beside it whenever its spot gets
 * dark (so tunnels and caves it works in end up lit every six or seven blocks); at home, dark
 * patches of ground where monsters could spawn get a torch (see LightHomeTask).
 */
public final class Lighting {
	/** Underground, put a torch down when block light falls below this. */
	public static final int CAVE_DARK = 6;
	/** Monsters spawn at block light 0 (1.18+); anything below this counts as dark at home. */
	public static final int HOME_DARK = 1;
	/** Within this of the base, look at its own level (under roofs too); further out, the open ground. */
	private static final int NEAR_RADIUS = 12;
	/** Open ground further than this above or below the base isn't home's to light. */
	private static final int MAX_RISE = 8;

	private long nextTorch;

	/** Called every second or so. Places at most one torch. */
	public void tick(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || now < nextTorch || !CompanionConfig.get().placeTorches) {
			return;
		}
		ServerWorld world = p.getServerWorld();
		BlockPos feet = p.getBlockPos();
		if (com.botcraft.nav.Terrain.openSky(world, feet.up()) || Inventories.count(p.getInventory(), s -> s.isOf(Items.TORCH)) == 0) {
			return; // only underground, and only if we have torches
		}
		if (c.actions().isMining() || !c.perception().threats().isEmpty() || !p.isOnGround()) {
			return; // not in the middle of something
		}
		if (world.getLightLevel(LightType.BLOCK, feet) >= CAVE_DARK) {
			return;
		}
		BlockPos spot = torchSpot(c, world, feet, 2);
		if (spot != null && c.actions().placeBlock(spot, s -> s.isOf(Items.TORCH))) {
			com.botcraft.util.AiLog.log(c.name(), "torch at {}", spot.toShortString());
			nextTorch = now + 40;
		} else {
			nextTorch = now + 20 * 5;
		}
	}

	/** Air with solid ground below, near {@code around}, within reach, not where we stand. */
	@Nullable
	public static BlockPos torchSpot(Companion c, ServerWorld world, BlockPos around, int radius) {
		CompanionPlayer p = c.player();
		List<BlockPos> spots = new ArrayList<>();
		for (BlockPos b : BlockPos.iterate(around.add(-radius, -1, -radius), around.add(radius, 1, radius))) {
			if (b.equals(p.getBlockPos()) || b.equals(p.getBlockPos().up()) || !world.getBlockState(b).isAir()
				|| !world.getFluidState(b).isEmpty() || !world.getBlockState(b.down()).isSideSolidFullSquare(world, b.down(), Direction.UP)) {
				continue;
			}
			if (c.actions().canReachBlock(b)) {
				spots.add(b.toImmutable());
			}
		}
		spots.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(around)));
		return spots.isEmpty() ? null : spots.get(0);
	}

	/** Dark patches of open ground within {@code radius} of the base, nearest first. */
	public static List<BlockPos> darkSpots(ServerWorld world, BlockPos base, int radius) {
		List<BlockPos> out = new ArrayList<>();
		// Close in, about the base's own level (floors under a roof too): not pockets under the
		// floor or in the hillside above.
		int near = Math.min(radius, NEAR_RADIUS);
		for (BlockPos b : BlockPos.iterate(base.add(-near, -1, -near), base.add(near, 2, near))) {
			if (b.getSquaredDistance(base) <= near * near && darkSpot(world, b)) {
				out.add(b.toImmutable());
			}
		}
		// Further out, the open ground wherever it is (home's edges go up and down hills), as long
		// as it's not far above or below the base.
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int d2 = dx * dx + dz * dz;
				if (d2 <= near * near || d2 > radius * radius) {
					continue;
				}
				int x = base.getX() + dx;
				int z = base.getZ() + dz;
				if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z))) {
					continue;
				}
				int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (Math.abs(y - base.getY()) <= MAX_RISE && darkSpot(world, m.set(x, y, z))) {
					out.add(m.toImmutable());
				}
			}
		}
		out.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(base)));
		return out;
	}

	private static boolean darkSpot(ServerWorld world, BlockPos b) {
		return world.getBlockState(b).isAir() && world.getBlockState(b.up()).isAir() && world.getFluidState(b).isEmpty()
			&& world.getBlockState(b.down()).isSideSolidFullSquare(world, b.down(), Direction.UP)
			&& world.getLightLevel(LightType.BLOCK, b) < HOME_DARK;
	}
}

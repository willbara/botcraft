package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

	private long nextTorch;

	/** Called every second or so. Places at most one torch. */
	public void tick(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || now < nextTorch || !CompanionConfig.get().placeTorches) {
			return;
		}
		ServerWorld world = p.getServerWorld();
		BlockPos feet = p.getBlockPos();
		if (world.isSkyVisible(feet.up()) || Inventories.count(p.getInventory(), s -> s.isOf(Items.TORCH)) == 0) {
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
			com.mcfriends.util.AiLog.log(c.name(), "torch at {}", spot.toShortString());
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
		// About the base's own level: not pockets under the floor or in the hillside above.
		for (BlockPos b : BlockPos.iterate(base.add(-radius, -1, -radius), base.add(radius, 2, radius))) {
			if (b.getSquaredDistance(base) > radius * radius || !world.getBlockState(b).isAir() || !world.getBlockState(b.up()).isAir()
				|| !world.getFluidState(b).isEmpty() || !world.getBlockState(b.down()).isSideSolidFullSquare(world, b.down(), Direction.UP)) {
				continue;
			}
			if (world.getLightLevel(LightType.BLOCK, b) < HOME_DARK) {
				out.add(b.toImmutable());
			}
		}
		out.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(base)));
		return out;
	}
}

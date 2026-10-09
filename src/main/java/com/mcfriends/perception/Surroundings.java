package com.mcfriends.perception;

import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.OreKind;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A rolling picture of what's right around the companion (8 blocks each way): ore of every
 * kind, water and lava, and the nearest spot with air to breathe. One horizontal layer is read
 * per tick (17x17 blocks), so the whole cube refreshes about once a second at a tiny, steady
 * cost, and tasks can ask "anything worth having right here?" for free.
 *
 * <p>Results are a few ticks old and positions come from where we were when each layer was
 * read; callers re-check the block before acting on it.
 */
public final class Surroundings {
	public static final int RADIUS = 8;
	private static final int SIZE = RADIUS * 2 + 1;

	private record Layer(Map<OreKind, List<BlockPos>> ores, int water, int lava, List<BlockPos> breathable) {
	}

	private final Layer[] layers = new Layer[SIZE];
	private int nextLayer;
	private BlockPos center = BlockPos.ORIGIN;

	/** Read the next layer. Called every tick. */
	public void tick(CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		if (nextLayer == 0) {
			center = p.getBlockPos();
		}
		int y = center.getY() - RADIUS + nextLayer;
		Map<OreKind, List<BlockPos>> ores = new EnumMap<>(OreKind.class);
		List<BlockPos> breathable = new ArrayList<>();
		int water = 0;
		int lava = 0;
		// Air-to-breathe spots only matter around water: skip those lookups on dry land.
		boolean watery = p.isTouchingWater() || waterBlocks() > 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		if (y > world.getBottomY() && y < world.getTopY()) {
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					m.set(center.getX() + dx, y, center.getZ() + dz);
					BlockState s = world.getBlockState(m);
					if (s.isAir()) {
						// Somewhere to come up for air: air right at a water surface (above it or beside it).
						if (watery && breathable.size() < 48 && (wet(world, m.down()) || wet(world, m.north()) || wet(world, m.south())
							|| wet(world, m.east()) || wet(world, m.west()))) {
							breathable.add(m.toImmutable());
						}
						continue;
					}
					var fluid = s.getFluidState();
					if (!fluid.isEmpty()) {
						if (fluid.isIn(FluidTags.LAVA)) {
							lava++;
						} else {
							water++;
						}
						continue;
					}
					for (OreKind k : OreKind.values()) {
						if (k != OreKind.STONE && k.blocks().test(s)) {
							ores.computeIfAbsent(k, kk -> new ArrayList<>()).add(m.toImmutable());
							break;
						}
					}
				}
			}
		}
		layers[nextLayer] = new Layer(ores, water, lava, breathable);
		nextLayer = (nextLayer + 1) % SIZE;
	}

	private static boolean wet(ServerWorld world, BlockPos pos) {
		return world.getFluidState(pos).isIn(FluidTags.WATER);
	}

	/** Ore of this kind seen around us, nearest to {@code from} first. */
	public List<BlockPos> ores(OreKind kind, BlockPos from) {
		List<BlockPos> out = new ArrayList<>();
		for (Layer l : layers) {
			if (l != null) {
				out.addAll(l.ores.getOrDefault(kind, List.of()));
			}
		}
		out.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(from)));
		return out;
	}

	public int waterBlocks() {
		int n = 0;
		for (Layer l : layers) {
			n += l == null ? 0 : l.water;
		}
		return n;
	}

	public int lavaBlocks() {
		int n = 0;
		for (Layer l : layers) {
			n += l == null ? 0 : l.lava;
		}
		return n;
	}

	/** Nearest air spot (to stand in with our head out of the water), or null if none around. */
	@Nullable
	public BlockPos nearestAir(BlockPos from) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (Layer l : layers) {
			if (l == null) {
				continue;
			}
			for (BlockPos b : l.breathable) {
				double d = b.getSquaredDistance(from) + Math.max(0, from.getY() - b.getY()) * 4.0; // up is easier than down
				if (d < bestD) {
					bestD = d;
					best = b;
				}
			}
		}
		return best;
	}
}

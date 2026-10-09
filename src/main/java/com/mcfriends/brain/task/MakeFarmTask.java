package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Crops;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Make a {@code size x size} farm near a spot: find flat grass/dirt, clear plants off it, till
 * it with a hoe and plant seeds. Needs a hoe (crafts a wooden one if missing) and seeds
 * (collects wheat seeds from grass if missing). Remembers the farm so "harvest the farm" can
 * find it later.
 *
 * <p>Farmland without water nearby still grows crops once planted, just more slowly; the
 * companion mentions it when there's no water within 4 blocks.
 */
public class MakeFarmTask extends Task {
	private enum Phase { PREPARE, SITE, CLEAR, TILL, PLANT }

	private final BlockPos near;
	private final int size;
	private final Item seed;
	private Phase phase = Phase.PREPARE;
	private int phaseTicks;
	private final List<BlockPos> tiles = new ArrayList<>();
	private int index;
	private int planted;
	private boolean noWater;

	public MakeFarmTask(Companion c, BlockPos near, int size, Item seed) {
		super(c);
		this.near = near.toImmutable();
		this.size = Math.max(1, Math.min(size, 9));
		this.seed = seed;
	}

	@Override
	public String name() {
		return "make_farm";
	}

	@Override
	public String describe() {
		return "making a " + size + "x" + size + " " + Crops.cropName(seed) + " farm" + (phase == Phase.PLANT ? " (planting)" : phase == Phase.TILL ? " (tilling)" : "");
	}

	public int planted() {
		return planted;
	}

	public int size() {
		return size;
	}

	public boolean noWater() {
		return noWater;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 6;
	}

	private void setPhase(Phase p) {
		AiLog.log(c.name(), "farm: {} -> {}", phase, p);
		phase = p;
		phaseTicks = 0;
		index = 0;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		phaseTicks++;
		return switch (phase) {
			case PREPARE -> prepare(p);
			case SITE -> site(p);
			case CLEAR -> work(p, Phase.TILL);
			case TILL -> work(p, Phase.PLANT);
			case PLANT -> work(p, null);
		};
	}

	private Status prepare(CompanionPlayer p) {
		List<String> prereqs = new ArrayList<>();
		if (Inventories.count(p.getInventory(), s -> s.getItem() instanceof HoeItem) == 0) {
			prereqs.add("craft wooden_hoe 1");
		}
		int seeds = Inventories.count(p.getInventory(), s -> s.isOf(seed));
		if (seeds == 0) {
			if (seed != Items.WHEAT_SEEDS) {
				return fail("I don't have any " + ItemNames.display(seed) + " to plant");
			}
			prereqs.add("seeds " + Math.min(size * size, 16));
		}
		if (!prereqs.isEmpty()) {
			return deferTo(prereqs);
		}
		setPhase(Phase.SITE);
		return Status.RUNNING;
	}

	private Status site(CompanionPlayer p) {
		BlockPos origin = findSite(p.getServerWorld());
		if (origin == null) {
			return fail("I couldn't find a flat " + size + "x" + size + " patch of dirt or grass here");
		}
		tiles.clear();
		for (int dx = 0; dx < size; dx++) {
			for (int dz = 0; dz < size; dz++) {
				// Snake order so we walk the rows back and forth.
				tiles.add(origin.add(dx, 0, dx % 2 == 0 ? dz : size - 1 - dz));
			}
		}
		noWater = !waterNear(p.getServerWorld(), origin);
		BlockPos centre = origin.add(size / 2, 0, size / 2);
		c.memory().setPlace("farm", centre, c.dimension(), p.getWorld().getTime());
		AiLog.log(c.name(), "farm: site at {} (water nearby: {})", origin.toShortString(), !noWater);
		setPhase(Phase.CLEAR);
		return Status.RUNNING;
	}

	/** The top-left of a flat, tillable, unobstructed square nearest to where we were asked. */
	@Nullable
	private BlockPos findSite(ServerWorld world) {
		for (int r = 0; r <= 10; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = near.getX() + dx;
					int z = near.getZ() + dz;
					int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					BlockPos origin = new BlockPos(x, y, z);
					if (siteOk(world, origin)) {
						return origin;
					}
				}
			}
		}
		return null;
	}

	private boolean siteOk(ServerWorld world, BlockPos origin) {
		for (int dx = 0; dx < size; dx++) {
			for (int dz = 0; dz < size; dz++) {
				BlockPos t = origin.add(dx, 0, dz);
				BlockState s = world.getBlockState(t);
				BlockState above = world.getBlockState(t.up());
				if (!(Crops.tillable(s) || s.isOf(Blocks.FARMLAND)) || !(above.isAir() || Crops.seedGrass(above) || above.isReplaceable())) {
					return false;
				}
				if (!world.getFluidState(t.up()).isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean waterNear(ServerWorld world, BlockPos origin) {
		for (BlockPos b : BlockPos.iterate(origin.add(-4, -1, -4), origin.add(size + 3, 0, size + 3))) {
			if (world.getFluidState(b).isIn(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	/** Clear / till / plant each tile in turn, walking along the rows. */
	private Status work(CompanionPlayer p, @Nullable Phase next) {
		ServerWorld world = p.getServerWorld();
		while (index < tiles.size() && done(world, tiles.get(index))) {
			index++;
			phaseTicks = 0;
		}
		if (index >= tiles.size()) {
			if (next == null) {
				return planted > 0 ? Status.SUCCESS : fail("I ran out of seeds before planting anything");
			}
			setPhase(next);
			return Status.RUNNING;
		}
		BlockPos tile = tiles.get(index);
		BlockPos target = phase == Phase.CLEAR ? tile.up() : tile;
		if (!c.actions().canReachBlock(target)) {
			c.nav().moveTo(Goal.reachVisible(world, target, ActionController.BLOCK_REACH - 1.0), false);
			if (c.nav().state() == Navigator.State.FAILED || phaseTicks > 20 * 20) {
				index++; // skip a tile we can't get to
				phaseTicks = 0;
				c.nav().stop();
			}
			return Status.RUNNING;
		}
		c.nav().stop();
		switch (phase) {
			case CLEAR -> c.actions().mineBlock(tile.up());
			case TILL -> {
				if (!c.actions().useItemOn(tile, Direction.UP, s -> s.getItem() instanceof HoeItem)) {
					index++;
				}
			}
			case PLANT -> {
				if (Crops.tillable(world.getBlockState(tile))) {
					c.actions().useItemOn(tile, Direction.UP, s -> s.getItem() instanceof HoeItem); // trampled: till again
				} else if (Inventories.count(p.getInventory(), s -> s.isOf(seed)) == 0) {
					index = tiles.size(); // out of seeds
				} else if (c.actions().useItemOn(tile, Direction.UP, s -> s.isOf(seed))) {
					planted++;
					c.memory().count("planted", 1);
				} else {
					index++;
				}
			}
			default -> {
			}
		}
		return Status.RUNNING;
	}

	private boolean done(ServerWorld world, BlockPos tile) {
		return switch (phase) {
			case CLEAR -> world.getBlockState(tile.up()).isAir();
			case TILL -> world.getBlockState(tile).isOf(Blocks.FARMLAND) || !Crops.tillable(world.getBlockState(tile));
			case PLANT -> !(world.getBlockState(tile).isOf(Blocks.FARMLAND) || Crops.tillable(world.getBlockState(tile)))
				|| !world.getBlockState(tile.up()).isAir();
			default -> true;
		};
	}
}

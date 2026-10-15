package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.memory.Memory;
import com.botcraft.mixin.FishingBobberAccessor;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Go fishing, like a player: walk to the shore of water we know about (a "water" sighting, see
 * Perception) or the nearest open water around, cast out, wait for a bite (the bobber's hook
 * countdown: the moment the bobber dips) and reel in, until we've caught {@code goal} fish or
 * it's taken long enough. A rod is made first if we have none (three sticks and two string).
 */
public class FishTask extends Task {
	private static final Predicate<ItemStack> ROD = s -> s.isOf(Items.FISHING_ROD);
	private static final Predicate<ItemStack> FISH = s -> s.isOf(Items.COD) || s.isOf(Items.SALMON) || s.isOf(Items.TROPICAL_FISH)
		|| s.isOf(Items.PUFFERFISH);
	/** How far round the sighting (or us) to look for a spot on the shore. */
	private static final int SHORE_SEARCH = 16;
	/** Give a cast this long before reeling in and trying again. */
	private static final int CAST_TICKS = 20 * 45;
	/** A cast that hasn't landed in water after this long missed. */
	private static final int LAND_TICKS = 20 * 4;
	private static final int MAX_MISSES = 4;

	private enum Phase { FIND, WALK, CAST, WAIT, REEL }

	private final int goal;
	private Phase phase = Phase.FIND;
	@Nullable
	private BlockPos stand;
	@Nullable
	private BlockPos water;
	private int phaseTicks;
	private int caught;
	private int misses;
	private int fishBefore;
	private boolean askedForRod;

	public FishTask(Companion c, int goal) {
		super(c);
		this.goal = Math.max(1, goal);
	}

	@Override
	public String name() {
		return "fish";
	}

	@Override
	public String describe() {
		return "fishing (" + caught + "/" + goal + " caught)";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 5;
	}

	@Override
	protected void onStop() {
		c.nav().stop();
		reelIn();
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		ServerWorld w = p.getServerWorld();
		if (Inventories.count(p.getInventory(), ROD) == 0) {
			if (askedForRod) {
				return caught > 0 ? done() : fail("I haven't got a fishing rod");
			}
			askedForRod = true;
			return deferTo(List.of("craft fishing_rod 1"));
		}
		if (caught >= goal) {
			return done();
		}
		phaseTicks++;
		switch (phase) {
			case FIND -> {
				if (!findSpot(w, p)) {
					return caught > 0 ? done() : fail("I can't find anywhere to fish");
				}
				AiLog.log(c.name(), "fish: going to {} to fish at {}", stand.toShortString(), water.toShortString());
				to(Phase.WALK);
			}
			case WALK -> {
				if (p.getBlockPos().getSquaredDistance(stand) <= 2.0) {
					c.nav().stop();
					to(Phase.CAST);
				} else if (c.nav().state() == Navigator.State.FAILED || phaseTicks > 20 * 60) {
					forgetSpot("couldn't get there");
				} else if (c.nav().state() == Navigator.State.IDLE) {
					c.nav().moveTo(Goal.near(stand, 1.0), false);
				}
			}
			case CAST -> {
				// Throw out over the water: aim a little above the far spot so the bobber carries.
				Vec3d aim = Vec3d.ofCenter(water).add(0, 1.2, 0);
				if (p.fishHook != null) {
					to(Phase.WAIT);
				} else if (c.actions().useItemToward(aim, ROD)) {
					fishBefore = Inventories.count(p.getInventory(), FISH);
					to(Phase.WAIT);
				} else if (phaseTicks > 40) {
					forgetSpot("couldn't cast");
				}
			}
			case WAIT -> {
				FishingBobberEntity bobber = p.fishHook;
				if (bobber == null || bobber.isRemoved()) {
					to(Phase.CAST);
					return Status.RUNNING;
				}
				c.actions().lookAt(bobber.getPos());
				if (phaseTicks > LAND_TICKS && !bobber.isTouchingWater()) {
					// Landed on the bank (or caught on something): reel in and throw again.
					reelIn();
					if (++misses >= MAX_MISSES) {
						forgetSpot("my casts keep missing the water");
					} else {
						to(Phase.CAST);
					}
					return Status.RUNNING;
				}
				if (((FishingBobberAccessor) bobber).botcraft$hookCountdown() > 0) {
					to(Phase.REEL); // a bite!
				} else if (phaseTicks > CAST_TICKS) {
					reelIn();
					to(Phase.CAST);
				}
			}
			case REEL -> {
				FishingBobberEntity bobber = p.fishHook;
				if (bobber == null) {
					to(Phase.CAST);
				} else if (c.actions().useItemToward(bobber.getPos(), ROD)) {
					caught++;
					misses = 0;
					c.memory().count("fish_caught", 1);
					to(Phase.CAST);
				} else if (phaseTicks > 20) {
					to(Phase.WAIT); // couldn't turn in time: the fish got away
				}
			}
		}
		return Status.RUNNING;
	}

	private void to(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	/** Reel in an idle line (a missed cast, or giving up). */
	private void reelIn() {
		CompanionPlayer p = c.player();
		if (p != null && p.fishHook != null && Inventories.count(p.getInventory(), ROD) > 0) {
			Inventories.select(p, Inventories.find(p.getInventory(), ROD));
			p.interactionManager.interactItem(p, p.getWorld(), p.getMainHandStack(), net.minecraft.util.Hand.MAIN_HAND);
		}
	}

	private void forgetSpot(String why) {
		AiLog.log(c.name(), "fish: giving up on {}: {}", stand == null ? "?" : stand.toShortString(), why);
		c.memory().forgetSighting("water", water == null ? c.player().getBlockPos() : water);
		c.nav().stop();
		reelIn();
		stand = null;
		water = null;
		misses = 0;
		to(Phase.FIND);
	}

	private Status done() {
		c.nav().stop();
		reelIn();
		int fish = Inventories.count(c.player().getInventory(), FISH);
		AiLog.log(c.name(), "fish: caught {} (now carrying {} fish)", caught, fish);
		return caught > 0 ? Status.SUCCESS : quietSuccess();
	}

	/** A dry spot on the shore with open water in front of it: near a known water body, or near us. */
	private boolean findSpot(ServerWorld w, CompanionPlayer p) {
		List<Memory.Sighting> known = c.memory().sightings("water", p.getBlockPos(), c.dimension(), 96);
		List<BlockPos> centers = new java.util.ArrayList<>();
		for (Memory.Sighting s : known) {
			centers.add(s.pos());
		}
		centers.add(p.getBlockPos());
		for (BlockPos center : centers) {
			if (shore(w, center, p.getBlockPos())) {
				return true;
			}
		}
		return false;
	}

	/** The nearest shore spot round {@code center}: land beside a water source with more water beyond it. */
	private boolean shore(ServerWorld w, BlockPos center, BlockPos from) {
		double bestD = Double.MAX_VALUE;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -SHORE_SEARCH; dx <= SHORE_SEARCH; dx++) {
			for (int dz = -SHORE_SEARCH; dz <= SHORE_SEARCH; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!w.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockPos land = new BlockPos(x, top, z);
				if (!w.getBlockState(land).isSideSolidFullSquare(w, land, Direction.UP) || !w.getBlockState(land.up()).isAir()
					|| !w.getBlockState(land.up(2)).isAir()) {
					continue;
				}
				for (Direction d : Direction.Type.HORIZONTAL) {
					// Water next to the land, at about its height, and more of it two blocks further out.
					BlockPos near = openWater(w, x + d.getOffsetX(), z + d.getOffsetZ(), top);
					BlockPos far = near == null ? null : openWater(w, x + d.getOffsetX() * 3, z + d.getOffsetZ() * 3, top);
					if (far == null) {
						continue;
					}
					double dist = land.getSquaredDistance(from);
					if (dist < bestD) {
						bestD = dist;
						stand = land.up();
						water = far;
					}
				}
			}
		}
		return stand != null;
	}

	/** The water surface in this column, if it's a source with open air over it within a block of {@code level}. */
	@Nullable
	private static BlockPos openWater(ServerWorld w, int x, int z, int level) {
		if (!w.isChunkLoaded(x >> 4, z >> 4)) {
			return null;
		}
		int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		if (Math.abs(top - level) > 1) {
			return null;
		}
		BlockPos b = new BlockPos(x, top, z);
		var f = w.getFluidState(b);
		return f.isIn(FluidTags.WATER) && f.isStill() && w.getBlockState(b.up()).isAir() ? b : null;
	}
}

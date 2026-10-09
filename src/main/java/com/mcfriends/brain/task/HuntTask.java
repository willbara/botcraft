package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Claims;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.Animals;
import com.mcfriends.nav.Goal;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Hunt {@code count} animals for food. Only fair game (see {@link Animals#huntable}): no pets,
 * no named or leashed animals, nothing penned in, and never the last pair of a species nearby.
 * Picks up the drops after each kill.
 *
 * <p>When nothing is in range it picks a direction (away from where others are hunting) and
 * walks that way to look again, a few times, before giving up. Animals near another companion
 * or player are left to them.
 */
public class HuntTask extends Task {
	private static final double RADIUS = 32;
	/** How far each search trip goes, and how many trips before giving up. */
	private static final int TRIP = 40;
	private static final int MAX_TRIPS = 5;

	private final List<EntityType<?>> species;
	private final int count;
	private int kills;
	@Nullable
	private AnimalEntity target;
	@Nullable
	private Vec3d killedAt;
	private int collectTicks;
	private int searchFails;
	private BlockPos goalCenter = BlockPos.ORIGIN;
	@Nullable
	private ItemEntity collecting;
	private final Pickup pickup = new Pickup();
	private int trips;
	@Nullable
	private Direction searchDir;
	@Nullable
	private BlockPos tripGoal;
	private int tripTicks;

	public HuntTask(Companion c, List<EntityType<?>> species, int count) {
		super(c);
		this.species = species;
		this.count = Math.max(1, count);
	}

	@Override
	public String name() {
		return "hunt";
	}

	@Override
	public String describe() {
		return "hunting for food (" + kills + "/" + count + ")";
	}

	public int kills() {
		return kills;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60 * 8;
	}

	@Override
	protected void onStop() {
		Claims.release(c);
		super.onStop();
	}

	@Override
	protected void onStart(boolean resumed) {
		Inventories.equipBestWeapon(c.player());
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (killedAt != null) {
			return collect(p);
		}
		if (kills >= count) {
			return Status.SUCCESS;
		}
		if (target == null || !target.isAlive()) {
			if (target != null && target.isDead()) {
				kills++;
				killedAt = target.getPos();
				collectTicks = 0;
				c.memory().count("hunted_" + target.getType().getUntranslatedName(), 1);
				target = null;
				return Status.RUNNING;
			}
			target = findTarget(p);
			if (target == null) {
				if (tripGoal != null) {
					return travel(p);
				}
				if (++searchFails > 3) {
					if (trips >= MAX_TRIPS) {
						return kills > 0 ? Status.SUCCESS : fail("I walked around but couldn't find any animals to hunt");
					}
					startTrip(p);
				}
				return Status.RUNNING;
			}
			searchFails = 0;
			tripGoal = null;
			Claims.claim(c, target.getBlockPos());
			AiLog.log(c.name(), "hunting a {}", target.getType().getUntranslatedName());
		}
		double d = p.distanceTo(target);
		if (d > RADIUS + 8) {
			target = null;
			return Status.RUNNING;
		}
		c.actions().lookAt(target);
		if (d > 2.6) {
			if (d < 6 && p.canSee(target)) {
				c.nav().stop();
				c.actions().moveTo(target.getPos());
				c.actions().sprint(d > 4);
				if (p.horizontalCollision && p.isOnGround()) {
					c.actions().jump();
				}
			} else if (!c.nav().isBusy() || target.getBlockPos().getSquaredDistance(goalCenter) > 9) {
				goalCenter = target.getBlockPos();
				c.nav().moveTo(Goal.near(goalCenter, 1.5), d > 10);
			}
		} else {
			c.nav().stop();
		}
		c.actions().attack(target);
		return Status.RUNNING;
	}

	private Status collect(CompanionPlayer p) {
		if (collecting == null || !collecting.isAlive()) {
			List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, new Box(BlockPos.ofFloored(killedAt)).expand(5.0, 3.0, 5.0),
				e -> e.isAlive() && !pickup.ignored(e));
			items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
			collecting = items.isEmpty() ? null : items.get(0);
		}
		if (collecting == null || ++collectTicks > 20 * 10) {
			killedAt = null;
			collecting = null;
			return Status.RUNNING;
		}
		if (!pickup.tick(c, collecting)) {
			collecting = null;
		}
		return Status.RUNNING;
	}

	/** Pick a direction to search: keep going the same way, else away from others' work. */
	private void startTrip(CompanionPlayer p) {
		trips++;
		searchFails = 0;
		if (searchDir == null) {
			Direction best = Direction.Type.HORIZONTAL.random(p.getRandom());
			int bestScore = Integer.MAX_VALUE;
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos probe = p.getBlockPos().offset(d, TRIP);
				int score = (Claims.crowded(c, probe, TRIP * 0.75) ? 10 : 0) + p.getRandom().nextInt(3);
				if (score < bestScore) {
					bestScore = score;
					best = d;
				}
			}
			searchDir = best;
		} else if (trips % 2 == 1) {
			// Every other trip, turn so the search sweeps a wider area.
			searchDir = searchDir.rotateYClockwise();
		}
		BlockPos ahead = p.getBlockPos().offset(searchDir, TRIP);
		int y = p.getServerWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
		tripGoal = new BlockPos(ahead.getX(), y, ahead.getZ());
		tripTicks = 0;
		c.nav().moveTo(Goal.near(tripGoal, 4), true);
		AiLog.log(c.name(), "hunt: nothing here, searching {} (trip {}/{})", searchDir, trips, MAX_TRIPS);
	}

	private Status travel(CompanionPlayer p) {
		tripTicks++;
		var state = c.nav().state();
		boolean done = state == com.mcfriends.nav.Navigator.State.ARRIVED || state == com.mcfriends.nav.Navigator.State.FAILED
			|| tripTicks > 20 * 45 || p.getBlockPos().getSquaredDistance(tripGoal) < 25;
		if (done) {
			if (state == com.mcfriends.nav.Navigator.State.FAILED) {
				searchDir = searchDir == null ? null : searchDir.rotateYClockwise();
			}
			tripGoal = null;
			c.nav().stop();
		} else if (!c.nav().isBusy()) {
			c.nav().moveTo(Goal.near(tripGoal, 4), true);
		}
		return Status.RUNNING;
	}

	@Nullable
	private AnimalEntity findTarget(CompanionPlayer p) {
		List<AnimalEntity> around = p.getServerWorld().getEntitiesByClass(AnimalEntity.class, p.getBoundingBox().expand(RADIUS, 12, RADIUS),
			a -> species.isEmpty() ? Animals.FOOD_ANIMALS.contains(a.getType()) : species.contains(a.getType()));
		around.sort(Comparator.comparingDouble(a -> a.squaredDistanceTo(p)));
		AnimalEntity shared = null;
		for (AnimalEntity a : around) {
			if (Animals.huntable(a, around)) {
				// Leave animals by someone else to them, unless that's all there is.
				if (!Claims.crowded(c, a.getBlockPos(), 6)) {
					return a;
				}
				if (shared == null) {
					shared = a;
				}
			}
		}
		return shared;
	}
}

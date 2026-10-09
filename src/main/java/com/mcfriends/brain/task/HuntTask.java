package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
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
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Hunt {@code count} animals for food. Only fair game (see {@link Animals#huntable}): no pets,
 * no named or leashed animals, nothing penned in, and never the last pair of a species nearby.
 * Picks up the drops after each kill.
 */
public class HuntTask extends Task {
	private static final double RADIUS = 32;

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
		return 20 * 60 * 5;
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
				if (++searchFails > 3) {
					return kills > 0 ? Status.SUCCESS : fail("there are no animals I can hunt around here");
				}
				return Status.RUNNING;
			}
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

	@Nullable
	private AnimalEntity findTarget(CompanionPlayer p) {
		List<AnimalEntity> around = p.getServerWorld().getEntitiesByClass(AnimalEntity.class, p.getBoundingBox().expand(RADIUS, 12, RADIUS),
			a -> species.isEmpty() ? Animals.FOOD_ANIMALS.contains(a.getType()) : species.contains(a.getType()));
		around.sort(Comparator.comparingDouble(a -> a.squaredDistanceTo(p)));
		for (AnimalEntity a : around) {
			if (Animals.huntable(a, around)) {
				return a;
			}
		}
		return null;
	}
}

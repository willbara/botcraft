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
import net.minecraft.util.math.Vec3d;
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
	/** How far off we can make out animals (about a normal render distance). */
	private static final double FAR_RADIUS = 96;
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
	private final SearchTrips search = new SearchTrips("hunt", TRIP, MAX_TRIPS);

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
		search.cancel(c);
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
		if (kills == 0 && target == null && Inventories.bestWeaponDamage(p.getInventory()) < 2.0) {
			// Bare hands: a wooden sword is two planks and a stick, and far quicker than punching.
			return deferTo(java.util.List.of("craft wooden_sword 1"));
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
				if (search.tick(c)) {
					return Status.RUNNING;
				}
				if (++searchFails > 3) {
					if (search.exhausted()) {
						return kills > 0 ? Status.SUCCESS : fail("I walked around but couldn't find any animals to hunt");
					}
					searchFails = 0;
					// Look further first (a herd out in the field we can see from here), then places
					// we've seen animals before, and only then wander off to look.
					BlockPos herd = findHerd(p);
					if (herd != null) {
						AiLog.log(c.name(), "hunt: spotted animals over at {}", herd.toShortString());
					} else {
						for (var seen : c.memory().sightings("herd", p.getBlockPos(), c.dimension(), 160)) {
							if (seen.pos().getSquaredDistance(p.getBlockPos()) > RADIUS * RADIUS && !triedHerds.contains(seen.pos())) {
								herd = seen.pos();
								AiLog.log(c.name(), "hunt: there were animals over at {} before", herd.toShortString());
								break;
							}
						}
					}
					if (herd != null) {
						triedHerds.add(herd);
					}
					search.start(c, herd);
				}
				return Status.RUNNING;
			}
			searchFails = 0;
			search.cancel(c);
			Claims.claim(c, target.getBlockPos());
			c.memory().sight("herd", target.getBlockPos(), c.dimension(), p.getWorld().getTime());
			Inventories.equipBestWeapon(p);
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
			if (ticks() % 20 == 0) {
				Inventories.equipBestWeapon(p); // picking things up can shuffle the hotbar
			}
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

	/** Herds we already went to look at during this hunt. */
	private final java.util.Set<BlockPos> triedHerds = new java.util.HashSet<>();

	/**
	 * Animals further off, as far as we could see them (about render distance): the nearest
	 * one that's fair game, judged against its own herd rather than what's near us.
	 */
	@Nullable
	private BlockPos findHerd(CompanionPlayer p) {
		List<AnimalEntity> far = p.getServerWorld().getEntitiesByClass(AnimalEntity.class, p.getBoundingBox().expand(FAR_RADIUS, 48, FAR_RADIUS),
			a -> species.isEmpty() ? Animals.FOOD_ANIMALS.contains(a.getType()) : species.contains(a.getType()));
		far.sort(Comparator.comparingDouble(a -> a.squaredDistanceTo(p)));
		var border = p.getWorld().getWorldBorder();
		for (AnimalEntity a : far) {
			if (!border.contains(a.getBlockPos()) || triedHerds.stream().anyMatch(t -> t.getSquaredDistance(a.getBlockPos()) < 16 * 16)) {
				continue;
			}
			List<AnimalEntity> herd = far.stream().filter(o -> o.squaredDistanceTo(a) < 16 * 16).toList();
			if (Animals.huntable(a, herd)) {
				c.memory().sight("herd", a.getBlockPos(), c.dimension(), p.getWorld().getTime());
				return a.getBlockPos();
			}
		}
		return null;
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

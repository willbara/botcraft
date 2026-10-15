package com.botcraft.perception;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cached world queries for one companion. Results are recomputed at most every few ticks so
 * drives and tasks can ask freely.
 */
public class Perception {
	public static final double THREAT_RADIUS = 16.0;
	/** Someone with a price on their head is spotted (and gone after) from this far. */
	public static final double WANTED_RADIUS = 32.0;
	private static final int THREAT_TTL = 3;
	private static final int INVENTORY_TTL = 20;

	private final Companion companion;
	private final BlockScanner scanner;
	private final Surroundings surroundings = new Surroundings();

	private long now;
	private long threatsAt = -1000;
	private List<MobEntity> threats = List.of();
	private long enemiesAt = -1000;
	private List<ServerPlayerEntity> enemies = List.of();
	private long inventoryAt = -1000;
	private InventorySummary inventory = InventorySummary.EMPTY;

	public Perception(Companion companion) {
		this.companion = companion;
		this.scanner = new BlockScanner(companion);
	}

	public void tick(long serverTick) {
		this.now = serverTick;
		if (companion.player() != null) {
			surroundings.tick(companion.player());
			if ((serverTick + Math.floorMod(companion.name().hashCode(), 100)) % 100 == 0) {
				watchHerds();
			}
			// Keep an eye out between decisions: something new coming at us (or a friend) gets a
			// decision at once, not at the next scheduled think.
			if ((serverTick + Math.floorMod(companion.name().hashCode(), THREAT_TTL)) % THREAT_TTL == 0) {
				boolean had = !threats.isEmpty() || !enemies.isEmpty();
				threats();
				enemyPlayers();
				if (!had && (!threats.isEmpty() || !enemies.isEmpty())) {
					companion.brain().alert();
				}
			}
			if ((serverTick + Math.floorMod(companion.name().hashCode(), 200)) % 200 == 100) {
				watchWater();
			}
		}
	}

	private static final int MAX_HERDS = 12;
	/** Water this big (columns of the sparse look below) counts as a body of water worth fishing. */
	private static final int WATER_BODY = 5;

	/**
	 * Notice open water (a lake, a river, the sea) while about: a sparse look at the surface round
	 * us, and if enough of it is water, the nearest bit is remembered as a "water" sighting (for
	 * fishing, see FishTask).
	 */
	private void watchWater() {
		var p = companion.player();
		var world = p.getServerWorld();
		BlockPos at = p.getBlockPos();
		BlockPos nearest = null;
		double bestD = Double.MAX_VALUE;
		int count = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -24; dx <= 24; dx += 4) {
			for (int dz = -24; dz <= 24; dz += 4) {
				int x = at.getX() + dx;
				int z = at.getZ() + dz;
				if (!world.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				var f = world.getFluidState(m.set(x, top, z));
				if (f.isIn(net.minecraft.registry.tag.FluidTags.WATER) && f.isStill() && Math.abs(top - at.getY()) < 12) {
					count++;
					double d = m.getSquaredDistance(at);
					if (d < bestD) {
						bestD = d;
						nearest = m.toImmutable();
					}
				}
			}
		}
		if (count >= WATER_BODY && nearest != null) {
			companion.memory().sight("water", nearest, companion.dimension(), now);
		}
	}

	/**
	 * Notice animals while doing other things, like a player does: groups of three or more food
	 * animals within 64 blocks are remembered (one spot per area), and remembered spots in view
	 * with nothing there any more are forgotten. Hunting goes straight to these.
	 */
	private void watchHerds() {
		var p = companion.player();
		var world = p.getServerWorld();
		var memory = companion.memory();
		String dim = companion.dimension();
		List<net.minecraft.entity.passive.AnimalEntity> animals = world.getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class,
			p.getBoundingBox().expand(64, 16, 64), a -> com.botcraft.knowledge.Animals.FOOD_ANIMALS.contains(a.getType())
				&& !a.isBaby() && !a.hasCustomName() && a.isAlive());
		java.util.Map<net.minecraft.entity.EntityType<?>, List<net.minecraft.entity.passive.AnimalEntity>> byKind = new java.util.HashMap<>();
		for (var a : animals) {
			byKind.computeIfAbsent(a.getType(), k -> new java.util.ArrayList<>()).add(a);
		}
		long time = world.getTime();
		for (var group : byKind.values()) {
			if (group.size() < 3) {
				continue;
			}
			// The middle of the densest bunch, not whichever stray we happened to list first.
			net.minecraft.entity.passive.AnimalEntity center = null;
			int bestNear = 0;
			for (var a : group) {
				int near = (int) group.stream().filter(o -> o.squaredDistanceTo(a) < 12 * 12).count();
				if (near > bestNear) {
					bestNear = near;
					center = a;
				}
			}
			if (center == null || bestNear < 3) {
				continue;
			}
			net.minecraft.util.math.BlockPos at = center.getBlockPos();
			var known = memory.sightings("herd", at, dim, 24);
			if (!known.isEmpty()) {
				memory.sight("herd", known.get(0).pos(), dim, time); // still there
			} else if (memory.sightings("herd", p.getBlockPos(), dim, 10_000).size() < MAX_HERDS) {
				memory.sight("herd", at, dim, time);
			}
		}
		// Remembered herds we can see the spot of, with no animals near it now: gone.
		for (var seen : memory.sightings("herd", p.getBlockPos(), dim, 32)) {
			boolean any = animals.stream().anyMatch(a -> a.getBlockPos().getSquaredDistance(seen.pos()) < 24 * 24);
			if (!any) {
				memory.forgetSighting("herd", seen.pos());
			}
		}
	}

	/** What's right around us (ore, water, lava, air), kept up to date a layer per tick. */
	public Surroundings surroundings() {
		return surroundings;
	}

	public BlockScanner blocks() {
		return scanner;
	}

	@Nullable
	public ServerPlayerEntity owner() {
		ServerPlayerEntity o = companion.owner();
		return o != null && o.isAlive() && !o.isSpectator() ? o : null;
	}

	/** Distance to owner, or +inf if the owner is offline or in another dimension. */
	public double ownerDistance() {
		ServerPlayerEntity o = owner();
		CompanionPlayer p = companion.player();
		if (o == null || p == null || o.getWorld() != p.getWorld()) {
			return Double.POSITIVE_INFINITY;
		}
		return o.distanceTo(p);
	}

	// ---- threats ------------------------------------------------------------------------------

	/**
	 * Players within 16 blocks we should fight (enemy factions' companions, players on bad terms
	 * with our faction, whoever just hit us), nearest first. See faction.Hostility.
	 */
	public List<ServerPlayerEntity> enemyPlayers() {
		if (now - enemiesAt >= THREAT_TTL) {
			enemiesAt = now;
			enemies = computeEnemies();
		}
		return enemies;
	}

	private List<ServerPlayerEntity> computeEnemies() {
		CompanionPlayer p = companion.player();
		if (p == null) {
			return List.of();
		}
		List<ServerPlayerEntity> list = new ArrayList<>();
		java.util.Map<ServerPlayerEntity, Double> bounty = new java.util.HashMap<>();
		var faction = com.botcraft.faction.Factions.of(companion);
		for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
			double d2 = o.squaredDistanceTo(p);
			if (o == p || d2 >= WANTED_RADIUS * WANTED_RADIUS) {
				continue;
			}
			// Someone with a price on their head is worth going further for, and comes first.
			double b = com.botcraft.faction.Bounties.value(faction, o.getGameProfile().getName(), now);
			boolean wanted = b >= com.botcraft.faction.Bounties.WANTED && p.canSee(o);
			if ((d2 < THREAT_RADIUS * THREAT_RADIUS || wanted) && com.botcraft.faction.Hostility.isEnemy(companion, o)) {
				list.add(o);
				bounty.put(o, b);
			}
		}
		list.sort(Comparator.comparingDouble(o -> o.squaredDistanceTo(p) / (1.0 + bounty.getOrDefault(o, 0.0))));
		return list;
	}

	/** Something just happened (we were hit, a friend was): look again now instead of trusting the last look. */
	public void alert() {
		threatsAt = -1000;
		enemiesAt = -1000;
	}

	/** Hostile mobs within 16 blocks that are (or would be) hostile to us or our owner, nearest first. */
	public List<MobEntity> threats() {
		if (now - threatsAt >= THREAT_TTL) {
			threatsAt = now;
			threats = computeThreats();
		}
		return threats;
	}

	@Nullable
	public MobEntity nearestThreat() {
		List<MobEntity> t = threats();
		return t.isEmpty() ? null : t.get(0);
	}

	/** Hostile mobs near the owner that are targeting the owner (for "defend me"). */
	public List<MobEntity> ownerThreats() {
		ServerPlayerEntity o = owner();
		CompanionPlayer p = companion.player();
		if (o == null || p == null || o.getWorld() != p.getWorld()) {
			return List.of();
		}
		List<MobEntity> list = new ArrayList<>(o.getServerWorld().getEntitiesByClass(MobEntity.class, o.getBoundingBox().expand(12.0, 5.0, 12.0),
			m -> m.isAlive() && m instanceof Monster && (m.getTarget() == o || m.squaredDistanceTo(o) < 5 * 5)));
		list.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(p)));
		return list;
	}

	@Nullable
	public CreeperEntity nearestCreeper(double radius) {
		CompanionPlayer p = companion.player();
		for (MobEntity m : threats()) {
			if (m instanceof CreeperEntity c && m.distanceTo(p) <= radius) {
				return c;
			}
		}
		return null;
	}

	private List<MobEntity> computeThreats() {
		CompanionPlayer p = companion.player();
		if (p == null) {
			return List.of();
		}
		ServerPlayerEntity owner = owner();
		Box box = p.getBoundingBox().expand(THREAT_RADIUS, 6.0, THREAT_RADIUS);
		// Herobrine and the monsters leave each other be (see mixin.MobTargetMixin): only one that's
		// somehow after him counts.
		boolean herobrine = com.botcraft.brain.EasterEggs.is(companion, com.botcraft.brain.EasterEggs.Kind.HEROBRINE);
		List<MobEntity> list = new ArrayList<>(p.getServerWorld().getEntitiesByClass(MobEntity.class, box,
			m -> m.isAlive() && m instanceof Monster && (herobrine ? m.getTarget() == p : isHostileTo(m, p, owner))));
		list.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(p)));
		return list;
	}

	private static boolean isHostileTo(MobEntity m, CompanionPlayer p, @Nullable ServerPlayerEntity owner) {
		LivingEntity target = m.getTarget();
		// Mobs after us, our owner, or a fellow companion nearby (we look out for each other).
		boolean targetingUs = target == p || (owner != null && target == owner)
			|| (target instanceof CompanionPlayer friend && friend.squaredDistanceTo(p) < 16 * 16);
		// Under water (drowned, guardians): fighting them means swimming into deep water after
		// them. Only hit back when one is right on us; otherwise keep away from the water.
		if (m.isSubmergedInWater()) {
			return m.squaredDistanceTo(p) < 2.5 * 2.5;
		}
		// Neutral mobs only count when they're already after us.
		if (m instanceof EndermanEntity || m instanceof ZombifiedPiglinEntity || m instanceof PiglinEntity) {
			return targetingUs;
		}
		if (m instanceof SpiderEntity && target == null && p.getWorld().isDay()) {
			return false;
		}
		if (!(m instanceof HostileEntity) && !targetingUs) {
			// e.g. slimes, phantoms, ghasts: only when engaged or close
			return m.squaredDistanceTo(p) < 8 * 8;
		}
		return targetingUs || m.canSee(p) || m.squaredDistanceTo(p) < 6 * 6;
	}

	// ---- environment --------------------------------------------------------------------------

	public long timeOfDay() {
		CompanionPlayer p = companion.player();
		return p == null ? 0 : p.getWorld().getTimeOfDay() % 24000L;
	}

	/** Night as players see it: mobs can spawn on the surface (dusk 13000 to dawn 23000). */
	public boolean isNight() {
		long t = timeOfDay();
		return t >= 13000 && t < 23000;
	}

	public int lightLevel() {
		CompanionPlayer p = companion.player();
		if (p == null) {
			return 15;
		}
		BlockPos pos = p.getBlockPos();
		return p.getWorld().getLightLevel(pos);
	}

	// ---- inventory ----------------------------------------------------------------------------

	public InventorySummary inventory() {
		if (now - inventoryAt >= INVENTORY_TTL) {
			inventoryAt = now;
			CompanionPlayer p = companion.player();
			inventory = p == null ? InventorySummary.EMPTY : InventorySummary.of(p.getInventory());
		}
		return inventory;
	}

	public void invalidateInventory() {
		inventoryAt = -1000;
	}

	public record InventorySummary(int food, int logs, int throwaway, double bestWeapon, int freeSlots) {
		static final InventorySummary EMPTY = new InventorySummary(0, 0, 0, 1.0, 36);

		static InventorySummary of(PlayerInventory inv) {
			int free = 0;
			for (int i = 0; i < inv.main.size(); i++) {
				if (inv.getStack(i).isEmpty()) {
					free++;
				}
			}
			return new InventorySummary(Inventories.foodCount(inv), Inventories.count(inv, Inventories::isLog),
				Inventories.count(inv, Inventories::isThrowaway), Inventories.bestWeaponDamage(inv), free);
		}
	}
}

package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.util.AiLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Random;

/**
 * Easter egg: a companion named "popbob" is a menace, but a patient one. It belongs to no faction
 * and takes no orders. It's an ordinary free companion underneath (see {@link Persona}): it gears
 * up like anyone (tools, a proper weapon, armour), eats, and fights with everything ordinary
 * companions know. Once armed it goes looking for trouble, turn and turn about:
 * <ul>
 *   <li><b>raids</b> a faction's base, helping itself to what's in the chests (the "steal" job);</li>
 *   <li><b>bothers</b> someone: follows them about, takes a swipe now and then, taunts them;</li>
 *   <li>and on the way <b>wrecks</b> built blocks in reach (never containers or anything unbreakable).</li>
 * </ul>
 * Hit it once it's armed and it fights back (faction.Hostility: whoever just hit it is its enemy);
 * before that it backs off like anyone. Companions only gang up on it once it's armed and
 * dangerous. Turn it off with {@code easterEggs: false}.
 */
public final class Popbob implements Persona {
	private static final double BOTHER_RANGE = 48;
	private static final double GRIEF_REACH = ActionController.BLOCK_REACH - 0.3;
	/** How far it'll go to rob a base. */
	private static final double RAID_RANGE = 320;

	private final Random random = new Random();
	@Nullable
	private PlayerEntity target;
	@Nullable
	private BlockPos wrecking;
	private int wreckTicks;
	private BlockPos chasing = BlockPos.ORIGIN;
	private long nextTaunt;
	private long nextMischief;
	private long botherUntil;
	private long nextSwipe;
	private final java.util.Map<String, Long> robbed = new java.util.HashMap<>();

	public static boolean is(Companion c) {
		return EasterEggs.is(c, EasterEggs.Kind.POPBOB);
	}

	/** Armed and armoured enough to start trouble: an iron sword, or a stone one and some armour. */
	public static boolean geared(PlayerEntity p) {
		double weapon = Inventories.bestWeaponDamage(p.getInventory());
		return weapon >= 6.0 || (weapon >= 5.0 && p.getArmor() >= 6);
	}

	/**
	 * Gearing up: free mode's own plans, like anyone. Armed: every so often, some mischief (off to
	 * rob a base, or someone to pester); while pestering, its own plans (see {@link #act}).
	 */
	@Override
	public boolean ownPlans(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !p.isAlive() || !geared(p)) {
			return false;
		}
		if (now >= nextMischief && !urgent(c)) {
			nextMischief = now + 20 * (30 + random.nextInt(60));
			mischief(c, p, now);
		}
		return bothering(p, now);
	}

	/** Pestering someone, or (with nothing else on) wrecking what's in reach. */
	@Override
	public void act(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !geared(p)) {
			return;
		}
		if (now % 40 == 0) {
			Inventories.equipBestWeapon(p);
		}
		if (bothering(p, now)) {
			bother(c, p, now);
		} else {
			griefNearby(c, p);
		}
	}

	/** A fight, a flight or a meal on: no mischief now. */
	private static boolean urgent(Companion c) {
		Objective o = c.brain().currentObjective();
		return o == Objective.FIGHT || o == Objective.FLEE || o == Objective.RETREAT || o == Objective.EAT || o == Objective.BREATHE;
	}

	private boolean bothering(CompanionPlayer p, long now) {
		return target != null && now < botherUntil && target.isAlive() && !target.isSpectator() && target.getWorld() == p.getWorld()
			&& target.distanceTo(p) <= BOTHER_RANGE;
	}

	/** Rob a base, or pick someone to pester (whichever there is, half and half). */
	private void mischief(Companion c, CompanionPlayer p, long now) {
		if (random.nextBoolean() && raid(c, p, now)) {
			return;
		}
		target = EasterEggs.nearestOther(c, BOTHER_RANGE, EasterEggs.Kind.POPBOB);
		if (target != null) {
			botherUntil = now + 20 * (25 + random.nextInt(30));
			c.jobs().clear(); // drops whatever free mode had it doing
			AiLog.log(c.name(), "popbob: going to bother {}", target.getGameProfile().getName());
			return;
		}
		raid(c, p, now);
	}

	/** Off to rob the nearest faction base it hasn't robbed lately (an ordinary "steal" job). True if it set off. */
	private boolean raid(Companion c, CompanionPlayer p, long now) {
		com.botcraft.colony.Colony best = null;
		double bestD = RAID_RANGE * RAID_RANGE;
		for (var colony : com.botcraft.colony.Colony.all()) {
			if (!colony.hasBase() || colony.world() != p.getServerWorld() || colony.chests().isEmpty()
				|| now - robbed.getOrDefault(colony.id(), -1_000_000L) < 20 * 60 * 10) {
				continue;
			}
			double d = colony.base().getSquaredDistance(p.getBlockPos());
			if (d < bestD) {
				bestD = d;
				best = colony;
			}
		}
		if (best == null) {
			return false;
		}
		robbed.put(best.id(), now);
		target = null;
		c.jobs().replace(java.util.List.of("steal " + best.id()));
		var f = com.botcraft.faction.Factions.get(best.id());
		taunt(c, "popbob.raid", f == null ? best.id() : f.name, now);
		AiLog.log(c.name(), "popbob: off to rob {}", best.id());
		return true;
	}

	/** Tag along with someone, take a swipe now and then, and taunt them; wreck things on the way. */
	private void bother(Companion c, CompanionPlayer p, long now) {
		double d = target.distanceTo(p);
		c.actions().lookAt(target);
		if (d > 2.6) {
			if (!c.nav().isBusy() || target.getBlockPos().getSquaredDistance(chasing) > 9) {
				chasing = target.getBlockPos();
				c.nav().moveTo(Goal.near(chasing, 2), d > 8);
			}
			griefNearby(c, p);
			return;
		}
		c.nav().stop();
		if (now >= nextSwipe && c.actions().attack(target)) {
			nextSwipe = now + 20 * (3 + random.nextInt(4));
			if (now >= nextTaunt) {
				taunt(c, "popbob.bother", target.getGameProfile().getName(), now);
			}
		}
	}

	/** Break a built block in reach, one at a time. @return true while wrecking something */
	private boolean griefNearby(Companion c, CompanionPlayer p) {
		ServerWorld world = p.getServerWorld();
		if (wrecking != null && (world.getBlockState(wrecking).isAir() || ++wreckTicks > 20 * 10 || !c.actions().canReachBlock(wrecking))) {
			if (world.getBlockState(wrecking).isAir()) {
				c.memory().count("griefed", 1);
				if (random.nextInt(6) == 0) {
					taunt(c, "popbob.grief", "", c.now());
				}
			}
			wrecking = null;
		}
		if (wrecking == null) {
			BlockPos eye = BlockPos.ofFloored(p.getEyePos());
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			for (BlockPos b : BlockPos.iterate(eye.add(-4, -3, -4), eye.add(4, 3, 4))) {
				if (!griefable(world, b)) {
					continue;
				}
				double d = b.getSquaredDistance(eye);
				if (d < bestD && d <= GRIEF_REACH * GRIEF_REACH && c.actions().canReachBlock(b)) {
					bestD = d;
					best = b.toImmutable();
				}
			}
			if (best == null) {
				return false;
			}
			wrecking = best;
			wreckTicks = 0;
			AiLog.log(c.name(), "popbob: wrecking {} at {}", world.getBlockState(best).getBlock(), best.toShortString());
		}
		c.actions().mineBlock(wrecking);
		return true;
	}

	/** Built things worth wrecking; never containers or anything unbreakable. */
	static boolean griefable(ServerWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (s.isAir() || s.hasBlockEntity() || !s.getFluidState().isEmpty() || s.getHardness(world, pos) < 0) {
			return false;
		}
		return s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.WOOL) || s.isIn(BlockTags.STAIRS) || s.isIn(BlockTags.SLABS)
			|| s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.FENCE_GATES) || s.isIn(BlockTags.DOORS) || s.isIn(BlockTags.TRAPDOORS)
			|| s.isIn(BlockTags.WALLS) || s.isIn(BlockTags.WOOL_CARPETS) || s.isIn(BlockTags.IMPERMEABLE) // glass
			|| s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH) || s.isOf(Blocks.LANTERN) || s.isOf(Blocks.CRAFTING_TABLE)
			|| s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.STONE_BRICKS) || s.isOf(Blocks.BRICKS) || s.isOf(Blocks.GLASS_PANE)
			|| s.isOf(Blocks.LADDER) || s.isOf(Blocks.HAY_BLOCK) || s.isOf(Blocks.BOOKSHELF) || s.isOf(Blocks.TERRACOTTA)
			|| s.isIn(BlockTags.TERRACOTTA) || s.isOf(Blocks.SMOOTH_STONE) || s.isOf(Blocks.POLISHED_ANDESITE);
	}

	private void taunt(Companion c, String key, String who, long now) {
		nextTaunt = now + 20 * 12;
		c.speech().event(key, Map.of("player", who.isEmpty() ? "you" : who, "faction", who.isEmpty() ? "you" : who), 20 * 5, 1.0F);
	}

	/** An armed popbob within {@code range} of this companion, for the others to fight back. */
	@Nullable
	public static ServerPlayerEntity near(Companion c, double range) {
		return near(c, range, true);
	}

	/** A popbob within {@code range} of this companion (only an armed one if {@code armedOnly}). */
	@Nullable
	public static ServerPlayerEntity near(Companion c, double range, boolean armedOnly) {
		var m = com.botcraft.CompanionManager.get();
		if (m == null || !CompanionConfig.get().easterEggs || c.player() == null) {
			return null;
		}
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (o != c && is(o) && op != null && op.isAlive() && (!armedOnly || geared(op)) && op.getWorld() == c.player().getWorld() && op.distanceTo(c.player()) <= range) {
				return op;
			}
		}
		return null;
	}
}

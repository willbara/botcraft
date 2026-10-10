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

import java.util.Comparator;
import java.util.Map;
import java.util.Random;

/**
 * Easter egg: a companion named "popbob" is a menace. It ignores orders, chases the nearest
 * player or companion and hits them with the best weapon it has, and in between it wrecks
 * whatever built blocks are in reach (planks, glass, torches, doors, bricks...). It leaves
 * containers alone (chests, furnaces, beds and anything else holding stuff), so nobody loses
 * their things, and never breaks unbreakable blocks. Turn it off with {@code easterEggs: false}.
 */
public final class Popbob {
	private static final double CHASE_RANGE = 48;
	private static final double GRIEF_REACH = ActionController.BLOCK_REACH - 0.3;

	private final Random random = new Random();
	@Nullable
	private PlayerEntity target;
	@Nullable
	private BlockPos wrecking;
	private int wreckTicks;
	private BlockPos chasing = BlockPos.ORIGIN;
	private long nextTaunt;
	private long nextRoam;

	public static boolean is(Companion c) {
		return CompanionConfig.get().easterEggs && c.name().equalsIgnoreCase("popbob");
	}

	/** Runs instead of the normal brain while the companion is popbob. */
	public void tick(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !p.isAlive()) {
			return;
		}
		if (now % 40 == 0) {
			Inventories.equipBestWeapon(p);
		}
		if (target == null || !target.isAlive() || target.isRemoved() || target.getWorld() != p.getWorld()
			|| target.distanceTo(p) > CHASE_RANGE || target.isSpectator() || now % 100 == 0) {
			target = pickTarget(c);
		}
		// Hitting someone beats breaking things.
		if (target != null) {
			double d = target.distanceTo(p);
			c.actions().lookAt(target);
			if (d > ActionController.ATTACK_REACH - 0.4) {
				if (d < 6 && p.canSee(target)) {
					c.nav().stop();
					c.actions().moveTo(target.getPos());
					c.actions().sprint(true);
					if (p.horizontalCollision && p.isOnGround()) {
						c.actions().jump();
					}
				} else if (!c.nav().isBusy() || target.getBlockPos().getSquaredDistance(chasing) > 9) {
					chasing = target.getBlockPos();
					c.nav().moveTo(Goal.near(chasing, 1.5), true);
				}
				griefNearby(c, p);
			} else {
				c.nav().stop();
				if (c.actions().attack(target) && now >= nextTaunt) {
					taunt(c, "popbob.attack", target.getGameProfile().getName(), now);
				}
			}
			return;
		}
		// Nobody around: wreck stuff, and wander looking for trouble.
		if (!griefNearby(c, p) && !c.nav().isBusy() && now >= nextRoam) {
			nextRoam = now + 20 * 8;
			BlockPos to = p.getBlockPos().add(random.nextInt(25) - 12, 0, random.nextInt(25) - 12);
			c.nav().moveTo(Goal.near(to, 2), false);
		}
	}

	/** The nearest player or companion (not ourselves, not spectators). */
	@Nullable
	private PlayerEntity pickTarget(Companion c) {
		CompanionPlayer p = c.player();
		return p.getServerWorld().getPlayers().stream()
			.filter(o -> o != p && o.isAlive() && !o.isSpectator() && o.distanceTo(p) <= CHASE_RANGE)
			.min(Comparator.comparingDouble(o -> o.squaredDistanceTo(p)))
			.map(o -> (PlayerEntity) o).orElse(null);
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
		c.speech().event(key, Map.of("player", who.isEmpty() ? "you" : who), 20 * 5, 1.0F);
	}

	/** A popbob within {@code range} of this companion, for the others to fight back. */
	@Nullable
	public static ServerPlayerEntity near(Companion c, double range) {
		var m = com.botcraft.CompanionManager.get();
		if (m == null || !CompanionConfig.get().easterEggs) {
			return null;
		}
		for (Companion o : m.all()) {
			CompanionPlayer op = o.player();
			if (o != c && is(o) && op != null && op.isAlive() && op.getWorld() == c.player().getWorld() && op.distanceTo(c.player()) <= range) {
				return op;
			}
		}
		return null;
	}
}

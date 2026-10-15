package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.util.AiLog;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Easter egg: a companion named Herobrine. Says nothing and answers nobody; wears no armour;
 * belongs to no faction. It picks someone (a player, or a companion) and keeps to the edge of
 * their sight, at night standing still and staring; come close and it's gone, far away. By day
 * it makes signs and leaves them about, blank, where they'll be found, and strips the leaves
 * off trees now and then.
 */
public final class Herobrine {
	/** Watching distance at night: far enough to be a shape at the edge of sight. */
	private static final double WATCH_NEAR = 24;
	private static final double WATCH_FAR = 40;
	/** Come this close and it vanishes. */
	private static final double TOO_CLOSE = 10;
	private static final Predicate<ItemStack> SIGN = s -> s.isIn(ItemTags.SIGNS);

	private final Random random = new Random();
	@Nullable
	private ServerPlayerEntity target;
	private long nextVanish;
	private long nextSign;
	private long nextLeaves;
	private long nextCraft;
	@Nullable
	private BlockPos leaves;
	private int leafTicks;
	private BlockPos going = BlockPos.ORIGIN;

	public void tick(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !p.isAlive()) {
			return;
		}
		// Crafting or fetching wood for signs: let the ordinary job machinery do it.
		if (!c.jobs().isIdle()) {
			c.tickJobs(now);
			return;
		}
		if (target == null || !target.isAlive() || target.isSpectator() || target.getWorld() != p.getWorld() || now % 400 == 0) {
			target = EasterEggs.nearestOther(c, 96, EasterEggs.Kind.HEROBRINE);
		}
		boolean night = c.perception().isNight();
		if (target != null) {
			double d = target.distanceTo(p);
			// Approached: gone.
			if (d < TOO_CLOSE && now >= nextVanish) {
				nextVanish = now + 20 * 10;
				if (EasterEggs.vanish(c, target.getPos(), 60, 110)) {
					return;
				}
			}
			if (night) {
				stalk(c, p, target, d, WATCH_NEAR, WATCH_FAR, true);
				return;
			}
		}
		// By day: signs, leaves, and following at a distance.
		if (signs(c, p, now)) {
			return;
		}
		if (leaves(c, p, now)) {
			return;
		}
		if (target != null) {
			stalk(c, p, target, target.distanceTo(p), WATCH_FAR, WATCH_FAR + 24, false);
		}
	}

	/** Keep between {@code near} and {@code far} of the target; in the band, stand still (and stare, at night). */
	private void stalk(Companion c, CompanionPlayer p, ServerPlayerEntity t, double d, double near, double far, boolean stare) {
		if (d > far || d < near) {
			// To a spot the right distance from them, on our side of them.
			Vec3d away = p.getPos().subtract(t.getPos());
			if (away.lengthSquared() < 1) {
				away = new Vec3d(1, 0, 0);
			}
			Vec3d spot = t.getPos().add(away.normalize().multiply((near + far) / 2));
			BlockPos to = BlockPos.ofFloored(spot);
			if (!c.nav().isBusy() || to.getSquaredDistance(going) > 36) {
				going = to;
				c.nav().moveTo(Goal.near(to, 3), false);
			}
			return;
		}
		c.nav().stop();
		if (stare || random.nextInt(4) == 0) {
			c.actions().lookAt(t.getEyePos());
		}
	}

	/** Leave a blank sign somewhere it'll be found; make some first if there are none. */
	private boolean signs(Companion c, CompanionPlayer p, long now) {
		if (now < nextSign) {
			return false;
		}
		if (Inventories.count(p.getInventory(), SIGN) == 0) {
			if (now < nextCraft) {
				return false;
			}
			nextCraft = now + 20 * 60 * 2;
			String wood = wood(p);
			boolean haveWood = Inventories.count(p.getInventory(), s -> s.isIn(ItemTags.LOGS) || s.isIn(ItemTags.PLANKS)) > 0;
			c.jobs().append(haveWood ? List.of("craft " + wood + "_sign 3") : List.of("gather logs 2", "craft " + wood + "_sign 3"));
			AiLog.log(c.name(), "herobrine: making signs");
			return true;
		}
		ServerWorld w = p.getServerWorld();
		BlockPos at = p.getBlockPos();
		for (int i = 0; i < 12; i++) {
			BlockPos spot = at.add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
			if (w.getBlockState(spot).isAir() && w.getBlockState(spot.down()).isSideSolidFullSquare(w, spot.down(), Direction.UP)
				&& !spot.equals(p.getBlockPos()) && c.actions().canReachBlock(spot)) {
				if (c.actions().placeBlock(spot, SIGN)) {
					nextSign = now + 20 * (90 + random.nextInt(150));
					c.memory().count("signs_left", 1);
					AiLog.log(c.name(), "herobrine: left a sign at {}", spot.toShortString());
					return true;
				}
			}
		}
		nextSign = now + 20 * 15;
		return false;
	}

	/** Now and then, take the leaves off a tree within reach, one block at a time. */
	private boolean leaves(Companion c, CompanionPlayer p, long now) {
		ServerWorld w = p.getServerWorld();
		if (leaves != null) {
			if (!w.getBlockState(leaves).isIn(BlockTags.LEAVES) || ++leafTicks > 20 * 6) {
				leaves = null;
				c.actions().abortMining();
			} else {
				c.actions().mineBlock(leaves);
				return true;
			}
		}
		if (now < nextLeaves) {
			return false;
		}
		nextLeaves = now + 20 * (20 + random.nextInt(40));
		BlockPos eye = BlockPos.ofFloored(p.getEyePos());
		for (BlockPos b : BlockPos.iterate(eye.add(-4, -2, -4), eye.add(4, 4, 4))) {
			if (w.getBlockState(b).isIn(BlockTags.LEAVES) && c.actions().canReachBlock(b)) {
				leaves = b.toImmutable();
				leafTicks = 0;
				return true;
			}
		}
		return false;
	}

	/** The wood we've got (for the sign's kind), else oak. */
	private static String wood(CompanionPlayer p) {
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isIn(ItemTags.LOGS) || s.isIn(ItemTags.PLANKS)) {
				String id = Registries.ITEM.getId(s.getItem()).getPath().replace("stripped_", "");
				for (String suffix : new String[]{"_planks", "_log", "_wood", "_stem", "_hyphae"}) {
					if (id.endsWith(suffix)) {
						String wd = id.substring(0, id.length() - suffix.length());
						return wd.equals("bamboo") ? "oak" : wd;
					}
				}
			}
		}
		return "oak";
	}
}

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
 *
 * <p>Underneath it's an ordinary companion (see {@link Persona}): it eats, fights off what attacks
 * it and makes its signs with the ordinary craft job, but it doesn't follow free mode's plans:
 * it has its own.
 */
public final class Herobrine implements Persona {
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
	private long nextSpot;
	/** Checks in a row (every 5 ticks) a player has been looking straight at it. */
	private int stared;
	/** Looked at this long (3 seconds) and it's gone. */
	private static final int STARE_CHECKS = 12;

	/** Its own plans, always: no farming or tidying for Herobrine. (Jobs and drives still run.) */
	@Override
	public boolean ownPlans(Companion c, long now) {
		return true;
	}

	/**
	 * Who it's watching (real players first), and the vanishing act, whatever else is going on:
	 * anyone comes close, or a player looks straight at it for a few seconds.
	 */
	@Override
	public void always(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !p.isAlive()) {
			return;
		}
		if (target == null || !target.isAlive() || target.isSpectator() || target.getWorld() != p.getWorld() || now % 400 == 0) {
			target = pickTarget(c, p);
		}
		if (now < nextVanish || now % 5 != 0) {
			return;
		}
		ServerPlayerEntity spotter = null;
		boolean looked = false;
		for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
			if (o == p || !o.isAlive() || o.isSpectator()) {
				continue;
			}
			double d = o.distanceTo(p);
			boolean real = !(o instanceof CompanionPlayer);
			if (d < (real ? TOO_CLOSE : TOO_CLOSE - 3)) {
				spotter = o; // walked right up to it
				break;
			}
			if (real && d < 48 && lookingAt(o, p)) {
				looked = true;
				if (++stared >= STARE_CHECKS) {
					spotter = o; // looked at it too long
					break;
				}
			}
		}
		if (spotter == null) {
			if (!looked) {
				stared = 0; // looked away: the count starts again
			}
			return;
		}
		stared = 0;
		nextVanish = now + 20 * 8;
		if (EasterEggs.vanish(c, spotter.getPos(), 60, 110)) {
			c.brain().clearAll(); // whatever it was in the middle of, it isn't now
			AiLog.log(c.name(), "herobrine: seen by {}, gone", spotter.getGameProfile().getName());
		}
	}

	@Override
	public void act(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null || !p.isAlive()) {
			return;
		}
		boolean night = c.perception().isNight();
		if (target != null && night) {
			// Watch from the edge of sight, moving to a new spot every half a minute or so.
			watch(c, p, target, now, WATCH_NEAR, WATCH_FAR, true);
			return;
		}
		// By day: signs, leaves, and keeping its distance while circling round.
		if (signs(c, p, now)) {
			return;
		}
		if (leaves(c, p, now)) {
			return;
		}
		if (target != null) {
			watch(c, p, target, now, WATCH_FAR, WATCH_FAR + 24, false);
		} else if (!c.nav().isBusy() && now >= nextSpot) {
			nextSpot = now + 20 * (15 + random.nextInt(20));
			BlockPos to = p.getBlockPos().add(random.nextInt(41) - 20, 0, random.nextInt(41) - 20);
			c.nav().moveTo(Goal.near(to, 3), false);
		}
	}

	/**
	 * Stay between {@code near} and {@code far} of the target, at a spot round them that changes
	 * every so often; there, stand still and (at night) stare.
	 */
	private void watch(Companion c, CompanionPlayer p, ServerPlayerEntity t, long now, double near, double far, boolean stare) {
		double d = t.distanceTo(p);
		if (now >= nextSpot || d > far + 8 || d < near - 4) {
			nextSpot = now + 20 * (20 + random.nextInt(25));
			// Somewhere else round them, on the side we're on more or less.
			double base = Math.atan2(p.getZ() - t.getZ(), p.getX() - t.getX());
			double angle = base + (random.nextDouble() - 0.5) * Math.PI;
			double r = near + random.nextDouble() * (far - near);
			going = BlockPos.ofFloored(t.getX() + Math.cos(angle) * r, t.getY(), t.getZ() + Math.sin(angle) * r);
			c.nav().moveTo(Goal.near(going, 3), false);
			return;
		}
		if (c.nav().isBusy()) {
			return;
		}
		if (stare || random.nextInt(4) == 0) {
			c.actions().lookAt(t.getEyePos());
		}
	}

	/** Real players before companions, nearest first, within 96 blocks. */
	@Nullable
	private static ServerPlayerEntity pickTarget(Companion c, CompanionPlayer p) {
		ServerPlayerEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
			if (o == p || !o.isAlive() || o.isSpectator()) {
				continue;
			}
			double d = o.squaredDistanceTo(p) + (o instanceof CompanionPlayer ? 64 * 64 : 0);
			if (o.squaredDistanceTo(p) < 96 * 96 && d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	/** {@code o} is looking more or less straight at {@code at}. */
	private static boolean lookingAt(ServerPlayerEntity o, CompanionPlayer at) {
		Vec3d to = at.getEyePos().subtract(o.getEyePos()).normalize();
		return o.getRotationVec(1.0F).dotProduct(to) > 0.985 && o.canSee(at);
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

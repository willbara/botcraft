package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tame a wolf, like a player: walk up to the nearest wild one (not angry) and feed it bones until
 * it takes to us (a one-in-three chance a bone), then it's ours: it gets up and follows us, and
 * fights what we fight. Gives up when the bones run out, after {@link #MAX_BONES}, or if the wolf
 * gets away. One wolf a trip.
 */
public class TameTask extends Task {
	public static final Predicate<ItemStack> BONE = s -> s.isOf(Items.BONE);
	/** How far round us to look for a wolf. */
	public static final int RADIUS = 32;
	/** Bones a wolf gets before we give up on it. */
	private static final int MAX_BONES = 10;
	/** Close enough to hand it a bone. */
	private static final double REACH = 2.5;

	@Nullable
	private WolfEntity wolf;
	@Nullable
	private BlockPos goalCenter;
	private int bones;
	private int cooldown;
	private int approachTicks;

	public TameTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "tame";
	}

	@Override
	public String describe() {
		return "taming a wolf";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 60;
	}

	@Override
	protected void onStop() {
		c.nav().stop();
	}

	/** The nearest wild wolf (untamed, alive, not angry) within reach of a walk, or null. */
	@Nullable
	public static WolfEntity nearestWild(CompanionPlayer p, int radius) {
		List<WolfEntity> wolves = p.getServerWorld().getEntitiesByClass(WolfEntity.class, p.getBoundingBox().expand(radius, 8, radius),
			w -> w.isAlive() && !w.isTamed() && !w.hasAngerTime());
		return wolves.stream().min(Comparator.comparingDouble(w -> w.squaredDistanceTo(p))).orElse(null);
	}

	/** Wolves of ours about (alive, within 64 blocks). */
	public static int owned(CompanionPlayer p) {
		return p.getServerWorld().getEntitiesByClass(WolfEntity.class, p.getBoundingBox().expand(64, 16, 64),
			w -> w.isAlive() && w.isTamed() && p.getUuid().equals(w.getOwnerUuid())).size();
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (wolf != null && wolf.isAlive() && wolf.isTamed() && p.getUuid().equals(wolf.getOwnerUuid())) {
			// Ours now: up on its feet to follow us about (taming sits it down).
			wolf.setSitting(false);
			wolf.setInSittingPose(false);
			c.memory().count("wolves_tamed", 1);
			c.speech().event("event.tamed", Map.of("mob", "wolf"), 20 * 60, 0.8F);
			AiLog.log(c.name(), "tame: tamed a wolf after {} bone(s)", bones);
			c.nav().stop();
			return Status.SUCCESS;
		}
		if (Inventories.count(p.getInventory(), BONE) == 0) {
			return fail("I've no bones left for the wolf");
		}
		if (bones >= MAX_BONES) {
			return fail("the wolf wouldn't take to me");
		}
		if (wolf == null || !wolf.isAlive() || wolf.isTamed() || wolf.hasAngerTime() || wolf.distanceTo(p) > RADIUS + 8) {
			wolf = nearestWild(p, RADIUS);
			approachTicks = 0;
			goalCenter = null;
			if (wolf == null) {
				return fail("the wolf got away");
			}
		}
		c.actions().lookAt(wolf);
		double d = p.distanceTo(wolf);
		if (d > REACH) {
			if (++approachTicks > 20 * 30 || c.nav().state() == Navigator.State.FAILED) {
				return fail("I couldn't get near the wolf");
			}
			if (goalCenter == null || c.nav().state() == Navigator.State.IDLE || wolf.getBlockPos().getSquaredDistance(goalCenter) > 9) {
				goalCenter = wolf.getBlockPos();
				c.nav().moveTo(Goal.near(goalCenter, 1.5), false);
			}
			return Status.RUNNING;
		}
		c.nav().stop();
		if (--cooldown <= 0 && c.actions().useItemOnEntity(wolf, BONE)) {
			bones++;
			cooldown = 15; // a moment to see if it took (the hearts, or the smoke)
		}
		return Status.RUNNING;
	}
}

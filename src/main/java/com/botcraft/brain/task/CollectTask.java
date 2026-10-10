package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Leftovers;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.util.AiLog;
import net.minecraft.entity.ItemEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pick up whatever our own work dropped and left lying around (see {@link Leftovers}): logs
 * that fell out of reach, ore that dropped into a hole, a broken crafting table, an animal's
 * meat. Runs quietly between jobs. Anything we can't reach is let go of, and reported in the log.
 */
public class CollectTask extends Task {
	private static final int MAX_TICKS = 20 * 40;

	private final Pickup pickup = new Pickup();
	@Nullable
	private ItemEntity target;
	private int picked;
	private int missed;
	private final java.util.Map<String, Integer> got = new java.util.LinkedHashMap<>();
	private String targetName = "";

	public CollectTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "collect";
	}

	@Override
	public String describe() {
		return "picking up what I dropped";
	}

	@Override
	protected int timeoutTicks() {
		return MAX_TICKS + 20 * 5;
	}

	@Override
	protected Status onTick() {
		if (target == null || !target.isAlive()) {
			if (target != null) {
				picked(targetName);
			}
			List<ItemEntity> lying = c.leftovers().lying();
			target = lying.isEmpty() ? null : lying.get(0);
			if (target != null) {
				targetName = target.getStack().getCount() + " " + com.botcraft.knowledge.ItemNames.id(target.getStack().getItem());
			}
			if (target == null) {
				return done();
			}
		}
		if (ticks() > MAX_TICKS) {
			for (ItemEntity e : c.leftovers().lying()) {
				c.leftovers().giveUp(e);
				missed++;
			}
			return done();
		}
		if (!pickup.tick(c, target)) {
			if (target.isAlive()) {
				// Pickup gave up on it: unreachable (in lava, behind bedrock, across a ravine...).
				c.leftovers().giveUp(target);
				missed++;
			} else {
				picked(targetName);
			}
			target = null;
		}
		return Status.RUNNING;
	}

	private void picked(String what) {
		picked++;
		got.merge(what, 1, Integer::sum);
	}

	private Status done() {
		c.nav().stop();
		c.leftovers().pickedUp(picked);
		if (picked > 0 || missed > 0) {
			AiLog.log(c.name(), "collect: picked up {} dropped item(s) {}{}", picked, got.keySet(), missed > 0 ? ", couldn't reach " + missed : "");
		}
		return quietSuccess();
	}
}

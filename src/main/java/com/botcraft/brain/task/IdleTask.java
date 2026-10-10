package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

/** Nothing to do: look around and, if curious, take a short stroll near where we are. */
public class IdleTask extends Task {
	private final Glances glances = new Glances();
	private final Random random = new Random();
	private BlockPos anchor = BlockPos.ORIGIN;
	private int nextStroll;

	public IdleTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "idle";
	}

	@Override
	public String describe() {
		return "just hanging around";
	}

	@Override
	protected int timeoutTicks() {
		return Integer.MAX_VALUE;
	}

	@Override
	protected void onStart(boolean resumed) {
		anchor = "free".equals(c.data.mode) ? com.botcraft.brain.Life.home(c) : c.player().getBlockPos();
		nextStroll = 200 + random.nextInt(400);
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		if (c.nav().isBusy()) {
			return Status.RUNNING;
		}
		glances.tick(c, c.perception().owner());
		if (--nextStroll <= 0) {
			float curiosity = c.data.curiosity;
			nextStroll = (int) (300 + random.nextInt(400) * (1.2F - curiosity));
			if (random.nextFloat() < curiosity) {
				BlockPos target = anchor.add(random.nextInt(11) - 5, 0, random.nextInt(11) - 5);
				c.nav().moveTo(Goal.near(target, 1.5), false);
			}
		}
		if (c.nav().state() == Navigator.State.FAILED || c.nav().state() == Navigator.State.ARRIVED) {
			c.nav().stop();
		}
		return Status.RUNNING;
	}
}

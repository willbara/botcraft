package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;

/** Hold position and look around. */
public class StayTask extends Task {
	private final Glances glances = new Glances();

	public StayTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "stay";
	}

	@Override
	public String describe() {
		return "staying put";
	}

	@Override
	protected int timeoutTicks() {
		return Integer.MAX_VALUE;
	}

	@Override
	protected Status onTick() {
		glances.tick(c, c.perception().owner());
		return Status.RUNNING;
	}
}

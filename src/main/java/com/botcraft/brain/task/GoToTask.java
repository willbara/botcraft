package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import net.minecraft.util.math.BlockPos;

/** Walk to a spot ("come here"). */
public class GoToTask extends Task {
	private final BlockPos target;
	private final String label;

	public GoToTask(Companion c, BlockPos target, String label) {
		super(c);
		this.target = target.toImmutable();
		this.label = label;
		// Time scales with the trip: climbing out of a deep cave takes a while (digging, pillaring).
		var p = c.player();
		double dist = p == null ? 64 : Math.sqrt(p.getBlockPos().getSquaredDistance(this.target));
		this.timeout = (int) (20 * Math.min(60 * 8, 60 + dist * 1.5));
	}

	private final int timeout;

	public String label() {
		return label;
	}

	@Override
	public String name() {
		return "goto";
	}

	@Override
	public String describe() {
		return "heading " + label;
	}

	@Override
	protected int timeoutTicks() {
		return timeout;
	}

	@Override
	protected void onStart(boolean resumed) {
		double d = Math.sqrt(c.player().getBlockPos().getSquaredDistance(target));
		c.nav().moveTo(Goal.near(target, 2.0), d > 10);
	}

	@Override
	protected Status onTick() {
		Navigator.State s = c.nav().state();
		if (s == Navigator.State.ARRIVED) {
			return Status.SUCCESS;
		}
		if (s == Navigator.State.FAILED) {
			return fail("can't get there: " + c.nav().failReason());
		}
		if (s == Navigator.State.IDLE) {
			onStart(true);
		}
		return Status.RUNNING;
	}
}

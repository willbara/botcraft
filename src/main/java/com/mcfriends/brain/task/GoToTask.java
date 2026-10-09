package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import net.minecraft.util.math.BlockPos;

/** Walk to a spot ("come here"). */
public class GoToTask extends Task {
	private final BlockPos target;
	private final String label;

	public GoToTask(Companion c, BlockPos target, String label) {
		super(c);
		this.target = target.toImmutable();
		this.label = label;
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
		return 20 * 90;
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

package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * "Dance!": a dozen seconds of spinning, hopping, crouch-spamming and punching the air, the way
 * players do. Each companion starts at a different point of the routine so a group doesn't move
 * in lockstep. Whatever it was doing resumes afterwards.
 */
public class DanceTask extends Task {
	private static final int DURATION = 20 * 12;
	private static final int MOVE_LENGTH = 40;

	private int offset;
	private int strafe = 1;

	public DanceTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "dance";
	}

	@Override
	public String describe() {
		return "dancing";
	}

	@Override
	protected int timeoutTicks() {
		return DURATION + 40;
	}

	@Override
	protected void onStart(boolean resumed) {
		c.nav().stop();
		offset = Math.floorMod(c.name().hashCode(), 4) * MOVE_LENGTH;
	}

	@Override
	protected void onStop() {
		c.actions().sneak(false);
		super.onStop();
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		int t = ticks();
		if (t > DURATION) {
			c.actions().sneak(false);
			return quietSuccess();
		}
		int beat = t + offset;
		switch ((beat / MOVE_LENGTH) % 4) {
			case 0 -> {
				// Spin and hop.
				double angle = beat * 0.45;
				c.actions().lookAt(p.getEyePos().add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3));
				if (p.isOnGround() && beat % 10 == 0) {
					c.actions().jump();
				}
				c.actions().sneak(false);
			}
			case 1 -> {
				// Crouch spam, nodding along.
				c.actions().sneak(beat % 6 < 3);
				c.actions().lookAt(p.getEyePos().add(p.getRotationVector().multiply(3)).add(0, beat % 12 < 6 ? 1 : -1, 0));
			}
			case 2 -> {
				// Punch the air while shuffling side to side.
				c.actions().sneak(false);
				if (beat % 5 == 0) {
					p.swingHand(beat % 10 == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND);
				}
				if (beat % 20 == 0) {
					strafe = -strafe;
				}
				Vec3d facing = p.getRotationVector();
				Vec3d side = new Vec3d(-facing.z, 0, facing.x).normalize().multiply(strafe);
				c.actions().moveTo(p.getPos().add(side), 0.5);
			}
			default -> {
				// Jump spam with a flourish.
				c.actions().sneak(false);
				if (p.isOnGround()) {
					c.actions().jump();
				}
				if (beat % 8 == 0) {
					p.swingHand(Hand.MAIN_HAND);
				}
			}
		}
		return Status.RUNNING;
	}
}

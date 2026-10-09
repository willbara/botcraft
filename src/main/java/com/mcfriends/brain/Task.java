package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.util.AiLog;

/**
 * A unit of behaviour. Tasks are ticked every server tick while on top of the stack, can be
 * paused (when something more urgent comes up) and resumed later with their state intact.
 * Every task has a timeout; exceeding it fails the task.
 */
public abstract class Task {
	protected final Companion c;
	private int ticks;
	private boolean started;
	private String failReason = "";

	protected Task(Companion c) {
		this.c = c;
	}

	public abstract String name();

	/** Human-readable, used for "what are you doing?" (e.g. "chopping wood (3/16)"). */
	public abstract String describe();

	/** Ticks this task may run in total (including after resumes). */
	protected int timeoutTicks() {
		return 20 * 60 * 10;
	}

	protected void onStart(boolean resumed) {
	}

	protected abstract Status onTick();

	/** Called when paused or finished: release movement, mining, etc. */
	protected void onStop() {
		c.nav().stop();
		c.actions().reset();
	}

	public final void start() {
		AiLog.log(c.name(), "{} task {}", started ? "resume" : "start", name());
		onStart(started);
		started = true;
	}

	public final void pause() {
		AiLog.log(c.name(), "pause task {}", name());
		onStop();
	}

	public final void stop() {
		onStop();
	}

	public final Status tick() {
		if (++ticks > timeoutTicks()) {
			fail("timed out");
			return Status.FAILED;
		}
		return onTick();
	}

	public int ticks() {
		return ticks;
	}

	protected Status fail(String reason) {
		failReason = reason;
		AiLog.log(c.name(), "task {} failed: {}", name(), reason);
		return Status.FAILED;
	}

	public String failReason() {
		return failReason;
	}
}

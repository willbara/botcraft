package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.util.AiLog;
import org.jetbrains.annotations.Nullable;

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
	@Nullable
	private String spec;
	private boolean silent;

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

	/** Mark this task as a queued job created from {@code spec} (see Jobs). */
	public Task asJob(String spec) {
		this.spec = spec;
		return this;
	}

	@Nullable
	public String spec() {
		return spec;
	}

	/** End successfully without announcing anything (e.g. a wait that just gave up). */
	protected Status quietSuccess() {
		silent = true;
		return Status.SUCCESS;
	}

	/** Finished without anything worth announcing (e.g. it handed over to prerequisite jobs). */
	public boolean silent() {
		return silent;
	}

	/**
	 * Something else has to happen first: queue those jobs, then this one again, and end quietly.
	 * A retry counter in the spec stops endless loops (e.g. a prerequisite that keeps failing).
	 */
	protected Status deferTo(java.util.List<String> prerequisites) {
		String self = spec != null ? spec : name();
		int attempt = Jobs.attempt(self);
		if (attempt >= 3) {
			return fail("I keep going in circles");
		}
		java.util.List<String> list = new java.util.ArrayList<>();
		if (Jobs.optional(self)) {
			// Optional prep only sets up a table or furnace; anything bigger isn't "easy" any more.
			for (String pre : prerequisites) {
				if (!pre.startsWith("craft crafting_table") && !pre.startsWith("craft furnace") && !pre.startsWith("withdraw ")) {
					return fail("not worth the trouble right now");
				}
				list.add("?" + pre);
			}
		} else {
			list.addAll(prerequisites);
		}
		list.add(Jobs.withAttempt(self, attempt + 1));
		c.jobs().pushFront(list);
		AiLog.log(c.name(), "{} needs {} first", name(), prerequisites);
		silent = true;
		return Status.SUCCESS;
	}
}

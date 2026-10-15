package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.brain.task.FollowOwnerTask;
import com.botcraft.brain.task.IdleTask;
import com.botcraft.brain.task.StayTask;
import com.botcraft.config.CompanionConfig;
import com.botcraft.util.AiLog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;

/**
 * Utility AI over a task stack.
 *
 * <p>Every {@code aiTickInterval} ticks all drives are scored. If the best objective is an
 * interrupt (danger, hunger, low health) and beats whatever is on top of the stack by a margin,
 * a task for it is pushed and the current one is paused. Otherwise the base activity (follow,
 * work, stay, idle) may switch. Finished interrupts pop and the paused task resumes where it
 * left off. The current task is ticked every tick.
 */
public class Brain {
	private static final float HYSTERESIS = 0.08F;
	private static final float DROP_INTERRUPT_BELOW = 0.15F;

	private record Entry(Objective objective, Task task) {
	}

	private final Companion c;
	private final List<Drive> drives;
	private final TaskFactory factory;
	private final Deque<Entry> interrupts = new ArrayDeque<>();
	private final EnumMap<Objective, Task> baseTasks = new EnumMap<>(Objective.class);
	@Nullable
	private Task base;
	@Nullable
	private Objective baseObjective;
	@Nullable
	private Task job;
	private Scores lastScores = new Scores();
	private final int thinkOffset;
	private boolean thinkNow = true;
	/** Interrupts that just ended almost immediately: don't start them again right away. */
	private final EnumMap<Objective, Long> quickEnd = new EnumMap<>(Objective.class);
	private long now;

	/** Creates tasks for interrupt objectives (may return null, e.g. EAT with no food). */
	public interface TaskFactory {
		@Nullable
		Task create(Companion c, Objective objective);
	}

	public Brain(Companion c, List<Drive> drives, TaskFactory factory) {
		this.c = c;
		this.drives = drives;
		this.factory = factory;
		this.thinkOffset = Math.floorMod(c.name().hashCode(), 20);
	}

	public Personality personality() {
		return Personality.of(c.data);
	}

	public boolean hasJob() {
		return job != null;
	}

	public String jobName() {
		return job == null ? "none" : job.name();
	}

	@Nullable
	public Task job() {
		return job;
	}

	public Scores lastScores() {
		return lastScores;
	}

	@Nullable
	public Task current() {
		Entry top = interrupts.peek();
		return top != null ? top.task() : base;
	}

	@Nullable
	public Objective currentObjective() {
		Entry top = interrupts.peek();
		return top != null ? top.objective() : baseObjective;
	}

	public String describe() {
		Task t = current();
		return t == null ? "thinking" : t.describe();
	}

	// ---- control from commands / chat -----------------------------------------------------------

	/** Replace the current job (null clears it). */
	public void setJob(@Nullable Task newJob) {
		if (job != null) {
			if (base == job) {
				job.stop();
				base = null;
				baseObjective = null;
			}
		}
		job = newJob;
		if (job == null) {
			c.data.job = null;
		}
		thinkNow = true;
	}

	/** Something urgent: decide again on the next tick (keeping what we're doing unless something beats it). */
	public void alert() {
		thinkNow = true;
	}

	/** Re-evaluate on the next tick (mode changed etc.). Also drops the base task so it restarts fresh. */
	public void reconsider() {
		if (interrupts.isEmpty() && base != null) {
			base.stop();
		}
		base = null;
		baseObjective = null;
		thinkNow = true;
	}

	/** Drop everything (used by "stop"). Danger interrupts will come back on their own if still needed. */
	public void clearAll() {
		while (!interrupts.isEmpty()) {
			interrupts.pop().task().stop();
		}
		setJob(null);
		reconsider();
	}

	// ---- ticking --------------------------------------------------------------------------------

	public void tick(long serverTick) {
		now = serverTick;
		int interval = CompanionConfig.get().aiTickInterval;
		if (thinkNow || current() == null || (serverTick + thinkOffset) % interval == 0) {
			thinkNow = false;
			think();
		}
		Task cur = current();
		if (cur == null) {
			return;
		}
		Status s = cur.tick();
		if (s != Status.RUNNING) {
			finished(cur, s);
		}
	}

	private void think() {
		Scores scores = new Scores();
		for (Drive d : drives) {
			d.evaluate(c, this, scores);
		}
		lastScores = scores;
		Objective curObj = currentObjective();

		// Drop an interrupt nobody wants any more (e.g. the threat left).
		Entry top = interrupts.peek();
		if (top != null && scores.get(top.objective()) < DROP_INTERRUPT_BELOW) {
			AiLog.log(c.name(), "drop {} (no longer wanted) [{}]", top.objective(), scores);
			interrupts.pop().task().stop();
			resumeCurrent();
			top = interrupts.peek();
			curObj = currentObjective();
		}

		Objective best = scores.best(curObj);
		if (best == null) {
			return;
		}
		float bestScore = scores.get(best);

		if (best.interrupts) {
			if (now < quickEnd.getOrDefault(best, 0L)) {
				return; // it just ended on its own; give the situation a moment to change
			}
			if (top == null || (top.objective() != best && bestScore > scores.get(top.objective()) + HYSTERESIS)) {
				Task t = factory.create(c, best);
				if (t != null) {
					AiLog.log(c.name(), "interrupt -> {} ({}) [{}]", best, scores.reason(best), scores);
					Task paused = current();
					if (paused != null) {
						paused.pause();
					}
					interrupts.push(new Entry(best, t));
					t.start();
					c.onInterruptStarted(best, t);
				}
			}
			return;
		}
		if (top != null) {
			return; // let the interrupt finish first
		}
		if (base == null || (best != baseObjective && bestScore > scores.get(baseObjective) + HYSTERESIS)) {
			switchBase(best, scores);
		}
	}

	private void switchBase(Objective objective, Scores scores) {
		Task next = baseTask(objective);
		if (next == null || next == base) {
			return;
		}
		AiLog.log(c.name(), "activity -> {} ({}) [{}]", objective, scores.reason(objective), scores);
		if (base != null) {
			base.pause();
		}
		base = next;
		baseObjective = objective;
		next.start();
	}

	@Nullable
	private Task baseTask(Objective objective) {
		return switch (objective) {
			case WORK -> job;
			case FOLLOW -> baseTasks.computeIfAbsent(objective, o -> new FollowOwnerTask(c));
			case STAY -> baseTasks.computeIfAbsent(objective, o -> new StayTask(c));
			case IDLE -> baseTasks.computeIfAbsent(objective, o -> new IdleTask(c));
			case SLEEP -> baseTasks.computeIfAbsent(objective, o -> new com.botcraft.brain.task.SleepTask(c));
			default -> null;
		};
	}

	private void finished(Task task, Status status) {
		Entry top = interrupts.peek();
		if (top != null && top.task() == task) {
			interrupts.pop();
			if (task.ticks() < 10) {
				quickEnd.put(top.objective(), now + 40);
			}
			task.stop();
			AiLog.log(c.name(), "{} finished: {}", top.objective(), status);
			c.onTaskFinished(task, status, true);
		} else if (task == base) {
			task.stop();
			if (task == job) {
				job = null;
				c.data.job = null;
			} else {
				baseTasks.values().remove(task); // a fresh instance next time
			}
			AiLog.log(c.name(), "{} finished: {}", task.name(), status);
			base = null;
			baseObjective = null;
			c.onTaskFinished(task, status, false);
		}
		thinkNow = true;
		resumeCurrent();
	}

	private void resumeCurrent() {
		Task t = current();
		if (t != null) {
			t.start();
		}
	}
}

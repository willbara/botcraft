package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.util.AiLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered list of jobs ("chop 16 logs, then mine 8 coal"). The current job lives in the Brain's
 * job slot; waiting ones are specs in CompanionData.jobs so they survive restarts. Jobs may push
 * prerequisites in front of themselves (mining needs a pickaxe -> craft one first).
 */
public class JobQueue {
	private final Companion c;

	public JobQueue(Companion c) {
		this.c = c;
	}

	private List<String> queue() {
		if (c.data.jobs == null) {
			c.data.jobs = new ArrayList<>();
		}
		return c.data.jobs;
	}

	/** Drop the current job and everything queued, then do these. */
	public void replace(List<String> specs) {
		queue().clear();
		c.brain().setJob(null);
		queue().addAll(specs);
		AiLog.log(c.name(), "jobs = {}", specs);
	}

	public void append(List<String> specs) {
		queue().addAll(specs);
		AiLog.log(c.name(), "jobs += {}", specs);
	}

	/** Run these next (before anything already queued); the current job keeps running. */
	public void pushFront(List<String> specs) {
		queue().addAll(0, specs);
	}

	/**
	 * Do these right now: the current job is paused (re-queued right after them) and resumes when
	 * they're done. Used when a friend asks for help.
	 */
	public void interruptWith(List<String> specs) {
		List<String> list = new ArrayList<>(specs);
		if (c.brain().hasJob() && c.data.job != null) {
			list.add(c.data.job);
		}
		c.brain().setJob(null);
		queue().addAll(0, list);
		AiLog.log(c.name(), "interrupted with {}", specs);
	}

	public void clear() {
		queue().clear();
		c.brain().setJob(null);
	}

	public List<String> pending() {
		return queue();
	}

	public boolean isIdle() {
		return !c.brain().hasJob() && queue().isEmpty();
	}

	/** Start the next job when the slot is free. */
	public void tick() {
		while (!c.brain().hasJob() && !queue().isEmpty()) {
			String spec = queue().remove(0);
			Task t = Jobs.create(c, spec);
			if (t == null) {
				AiLog.log(c.name(), "dropping unknown job '{}'", spec);
				continue;
			}
			c.data.job = spec;
			c.brain().setJob(t.asJob(spec));
			AiLog.log(c.name(), "next job: {}", spec);
		}
	}

	/** A job failed: the rest of the plan probably depended on it. Returns how many were dropped. */
	public int abandonRest() {
		int n = queue().size();
		queue().clear();
		return n;
	}
}

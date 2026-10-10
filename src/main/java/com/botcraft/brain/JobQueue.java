package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.util.AiLog;

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
		if (!c.brain().hasJob() && wantsCollect()) {
			// Our own drops are lying about: pick them up before moving on.
			Task t = Jobs.create(c, "collect");
			c.data.job = null;
			c.brain().setJob(t.asJob("collect"));
			AiLog.log(c.name(), "collecting leftover drops first");
			return;
		}
		while (!c.brain().hasJob() && !queue().isEmpty()) {
			String spec = queue().remove(0);
			if (!spec.trim().endsWith("!") && !Jobs.optional(spec)) {
				// Check tools first: make or upgrade one if it's easy, then come back to this.
				List<String> prep = com.botcraft.brain.task.ToolPrep.before(c, spec);
				String marked = spec.trim() + " !";
				if (!prep.isEmpty()) {
					List<String> next = new ArrayList<>(prep);
					next.add(marked);
					queue().addAll(0, next);
					AiLog.log(c.name(), "tool prep before '{}': {}", spec, prep);
					continue;
				}
				spec = marked;
			}
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

	private long nextCollectCheck;

	private boolean wantsCollect() {
		if (c.now() < nextCollectCheck || "stay".equals(c.data.mode) || !c.leftovers().anyExpected()) {
			return false;
		}
		nextCollectCheck = c.now() + 20;
		if (c.leftovers().lying().isEmpty()) {
			return false;
		}
		if (queue().isEmpty()) {
			// Nothing else to do: tidy up now, but bits that keep dropping later (saplings from
			// decaying leaves) are picked up every so often, not chased one by one.
			nextCollectCheck = c.now() + 200;
		}
		return true;
	}

	/** An optional prep job failed: skip the prep that followed it, keep the real work. */
	public void skipOptional() {
		while (!queue().isEmpty() && Jobs.optional(queue().get(0))) {
			com.botcraft.brain.task.ToolPrep.failed(c, queue().remove(0));
		}
	}

	/** A job failed: the rest of the plan probably depended on it. Returns how many were dropped. */
	public int abandonRest() {
		int n = queue().size();
		queue().clear();
		return n;
	}
}

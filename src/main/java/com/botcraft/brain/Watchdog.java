package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Notices a companion that is "working" but getting nowhere: no movement beyond a few blocks,
 * nothing broken, no change in what it carries, for several minutes. Every task has its own
 * timeout, but those are long (mining runs up to 25 minutes) and a task can keep itself busy
 * without progress (re-planning the same unreachable path, retrying the same placement). The
 * watchdog catches that and lets the normal failure handling take over.
 */
public final class Watchdog {
	/** How long without any sign of progress before giving up on the job. */
	public static final long STALL_TICKS = 20 * 60 * 3;
	/** Jobs that legitimately stand still for a long time. */
	private static final Set<String> STILL_JOBS = Set.of("sleep", "shelter", "await", "stay");

	@Nullable
	private BlockPos anchor;
	private int lastItems = -1;
	private long progressAt;
	private long lastBreakSeen;

	/** @return true if the current job has stalled (call every so often, not every tick) */
	public boolean check(Companion c, long now) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return false;
		}
		BlockPos pos = p.getBlockPos();
		int items = 0;
		for (int i = 0; i < p.getInventory().main.size(); i++) {
			items += p.getInventory().getStack(i).getCount();
		}
		boolean working = c.brain().hasJob() && !STILL_JOBS.contains(c.brain().jobName());
		boolean moved = anchor == null || pos.getSquaredDistance(anchor) > 3 * 3;
		boolean broke = c.leftovers().lastExpectAt() > lastBreakSeen;
		// Gaining things is progress; eating or placing blocks while going nowhere isn't.
		if (!working || moved || items > lastItems || broke) {
			anchor = pos;
			lastItems = items;
			lastBreakSeen = c.leftovers().lastExpectAt();
			progressAt = now;
			return false;
		}
		if (items < lastItems) {
			lastItems = items; // remember the lower count, so gaining it back later counts
		}
		if (now - progressAt > STALL_TICKS) {
			progressAt = now; // give whatever comes next a fresh start
			return true;
		}
		return false;
	}
}

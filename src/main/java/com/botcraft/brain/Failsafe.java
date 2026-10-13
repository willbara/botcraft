package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.entity.CompanionPlayer;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Free mode's last resort. A free companion never follows or teleports to the owner; it lives
 * on its own. But if something has clearly gone wrong (wedged inside blocks, standing in the same
 * spot for minutes, or jobs stalling one after another), it's put back home, like a player
 * typing /home after getting hopelessly stuck.
 */
public final class Failsafe {
	/** Not moving more than a few blocks for this long (outside rest) means stuck. */
	public static final long STUCK_TICKS = 20 * 60 * 5;
	/** Inside blocks (suffocating) for this long. */
	public static final long WEDGED_TICKS = 20 * 3;
	/** Two watchdog stalls within this window. */
	public static final long STALL_WINDOW = 20 * 60 * 10;
	/** Resting is standing still on purpose. */
	private static final Set<String> RESTING_JOBS = Set.of("sleep", "shelter", "await");

	@Nullable
	private BlockPos anchor;
	private long anchorSince;
	private long wedgedSince = -1;
	private long lastStall = -1_000_000;
	private long lastCheck = Long.MIN_VALUE;

	private long lastWaterGiveUp = -1_000_000;
	private long handDigSince = -1;
	/** Digging out by hand (no pickaxe) this long means we're entombed, not getting anywhere soon. */
	public static final long HAND_DIG_TICKS = 20 * 90;

	/** A job was given up for too much water. @return true if it's the second within the window */
	public boolean waterTrapped(long now) {
		boolean repeated = now - lastWaterGiveUp < STALL_WINDOW;
		lastWaterGiveUp = now;
		return repeated;
	}

	/** A watchdog stall happened. @return true if it's the second within the window */
	public boolean stalled(long now) {
		boolean repeated = now - lastStall < STALL_WINDOW;
		lastStall = now;
		return repeated;
	}

	/**
	 * @param home where the companion lives (or null)
	 * @return why it needs rescuing, or null if all is well
	 */
	@Nullable
	public String check(Companion c, long now, @Nullable BlockPos home) {
		CompanionPlayer p = c.player();
		boolean gap = lastCheck == Long.MIN_VALUE || now - lastCheck > 20 * 60; // paused, unloaded, or just switched on
		lastCheck = now;
		if (p == null || !"free".equals(c.data.mode) || !p.isAlive() || gap) {
			reset(now, p == null ? null : p.getBlockPos());
			return null;
		}
		if (p.isInsideWall()) {
			if (wedgedSince < 0) {
				wedgedSince = now;
			} else if (now - wedgedSince > WEDGED_TICKS) {
				wedgedSince = -1;
				return "stuck inside blocks";
			}
		} else {
			wedgedSince = -1;
		}
		// Entombed in rock with no pickaxe: punching out a block every 7 s, which keeps the other
		// checks happy (it moves, it breaks things) while it gets nowhere.
		boolean handDigging = c.nav().digging() && Inventories.bestPickaxe(p.getInventory()) == null
			&& !com.botcraft.nav.Terrain.openSky(p.getWorld(), p.getBlockPos().up());
		if (!handDigging) {
			handDigSince = -1;
		} else if (handDigSince < 0) {
			handDigSince = now;
		} else if (now - handDigSince > HAND_DIG_TICKS) {
			handDigSince = -1;
			return "digging out by hand with no pickaxe";
		}
		BlockPos pos = p.getBlockPos();
		boolean resting = c.brain().currentObjective() == Objective.SLEEP || RESTING_JOBS.contains(c.brain().jobName())
			|| (home != null && pos.getSquaredDistance(home) < 16 * 16 && !c.brain().hasJob());
		if (anchor == null || resting || pos.getSquaredDistance(anchor) > 4 * 4) {
			anchor = pos;
			anchorSince = now;
			return null;
		}
		if (now - anchorSince > STUCK_TICKS) {
			anchorSince = now;
			return "hasn't moved in 5 minutes";
		}
		return null;
	}

	private void reset(long now, @Nullable BlockPos pos) {
		anchor = pos;
		anchorSince = now;
		wedgedSince = -1;
	}
}

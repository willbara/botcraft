package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of what a companion's own actions dropped: every block it breaks (logs, ore, its
 * own crafting table) and every animal or mob it kills leaves a "drop spot". Items lying near a
 * recent drop spot are ours to pick up; the job queue sends the companion to collect them before
 * starting the next job, so nothing gets left behind.
 *
 * <p>Items someone threw (a player's drops, a friend's delivery) are never counted as ours.
 * Items we couldn't reach are let go of after a try, so we don't keep running back for them.
 */
public final class Leftovers {
	/** How long a drop spot stays interesting (items despawn after 5 minutes anyway). */
	private static final long TTL = 20 * 60 * 3;
	/** How far an item may roll or fall from its spot and still count as from it. */
	private static final double SPREAD = 2.5;
	private static final double FALL = 6;
	/** Only collect within this distance; further is somebody else's problem. */
	public static final double RANGE = 16;
	private static final int MAX_SPOTS = 64;

	private record Spot(BlockPos pos, long at) {
	}

	private final Companion c;
	private final ArrayDeque<Spot> spots = new ArrayDeque<>();
	/** Items we tried and failed to reach, and until when we leave them alone. */
	private final Map<UUID, Long> givenUp = new HashMap<>();
	private int collected;
	private int missed;

	public Leftovers(Companion c) {
		this.c = c;
	}

	/** Something of ours may drop around here (a block broke, an animal died). */
	public void expect(BlockPos pos) {
		BlockPos p = pos.toImmutable();
		spots.removeIf(s -> s.pos.equals(p));
		spots.addLast(new Spot(p, c.now()));
		while (spots.size() > MAX_SPOTS) {
			spots.removeFirst();
		}
	}

	public boolean anyExpected() {
		prune();
		return !spots.isEmpty();
	}

	/** Our drops lying around within {@link #RANGE}, nearest first. */
	public List<ItemEntity> lying() {
		prune();
		CompanionPlayer p = c.player();
		if (spots.isEmpty() || p == null) {
			return List.of();
		}
		List<ItemEntity> items = p.getServerWorld().getEntitiesByClass(ItemEntity.class, new Box(p.getBlockPos()).expand(RANGE, 8, RANGE), this::ours);
		items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		return items;
	}

	private boolean ours(ItemEntity e) {
		if (!e.isAlive() || e.getStack().isEmpty() || givenUp.containsKey(e.getUuid())) {
			return false;
		}
		Entity thrower = e.getOwner();
		if (thrower != null && thrower != c.player()) {
			return false; // someone threw this (a player, a friend handing something over)
		}
		for (Spot s : spots) {
			double dx = e.getX() - (s.pos.getX() + 0.5);
			double dz = e.getZ() - (s.pos.getZ() + 0.5);
			double dy = s.pos.getY() + 0.5 - e.getY();
			if (dx * dx + dz * dz <= SPREAD * SPREAD && dy >= -SPREAD && dy <= FALL) {
				return true;
			}
		}
		return false;
	}

	/** Couldn't get to it: leave it for a while. */
	public void giveUp(ItemEntity e) {
		givenUp.put(e.getUuid(), c.now() + TTL);
		missed++;
	}

	public void pickedUp(int stacks) {
		collected += stacks;
	}

	public int collected() {
		return collected;
	}

	public int missed() {
		return missed;
	}

	private void prune() {
		long now = c.now();
		for (Iterator<Spot> it = spots.iterator(); it.hasNext(); ) {
			if (now - it.next().at > TTL) {
				it.remove();
			}
		}
		givenUp.values().removeIf(until -> until < now);
	}
}

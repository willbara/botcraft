package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.colony.Kit;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.faction.Faction;
import com.botcraft.faction.Factions;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * popbob's raid (see brain.Popbob): walk to a faction's base and help itself to what's in its
 * chests, the most valuable things first, until its pockets are full, the chests are empty or
 * it's been there a while. The faction finds out (its companions there fight back).
 */
public class StealTask extends Task {
	private static final int STAY_TICKS = 20 * 90;
	private static final int TRAVEL_TICKS = 20 * 120;

	private final String faction;
	private final ChestVisit visit = new ChestVisit();
	private final Set<BlockPos> emptied = new HashSet<>();
	private long arrivedAt = -1;
	private int taken;

	public StealTask(Companion c, String faction) {
		super(c);
		this.faction = faction;
	}

	@Override
	public String name() {
		return "steal";
	}

	@Override
	public String describe() {
		return "making trouble at " + factionName() + "'s base";
	}

	@Override
	protected int timeoutTicks() {
		return TRAVEL_TICKS + STAY_TICKS + 20 * 30;
	}

	@Override
	protected void onStop() {
		c.nav().stop();
	}

	public int taken() {
		return taken;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		Colony them = Colony.of(faction);
		if (them == null || !them.hasBase() || them.world() != p.getServerWorld()) {
			return fail("nobody to rob there");
		}
		BlockPos base = them.base();
		if (arrivedAt < 0) {
			if (p.getBlockPos().getSquaredDistance(base) < 10 * 10) {
				arrivedAt = c.now();
			} else {
				if (c.nav().state() == Navigator.State.FAILED || ticks() > TRAVEL_TICKS) {
					return fail("couldn't get to their base");
				}
				if (!c.nav().isBusy()) {
					c.nav().moveTo(Goal.near(base, 6), true);
				}
				return Status.RUNNING;
			}
		}
		if (c.now() - arrivedAt > STAY_TICKS || Kit.emptySlots(p.getInventory()) < 2) {
			return finish();
		}
		if (visit.chest() == null) {
			BlockPos next = null;
			double best = Double.MAX_VALUE;
			for (BlockPos chest : them.chests()) {
				double d = chest.getSquaredDistance(p.getBlockPos());
				if (!emptied.contains(chest) && d < best) {
					best = d;
					next = chest;
				}
			}
			if (next == null) {
				return finish();
			}
			visit.start(next);
		}
		Inventory chest = visit.tick(c);
		if (visit.failed()) {
			emptied.add(visit.chest());
			visit.reset();
			return Status.RUNNING;
		}
		if (chest == null) {
			return Status.RUNNING;
		}
		for (int pass = 3; pass >= 1; pass--) {
			for (int i = 0; i < chest.size() && Kit.emptySlots(p.getInventory()) > 1; i++) {
				ItemStack s = chest.getStack(i);
				if (s.isEmpty() || RaidTask.value(s) != pass) {
					continue;
				}
				ItemStack take = s.copy();
				int before = take.getCount();
				p.getInventory().insertStack(take);
				int got = before - take.getCount();
				s.decrement(got);
				taken += got;
			}
		}
		chest.markDirty();
		them.touched();
		c.perception().invalidateInventory();
		emptied.add(visit.chest());
		visit.reset();
		return Status.RUNNING;
	}

	private Status finish() {
		c.nav().stop();
		AiLog.log(c.name(), "popbob: robbed {} of {} item(s)", faction, taken);
		if (taken > 0) {
			c.memory().count("stolen", taken);
			c.speech().event("popbob.steal", Map.of("faction", factionName(), "count", Integer.toString(taken)), 20 * 30, 1.0F);
			Companion witness = nearestMember();
			if (witness != null) {
				witness.speech().event("popbob.robbed", Map.of(), 20 * 30, 1.0F);
				witness.relations().note(c.name(), com.botcraft.brain.Relationship.Event.STOLE);
			}
		}
		return taken > 0 ? Status.SUCCESS : quietSuccess();
	}

	private Companion nearestMember() {
		Companion best = null;
		double bestD = 32 * 32;
		for (Companion o : Factions.members(faction)) {
			if (o.player() == null || !o.isOnline() || o.player().getWorld() != c.player().getWorld()) {
				continue;
			}
			double d = o.player().squaredDistanceTo(c.player());
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	private String factionName() {
		Faction f = Factions.get(faction);
		return f == null ? faction : f.name;
	}
}

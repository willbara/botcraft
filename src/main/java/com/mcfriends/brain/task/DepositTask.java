package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.colony.Colony;
import com.mcfriends.colony.Kit;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Store everything that isn't part of the kit (see {@link Kit}) in the base chests. Fills chests
 * that already hold the same things first; when they're all full, puts down a new chest at the
 * base (crafting one from carried wood if needed).
 */
public class DepositTask extends Task {
	private final ChestVisit visit = new ChestVisit();
	private int stored;
	private int chestsTried;
	private int placeTicks;
	private boolean placing;

	public DepositTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "deposit";
	}

	@Override
	public String describe() {
		return "putting things away in the chests";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	protected Status onTick() {
		Colony colony = Colony.get();
		CompanionPlayer p = c.player();
		if (colony == null || !colony.hasBase() || colony.world() != p.getServerWorld()) {
			return fail("we don't have a base with chests");
		}
		PlayerInventory inv = p.getInventory();
		Map<Integer, Integer> toStore = Kit.toStore(inv);
		if (toStore.isEmpty()) {
			return done();
		}
		if (placing) {
			return placeChest(colony, p);
		}
		if (visit.chest() == null) {
			ItemStack first = inv.getStack(toStore.keySet().iterator().next());
			BlockPos chest = colony.chestWithRoom(first, p.getBlockPos());
			if (chest == null || chestsTried > 8) {
				placing = true;
				placeTicks = 0;
				return Status.RUNNING;
			}
			chestsTried++;
			visit.start(chest);
		}
		Inventory chest = visit.tick(c);
		if (visit.failed()) {
			c.baseUnreachableUntil = c.now() + 20 * 60 * 3; // can't get there: fetch nothing from the base for a while
			AiLog.log(c.name(), "deposit: couldn't get to the chest at {}", visit.chest().toShortString());
			visit.reset();
			return chestsTried > 3 ? done() : Status.RUNNING;
		}
		if (chest == null) {
			return Status.RUNNING;
		}
		// Open: move what belongs here (each item's best chest is this one) and fits.
		for (Map.Entry<Integer, Integer> e : toStore.entrySet()) {
			ItemStack s = inv.getStack(e.getKey());
			if (!visit.chest().equals(colony.chestWithRoom(s, p.getBlockPos()))) {
				continue; // has a better home (its own kind's chest); we'll take it there next
			}
			ItemStack part = s.split(e.getValue());
			int moved = ChestVisit.insert(chest, part);
			stored += moved;
			if (!part.isEmpty()) {
				s.increment(part.getCount()); // didn't fit: back in our hand
			}
		}
		colony.touched();
		c.perception().invalidateInventory();
		visit.reset(); // anything left goes to the next chest with room
		return Status.RUNNING;
	}

	/** All chests full (or none yet): put a new one down at the base. */
	private Status placeChest(Colony colony, CompanionPlayer p) {
		if (Inventories.count(p.getInventory(), s -> s.isOf(Items.CHEST)) == 0) {
			if (colony.chestCount() > 24) {
				return done(); // enough chests; something odd is going on
			}
			// A spare chest in storage (taking it out frees a slot too), else make one.
			if (colony.count(st -> st.isOf(Items.CHEST)) > 0) {
				return deferTo(List.of("withdraw chest 1"));
			}
			return deferTo(List.of("craft chest 1"));
		}
		BlockPos spot = colony.newChestSpot();
		if (spot == null) {
			AiLog.log(c.name(), "deposit: no room for another chest at the base");
			return done();
		}
		placeTicks++;
		if (!c.actions().canReachBlock(spot)) {
			if (placeTicks == 1 || c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), spot, 3.5), false);
			}
			return placeTicks > 20 * 30 ? done() : Status.RUNNING;
		}
		c.nav().stop();
		if (c.actions().placeBlock(spot, s -> s.isOf(Items.CHEST))) {
			colony.addChest(spot); // (registers the other half too if it joined a neighbour)
			AiLog.log(c.name(), "deposit: put a new chest at {}", spot.toShortString());
			placing = false;
			chestsTried = 0;
		} else if (placeTicks > 20 * 10) {
			AiLog.log(c.name(), "deposit: couldn't place a chest at {} ({})", spot.toShortString(), c.actions().lastPlaceProblem());
			return done();
		}
		return Status.RUNNING;
	}

	private Status done() {
		c.nav().stop();
		if (stored > 0) {
			AiLog.log(c.name(), "deposit: stored {} item(s)", stored);
			com.mcfriends.ops.SoakStats.stored(stored);
		} else if (!Kit.toStore(c.player().getInventory()).isEmpty()) {
			return fail("there's no room in the chests"); // so we don't keep trying
		}
		return quietSuccess();
	}
}

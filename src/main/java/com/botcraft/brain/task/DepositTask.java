package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.colony.Kit;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.nav.Goal;
import com.botcraft.nav.Navigator;
import com.botcraft.util.AiLog;
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
	/** Where the new chest goes (worked out once; it's a big search on steep ground). */
	@org.jetbrains.annotations.Nullable
	private BlockPos newSpot;
	/** Spots where putting the chest down didn't work (someone in the way...). */
	private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
	private int spotTicks;
	/** A batch for the base: at least this many of these go in, kit or not. */
	@org.jetbrains.annotations.Nullable
	private final java.util.function.Predicate<ItemStack> batch;
	private final int batchCount;
	private final String batchLabel;

	public DepositTask(Companion c) {
		this(c, null, 0, "");
	}

	/** Store {@code n} of something for the base (plus the usual extras). */
	public DepositTask(Companion c, @org.jetbrains.annotations.Nullable java.util.function.Predicate<ItemStack> batch, int n, String label) {
		super(c);
		this.batch = batch;
		this.batchCount = n;
		this.batchLabel = label;
	}

	@Override
	public String name() {
		return "deposit";
	}

	@Override
	public String describe() {
		return batch == null ? "putting things away in the chests" : "bringing " + batchCount + " " + batchLabel + " to the base";
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
		Map<Integer, Integer> toStore = Kit.toStore(inv, batch, batchCount);
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
		if (newSpot == null || !p.getWorld().getBlockState(newSpot).isReplaceable()) {
			newSpot = colony.newChestSpot(badSpots);
			spotTicks = 0;
			if (newSpot == null) {
				AiLog.log(c.name(), "deposit: no room for another chest at the base");
				return done();
			}
			c.nav().stop();
		}
		BlockPos spot = newSpot;
		// The 30 s for putting it down start once we're there, not while walking back to the base.
		boolean near = p.getBlockPos().getSquaredDistance(spot) < 10 * 10;
		if (near) {
			placeTicks++;
		}
		if (!c.actions().canReachBlock(spot)) {
			if (c.nav().state() == Navigator.State.IDLE) {
				c.nav().moveTo(Goal.reachVisible(p.getWorld(), spot, 3.5), !near);
			} else if (c.nav().state() == Navigator.State.FAILED && !near) {
				AiLog.log(c.name(), "deposit: can't get back to the base to put a chest down");
				return done();
			}
			return placeTicks > 20 * 30 ? done() : Status.RUNNING;
		}
		c.nav().stop();
		if (c.actions().placeBlock(spot, s -> s.isOf(Items.CHEST))) {
			colony.addChest(spot); // (registers the other half too if it joined a neighbour)
			AiLog.log(c.name(), "deposit: put a new chest at {}", spot.toShortString());
			placing = false;
			newSpot = null;
			chestsTried = 0;
		} else if (++spotTicks > 20 * 3) {
			// In reach but it won't go down here: try somewhere else (a few times).
			AiLog.log(c.name(), "deposit: couldn't place a chest at {} ({})", spot.toShortString(), c.actions().lastPlaceProblem());
			badSpots.add(spot);
			newSpot = null;
			if (badSpots.size() >= 3) {
				return done();
			}
		}
		return Status.RUNNING;
	}

	private Status done() {
		c.nav().stop();
		if (stored > 0) {
			AiLog.log(c.name(), "deposit: stored {} item(s)", stored);
			com.botcraft.ops.SoakStats.stored(stored);
		} else if (!Kit.toStore(c.player().getInventory()).isEmpty()) {
			return fail("there's no room in the chests"); // so we don't keep trying
		}
		return quietSuccess();
	}
}

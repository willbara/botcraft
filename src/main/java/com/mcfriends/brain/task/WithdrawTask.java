package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.colony.Colony;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/**
 * Fetch up to {@code count} of something from the base chests. If the chests turn out to have
 * less (someone else took it first), it takes what there is and ends quietly; the job that needed
 * it then gets the rest some other way.
 */
public class WithdrawTask extends Task {
	private final Predicate<ItemStack> what;
	private final String label;
	private final int count;
	private final ChestVisit visit = new ChestVisit();
	private int got;
	private int visits;

	public WithdrawTask(Companion c, Predicate<ItemStack> what, String label, int count) {
		super(c);
		this.what = what;
		this.label = label;
		this.count = Math.max(1, count);
	}

	@Override
	public String name() {
		return "withdraw";
	}

	@Override
	public String describe() {
		return "getting " + label + " from the chests (" + got + "/" + count + ")";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 90 + 20 * 60;
	}

	@Override
	protected Status onTick() {
		Colony colony = Colony.get();
		CompanionPlayer p = c.player();
		if (colony == null || !colony.hasBase() || colony.world() != p.getServerWorld() || got >= count || visits > 6) {
			return done();
		}
		if (visit.chest() == null) {
			BlockPos chest = colony.chestWith(what, p.getBlockPos());
			if (chest == null) {
				return done();
			}
			visits++;
			visit.start(chest);
		}
		Inventory chest = visit.tick(c);
		if (visit.failed()) {
			c.baseUnreachableUntil = c.now() + 20 * 60 * 3; // can't get there: fetch nothing from the base for a while
			visit.reset();
			return done();
		}
		if (chest == null) {
			return Status.RUNNING;
		}
		for (int i = 0; i < chest.size() && got < count; i++) {
			ItemStack s = chest.getStack(i);
			if (s.isEmpty() || !what.test(s)) {
				continue;
			}
			ItemStack take = s.split(Math.min(s.getCount(), count - got));
			int before = take.getCount();
			p.getInventory().insertStack(take);
			got += before - take.getCount();
			if (!take.isEmpty()) {
				s.increment(take.getCount()); // our inventory is full: leave the rest
				break;
			}
		}
		chest.markDirty();
		colony.touched();
		c.perception().invalidateInventory();
		visit.reset();
		return Status.RUNNING;
	}

	private Status done() {
		c.nav().stop();
		AiLog.log(c.name(), "withdraw: took {} {} from the chests", got, label);
		return quietSuccess();
	}
}

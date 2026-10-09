package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Goal;
import com.mcfriends.nav.Navigator;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/** Walk to the owner and toss them items ("bring me 10 logs"). */
public class DeliverTask extends Task {
	private final Predicate<ItemStack> what;
	private final String label;
	private final int count;
	private BlockPos goalCenter = BlockPos.ORIGIN;
	private int given = -1;
	private int wait;

	@org.jetbrains.annotations.Nullable
	private final String recipient;

	public DeliverTask(Companion c, Predicate<ItemStack> what, String label, int count) {
		this(c, what, label, count, null);
	}

	/** @param recipient a player/companion name, or null for the owner */
	public DeliverTask(Companion c, Predicate<ItemStack> what, String label, int count, @org.jetbrains.annotations.Nullable String recipient) {
		super(c);
		this.what = what;
		this.label = label;
		this.count = count;
		this.recipient = recipient;
	}

	public String recipientName() {
		return recipient == null ? "you" : recipient;
	}

	@Override
	public String name() {
		return "deliver";
	}

	@Override
	public String describe() {
		return "bringing " + recipientName() + " " + label;
	}

	public int given() {
		return Math.max(0, given);
	}

	public String label() {
		return label;
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		ServerPlayerEntity owner = recipient != null ? com.mcfriends.chat.ChatRouter.findByName(c, recipient) : c.perception().owner();
		if (owner == null) {
			return fail(recipient != null ? "I can't find " + recipient : "I can't find you");
		}
		if (given >= 0) {
			// Give the throw a moment, then we're done.
			return ++wait > 10 ? Status.SUCCESS : Status.RUNNING;
		}
		if (Inventories.count(p.getInventory(), what) == 0) {
			return fail("I don't have any " + label);
		}
		double d = owner.getWorld() == p.getWorld() ? owner.distanceTo(p) : Double.POSITIVE_INFINITY;
		// Close enough to toss: in sight, or right next to them (a ledge can block the eye line), or
		// as close as the path gets us.
		boolean arrived = c.nav().state() == Navigator.State.ARRIVED;
		if (d <= 4.5 && (p.canSee(owner) || d <= 2.5 || arrived)) {
			c.nav().stop();
			given = c.actions().giveItems(owner, what, count);
			c.perception().invalidateInventory();
			return Status.RUNNING;
		}
		BlockPos op = owner.getBlockPos();
		if (op.getSquaredDistance(goalCenter) > 4 || !c.nav().isBusy()) {
			if (c.nav().state() == Navigator.State.FAILED && op.getSquaredDistance(goalCenter) <= 4) {
				return fail("I can't get to you");
			}
			goalCenter = op;
			c.nav().moveTo(Goal.near(op, 2.0), d > 8);
		}
		return Status.RUNNING;
	}
}

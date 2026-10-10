package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.entity.CompanionPlayer;
import org.jetbrains.annotations.Nullable;

/** Eat the best food we carry, a few items in a row if still hungry. */
public class EatTask extends Task {
	private static final int MAX_ITEMS = 4;
	private final Glances glances = new Glances();
	private int eaten;
	private int startFood;
	private boolean using;
	private int retry;

	private EatTask(Companion c) {
		super(c);
	}

	@Nullable
	public static EatTask create(Companion c) {
		CompanionPlayer p = c.player();
		boolean starving = p.getHungerManager().getFoodLevel() <= 6;
		return Inventories.bestFood(p.getInventory(), starving) >= 0 ? new EatTask(c) : null;
	}

	@Override
	public String name() {
		return "eat";
	}

	@Override
	public String describe() {
		return "having a snack";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 20;
	}

	@Override
	protected void onStart(boolean resumed) {
		using = false;
		startFood = c.player().getHungerManager().getFoodLevel();
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		glances.tick(c, c.perception().owner());
		if (using) {
			if (c.actions().isUsingItem()) {
				return Status.RUNNING; // chewing
			}
			using = false;
			eaten++;
			c.perception().invalidateInventory();
		}
		int food = p.getHungerManager().getFoodLevel();
		boolean hurt = p.getHealth() < p.getMaxHealth() * 0.5F;
		if (!p.canConsume(false) || eaten >= MAX_ITEMS || (food >= 18 && !hurt) || (eaten > 0 && food >= 14 && !hurt)) {
			return eaten > 0 || food > startFood ? Status.SUCCESS : fail("not hungry");
		}
		int slot = Inventories.bestFood(p.getInventory(), food <= 6);
		if (slot < 0) {
			return eaten > 0 ? Status.SUCCESS : fail("no food");
		}
		if (c.actions().startUsing(slot)) {
			using = true;
			retry = 0;
		} else if (++retry > 10) {
			return fail("couldn't eat " + p.getInventory().getStack(slot).getName().getString());
		}
		return Status.RUNNING;
	}
}

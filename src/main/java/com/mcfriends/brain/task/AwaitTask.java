package com.mcfriends.brain.task;

import com.mcfriends.Companion;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Jobs;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.chat.ChatRouter;
import com.mcfriends.entity.CompanionPlayer;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Wait for a friend to bring something we asked for, picking it up when it lands. */
public class AwaitTask extends Task {
	private final Predicate<ItemStack> what;
	private final int count;
	private final String from;
	private int start = -1;
	private final Pickup pickup = new Pickup();
	private final Glances glances = new Glances();

	public AwaitTask(Companion c, String key, int count, String from) {
		super(c);
		this.what = Jobs.deliverable(key);
		this.count = Math.max(1, count);
		this.from = from;
	}

	@Override
	public String name() {
		return "await";
	}

	@Override
	public String describe() {
		return "waiting for " + from + " to bring something";
	}

	@Override
	protected int timeoutTicks() {
		return 20 * 75;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		int have = Inventories.count(p.getInventory(), what);
		if (start < 0) {
			start = have;
		}
		if (have - start >= count) {
			c.speech().event("share.thanks", Map.of("friend", from), 20 * 30, 0.7F);
			return Status.SUCCESS;
		}
		List<ItemEntity> drops = p.getServerWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(5, 2, 5),
			e -> e.isAlive() && what.test(e.getStack()) && !pickup.ignored(e));
		drops.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
		if (!drops.isEmpty()) {
			pickup.tick(c, drops.get(0));
			return Status.RUNNING;
		}
		ServerPlayerEntity friend = ChatRouter.findByName(c, from);
		glances.tick(c, friend);
		if (ticks() > 20 * 60) {
			// Took too long: carry on (our job will gather it the hard way if it still needs to).
			return have > start ? Status.SUCCESS : fail(from + " didn't make it");
		}
		return Status.RUNNING;
	}
}

package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Jobs;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.chat.ChatRouter;
import com.botcraft.entity.CompanionPlayer;
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
	private final int start;
	private int friendGoneTicks;
	private final Pickup pickup = new Pickup();
	private final Glances glances = new Glances();

	public AwaitTask(Companion c, String key, int count, String from) {
		super(c);
		this.what = Jobs.deliverable(key);
		this.count = Math.max(1, count);
		this.from = from;
		this.start = Inventories.count(c.player().getInventory(), what);
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
		return 20 * 130;
	}

	@Override
	protected Status onTick() {
		CompanionPlayer p = c.player();
		int have = Inventories.count(p.getInventory(), what);
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
		// Keep waiting while the friend is still on their way; once they're done (or gave up), give
		// the throw a few seconds to land and then carry on either way. Never fail the plan over it:
		// the job we were waiting for just gathers the thing itself if it's still missing.
		var m = com.botcraft.CompanionManager.get();
		var friendC = m == null ? null : m.byName(from);
		boolean coming = friendC != null && friendC.brain().job() instanceof DeliverTask dt && dt.recipientName().equalsIgnoreCase(c.name());
		if (!coming && ++friendGoneTicks > 20 * 6) {
			return quietSuccess();
		}
		if (ticks() > 20 * 90) {
			return quietSuccess();
		}
		return Status.RUNNING;
	}
}

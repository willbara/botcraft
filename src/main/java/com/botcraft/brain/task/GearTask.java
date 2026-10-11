package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.brain.plan.Planner;
import com.botcraft.knowledge.Gear;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.util.AiLog;
import net.minecraft.item.Item;

/**
 * Have a piece of gear (or something at least as good in its place): done at once if we wear or
 * carry it, otherwise plan how to get it the way free mode plans its progression (chests, craft,
 * smelt, mine...) with what we have right now, and do that first. Orders like "make diamond
 * armour" queue one of these per piece, so earlier steps of the order (mining the diamonds)
 * count by the time each piece is planned.
 */
public class GearTask extends Task {
	private final Item item;

	public GearTask(Companion c, Item item) {
		super(c);
		this.item = item;
	}

	@Override
	public String name() {
		return "gear_" + ItemNames.id(item);
	}

	@Override
	public String describe() {
		return "getting " + ItemNames.display(item);
	}

	@Override
	protected Status onTick() {
		if (Gear.hasAtLeast(c.player(), item)) {
			return Status.SUCCESS;
		}
		int carried = CraftTask.inventoryCounts(c.player().getInventory()).getOrDefault(item, 0);
		var decision = Planner.acquire(c, item, carried + 1);
		if (!decision.chosen().possible() || decision.chosen().steps().isEmpty()) {
			return fail("I don't know how to get " + ItemNames.display(item) + " (" + decision.chosen().note() + ")");
		}
		AiLog.log(c.name(), "gear: {} by {} {} | also {}", ItemNames.display(item), decision.chosen().method(), decision.chosen().steps(),
			decision.considered().stream().filter(o -> o != decision.chosen())
				.map(o -> o.method() + " " + (o.possible() ? Math.round(o.cost()) + "s" : "no")).toList());
		return deferTo(decision.chosen().steps());
	}
}

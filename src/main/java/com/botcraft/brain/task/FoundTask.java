package com.botcraft.brain.task;

import com.botcraft.Companion;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.colony.Colony;
import com.botcraft.faction.Factions;
import com.botcraft.util.AiLog;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * Found the faction's base where we're standing: it becomes the colony's base (so the base map
 * is read and the zones get laid out) and every member's home. Done at once; if someone else
 * founded it first, nothing to do.
 */
public class FoundTask extends Task {
	public FoundTask(Companion c) {
		super(c);
	}

	@Override
	public String name() {
		return "found";
	}

	@Override
	public String describe() {
		return "founding our base";
	}

	@Override
	protected Status onTick() {
		Colony colony = Colony.get();
		if (colony == null) {
			return fail("there's nowhere to keep a base");
		}
		if (colony.hasBase()) {
			return quietSuccess(); // someone beat us to it
		}
		var p = c.player();
		BlockPos at = p.getBlockPos();
		String dim = c.dimension();
		long time = c.server.getOverworld().getTime();
		colony.setBase(at, dim);
		var faction = Factions.of(c);
		for (Companion o : Factions.members(faction.id)) {
			o.memory().setPlace("home", at, dim, time);
		}
		AiLog.log(c.name(), "founded the base of {} at {}", faction.id, at.toShortString());
		c.speech().event("faction.founded", Map.of("faction", faction.name), 0, 1.0F);
		return Status.SUCCESS;
	}
}

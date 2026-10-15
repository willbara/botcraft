package com.botcraft.brain;

import com.botcraft.Companion;
import com.botcraft.brain.task.EatTask;
import com.botcraft.brain.task.FightTask;
import com.botcraft.brain.task.FleeTask;
import net.minecraft.entity.mob.MobEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Creates tasks for interrupting objectives (danger, hunger, low health). */
public final class Interrupts {
	private Interrupts() {
	}

	@Nullable
	public static Task create(Companion c, Objective objective) {
		return switch (objective) {
			case EAT -> EatTask.create(c);
			case BREATHE -> new com.botcraft.brain.task.BreatheTask(c);
			case FIGHT -> {
				List<MobEntity> threats = c.perception().threats();
				if (threats.isEmpty() && c.data.defendOwner) {
					threats = c.perception().ownerThreats();
				}
				if (threats.isEmpty() && !c.perception().enemyPlayers().isEmpty()) {
					yield new FightTask(c, c.perception().enemyPlayers().get(0));
				}
				if (threats.isEmpty()) {
					var popbob = Popbob.near(c, 12);
					yield popbob == null ? null : new FightTask(c, popbob);
				}
				yield new FightTask(c, threats.get(0));
			}
			case FLEE -> new FleeTask(c, false);
			case RETREAT -> new FleeTask(c, true);
			default -> null;
		};
	}
}

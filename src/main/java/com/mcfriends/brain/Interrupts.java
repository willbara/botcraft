package com.mcfriends.brain;

import com.mcfriends.Companion;
import com.mcfriends.brain.task.EatTask;
import com.mcfriends.brain.task.FightTask;
import com.mcfriends.brain.task.FleeTask;
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
			case FIGHT -> {
				List<MobEntity> threats = c.perception().threats();
				if (threats.isEmpty() && c.data.defendOwner) {
					threats = c.perception().ownerThreats();
				}
				yield threats.isEmpty() ? null : new FightTask(c, threats.get(0));
			}
			case FLEE -> new FleeTask(c, false);
			case RETREAT -> new FleeTask(c, true);
			default -> null;
		};
	}
}

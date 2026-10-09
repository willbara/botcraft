package com.mcfriends;

import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Brain;
import com.mcfriends.brain.Drives;
import com.mcfriends.brain.Interrupts;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.brain.Objective;
import com.mcfriends.brain.task.FightTask;
import com.mcfriends.brain.task.GatherLogsTask;
import com.mcfriends.brain.task.GoToTask;
import com.mcfriends.chat.CommandTasks;
import com.mcfriends.chat.ChatRouter;
import com.mcfriends.chat.EventWatcher;
import com.mcfriends.chat.Intent;
import com.mcfriends.chat.Speech;
import net.minecraft.entity.LivingEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import com.mcfriends.entity.CompanionData;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.nav.Navigator;
import com.mcfriends.perception.Perception;
import com.mcfriends.util.AiLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime state for one companion: its persistent data, the current player entity (replaced
 * after death/rejoin) and the AI subsystems. Subsystems must always go through {@link #player()}
 * rather than caching the entity.
 *
 * <p>Per tick: perception -> brain (drives every N ticks, current task every tick) ->
 * navigator -> actions (turns intentions into player input). Physics then run during the next
 * world tick, exactly like a client-driven player.
 */
public class Companion {
	public final CompanionData data;
	public final MinecraftServer server;
	@Nullable
	private CompanionPlayer player;

	private final ActionController actions;
	private final Navigator nav;
	private final Perception perception;
	private final Brain brain;
	private final Speech speech;
	private final EventWatcher events = new EventWatcher();
	private final Random random = new Random();

	private record Heard(UUID sender, Intent intent, long due) {
	}

	private final List<Heard> heard = new ArrayList<>();
	private long now;

	/** Server tick at which a dead/kicked companion should rejoin; -1 = not scheduled. */
	long respawnAtTick = -1;
	int deadTicks;

	public Companion(MinecraftServer server, CompanionData data) {
		this.server = server;
		this.data = data;
		this.actions = new ActionController(this);
		this.nav = new Navigator(this, actions);
		this.perception = new Perception(this);
		this.brain = new Brain(this, Drives.all(), Interrupts::create);
		this.speech = new Speech(this);
	}

	@Nullable
	public CompanionPlayer player() {
		return player;
	}

	void setPlayer(@Nullable CompanionPlayer player) {
		this.player = player;
		this.deadTicks = 0;
	}

	public ActionController actions() {
		return actions;
	}

	public Navigator nav() {
		return nav;
	}

	public Perception perception() {
		return perception;
	}

	public Brain brain() {
		return brain;
	}

	public Speech speech() {
		return speech;
	}

	public boolean isOnline() {
		return player != null && !player.isDisconnected();
	}

	public String name() {
		return data.name;
	}

	@Nullable
	public ServerPlayerEntity owner() {
		return data.owner == null ? null : server.getPlayerManager().getPlayer(data.owner);
	}

	/** Called every server tick while the companion is online and alive. */
	void tick(long serverTick) {
		now = serverTick;
		perception.tick(serverTick);
		processHeard();
		if (serverTick % 40 == 0 && Inventories.equipBestArmor(player)) {
			AiLog.log(name(), "put on better armour");
		}
		brain.tick(serverTick);
		nav.tick();
		actions.tick();
		events.tick(this, serverTick);
		speech.tick(serverTick);
	}

	/** Queue a chat request; it's acted on after a short, human-ish reaction delay. */
	public void hear(net.minecraft.server.network.ServerPlayerEntity sender, Intent intent) {
		heard.add(new Heard(sender.getUuid(), intent, now + 6 + random.nextInt(14)));
	}

	private void processHeard() {
		for (int i = 0; i < heard.size(); i++) {
			Heard h = heard.get(i);
			if (now >= h.due()) {
				heard.remove(i--);
				ServerPlayerEntity sender = server.getPlayerManager().getPlayer(h.sender());
				if (sender != null) {
					ChatRouter.respond(this, sender, h.intent());
				}
			}
		}
	}

	/** Called after the player joined: reason is "new", "summoned", "restored" or "respawned". */
	void onSpawned(String reason) {
		nav.stop();
		actions.reset();
		if (data.job != null && !brain.hasJob()) {
			CommandTasks.restoreJob(this);
		}
		brain.reconsider();
		AiLog.log(name(), "spawned ({}), personality {}", reason, brain.personality().summary());
		speech.tick(now);
		switch (reason) {
			case "new" -> speech.event("event.hello", Map.of(), 0, 1.0F);
			case "respawned", "summoned" -> speech.event("event.back", Map.of(), 0, 0.7F);
			default -> {
			}
		}
	}

	void onDeath() {
		AiLog.log(name(), "died");
		nav.stop();
	}

	void onRemoved() {
		nav.stop();
	}

	/** A fight task killed a mob. */
	public void onKill(LivingEntity victim) {
		AiLog.log(name(), "killed {}", victim.getType().getUntranslatedName());
		speech.event("event.kill", Map.of("mob", mobName(victim)), 15 * 20, 0.35F);
	}

	/** Brain callback when an interrupt (fight, flee, eat...) starts. */
	public void onInterruptStarted(Objective objective, Task task) {
		if (objective == Objective.FIGHT && task instanceof FightTask) {
			LivingEntity t = perception.nearestThreat();
			if (t != null) {
				speech.event("event.fight", Map.of("mob", mobName(t)), 40 * 20, 0.3F);
			}
		} else if (objective == Objective.FLEE && perception.nearestCreeper(10) == null) {
			speech.event("event.flee", Map.of(), 40 * 20, 0.3F);
		}
	}

	/** Brain callback when a task ends. */
	public void onTaskFinished(Task task, Status status, boolean interrupt) {
		if (task instanceof GatherLogsTask g) {
			if (status == Status.SUCCESS) {
				speech.event("event.task_done", Map.of("count", Integer.toString(g.gathered()), "item", "logs"), 0, 1.0F);
			} else {
				speech.event("event.task_failed", Map.of("reason", task.failReason()), 0, 1.0F);
			}
		} else if (task instanceof GoToTask) {
			if (status == Status.SUCCESS) {
				speech.event("event.arrived", Map.of(), 10 * 20, 0.4F);
			} else {
				speech.event("event.task_failed", Map.of("reason", task.failReason()), 0, 0.8F);
			}
		}
	}

	private static String mobName(LivingEntity e) {
		return e.getDisplayName().getString().toLowerCase(java.util.Locale.ROOT);
	}

	public String describeActivity() {
		if (player == null) {
			return "offline";
		}
		return brain.describe();
	}
}

package com.mcfriends;

import com.mcfriends.action.ActionController;
import com.mcfriends.action.Inventories;
import com.mcfriends.brain.Brain;
import com.mcfriends.brain.Drives;
import com.mcfriends.brain.Initiative;
import com.mcfriends.brain.Interrupts;
import com.mcfriends.brain.JobQueue;
import com.mcfriends.brain.Jobs;
import com.mcfriends.brain.Objective;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.brain.task.CraftTask;
import com.mcfriends.brain.task.DeliverTask;
import com.mcfriends.brain.task.FightTask;
import com.mcfriends.brain.task.GatherLogsTask;
import com.mcfriends.brain.task.GoToTask;
import com.mcfriends.brain.task.MineTask;
import com.mcfriends.brain.task.SleepTask;
import com.mcfriends.chat.ChatRouter;
import com.mcfriends.chat.EventWatcher;
import com.mcfriends.chat.Intent;
import com.mcfriends.chat.Speech;
import com.mcfriends.entity.CompanionData;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.knowledge.ItemNames;
import com.mcfriends.memory.Memory;
import com.mcfriends.nav.Navigator;
import com.mcfriends.perception.Perception;
import com.mcfriends.util.AiLog;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Runtime state for one companion: its persistent data and memory, the current player entity
 * (replaced after death/rejoin) and the AI subsystems. Subsystems must always go through
 * {@link #player()} rather than caching the entity.
 *
 * <p>Per tick: perception -> heard chat -> job queue -> initiative -> brain (drives every N
 * ticks, current task every tick) -> navigator -> actions (turns intentions into player input)
 * -> remarks -> speech. Physics then run during the next world tick, like a client-driven player.
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
	private final JobQueue jobs;
	private final Memory memory;
	private final Initiative initiative = new Initiative();
	private final EventWatcher events = new EventWatcher();
	private final Random random = new Random();

	private record Heard(UUID sender, List<Intent> intents, boolean append, long due) {
	}

	private final List<Heard> heard = new ArrayList<>();
	private long now;
	/** Don't try to go to bed again before this tick (after failing to find one). */
	public long sleepRetryAfter;

	/** Server tick at which a dead/kicked companion should rejoin; -1 = not scheduled. */
	long respawnAtTick = -1;
	int deadTicks;

	public Companion(MinecraftServer server, CompanionData data) {
		this.server = server;
		this.data = data;
		this.memory = Memory.load(server, data.name);
		this.actions = new ActionController(this);
		this.nav = new Navigator(this, actions);
		this.perception = new Perception(this);
		this.brain = new Brain(this, Drives.all(), Interrupts::create);
		this.speech = new Speech(this);
		this.jobs = new JobQueue(this);
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

	public JobQueue jobs() {
		return jobs;
	}

	public Memory memory() {
		return memory;
	}

	/** Current server tick (as of this companion's last tick). */
	public long now() {
		return now;
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

	public String dimension() {
		return player == null ? "minecraft:overworld" : player.getWorld().getRegistryKey().getValue().toString();
	}

	/** Called every server tick while the companion is online and alive. */
	void tick(long serverTick) {
		now = serverTick;
		perception.tick(serverTick);
		processHeard();
		if (serverTick % 40 == 0 && Inventories.equipBestArmor(player)) {
			AiLog.log(name(), "put on better armour");
		}
		jobs.tick();
		initiative.tick(this, serverTick);
		brain.tick(serverTick);
		nav.tick();
		actions.tick();
		events.tick(this, serverTick);
		speech.tick(serverTick);
	}

	/** Queue a chat request; it's acted on after a short, human-ish reaction delay. */
	public void hear(ServerPlayerEntity sender, List<Intent> intents, boolean append) {
		heard.add(new Heard(sender.getUuid(), intents, append, now + 6 + random.nextInt(14)));
		memory.person(sender.getGameProfile().getName(), server.getOverworld().getTime());
	}

	public void hear(ServerPlayerEntity sender, Intent intent) {
		hear(sender, List.of(intent), false);
	}

	private void processHeard() {
		for (int i = 0; i < heard.size(); i++) {
			Heard h = heard.get(i);
			if (now >= h.due()) {
				heard.remove(i--);
				ServerPlayerEntity sender = server.getPlayerManager().getPlayer(h.sender());
				if (sender != null) {
					ChatRouter.respond(this, sender, h.intents(), h.append());
				}
			}
		}
	}

	/** Called after the player joined: "new", "summoned", "restored", "respawned" or "reskinned". */
	void onSpawned(String reason) {
		nav.stop();
		actions.reset();
		if (data.job != null && !brain.hasJob()) {
			// The job we were doing when we left (with its remaining count) goes first again.
			jobs.pushFront(List.of(data.job));
			data.job = null;
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
		memory.count("deaths", 1);
		if (player != null) {
			memory.setPlace("last_death", player.getBlockPos(), dimension(), player.getWorld().getTime());
		}
	}

	void onRemoved() {
		nav.stop();
	}

	/** Owner (re)joined the server. */
	public void onOwnerJoined(ServerPlayerEntity owner) {
		Memory.Person p = memory.knownPerson(owner.getGameProfile().getName());
		long time = server.getOverworld().getTime();
		boolean longTime = p != null && time - p.lastSeen > 24000;
		memory.person(owner.getGameProfile().getName(), time);
		speech.event(longTime ? "event.owner_back_long" : "event.owner_back", Map.of("player", owner.getGameProfile().getName()), 20 * 60, 0.9F);
	}

	/** A fight task killed a mob. */
	public void onKill(LivingEntity victim) {
		AiLog.log(name(), "killed {}", victim.getType().getUntranslatedName());
		memory.count("killed_" + victim.getType().getUntranslatedName(), 1);
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
			boolean armed = perception.inventory().bestWeapon() >= Drives.ARMED_DAMAGE;
			speech.event(armed ? "event.flee" : "event.flee_unarmed", Map.of(), 40 * 20, 0.4F);
		}
	}

	/** Brain callback when a task ends. */
	public void onTaskFinished(Task task, Status status, boolean interrupt) {
		if (task instanceof SleepTask && task.spec() == null) {
			if (status == Status.FAILED) {
				sleepRetryAfter = now + 20 * 60;
				speech.event("event.no_bed", Map.of("reason", task.failReason()), 20 * 120, 0.8F);
			}
			return;
		}
		if (task.spec() == null || task.silent()) {
			return;
		}
		if (status == Status.FAILED) {
			int dropped = jobs.abandonRest();
			speech.event(dropped > 0 ? "event.plan_failed" : "event.task_failed",
				Map.of("reason", task.failReason(), "job", Jobs.describe(task.spec())), 0, 1.0F);
			return;
		}
		String result = resultPhrase(task);
		if (!jobs.pending().isEmpty()) {
			speech.event("event.step_done", Map.of("result", result, "next", Jobs.describe(jobs.pending().get(0))), 0, 0.6F);
		} else if (task instanceof GoToTask) {
			speech.event("event.arrived", Map.of(), 10 * 20, 0.4F);
		} else if (!(task instanceof SleepTask)) {
			speech.event("event.task_done", Map.of("result", result), 0, 1.0F);
		}
	}

	private static String resultPhrase(Task task) {
		if (task instanceof GatherLogsTask g) {
			return "got " + g.gathered() + " logs";
		}
		if (task instanceof MineTask m) {
			return "got " + m.gathered() + " " + m.kind().noun();
		}
		if (task instanceof CraftTask cr) {
			return "made " + (cr.made() > 1 ? cr.made() + " " : "a ") + ItemNames.display(cr.item());
		}
		if (task instanceof DeliverTask d) {
			return "gave you " + d.given() + " " + d.label();
		}
		return "done";
	}

	private static String mobName(LivingEntity e) {
		return e.getDisplayName().getString().toLowerCase(Locale.ROOT);
	}

	public String describeActivity() {
		if (player == null) {
			return "offline";
		}
		String now = brain.describe();
		if (!jobs.pending().isEmpty()) {
			now += ", then " + Jobs.describe(jobs.pending().get(0));
			if (jobs.pending().size() > 1) {
				now += " (+" + (jobs.pending().size() - 1) + " more)";
			}
		}
		return now;
	}
}

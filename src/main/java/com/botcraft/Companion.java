package com.botcraft;

import com.botcraft.action.ActionController;
import com.botcraft.action.Inventories;
import com.botcraft.brain.Brain;
import com.botcraft.brain.Drives;
import com.botcraft.brain.Life;
import com.botcraft.brain.Interrupts;
import com.botcraft.brain.JobQueue;
import com.botcraft.brain.Jobs;
import com.botcraft.brain.Objective;
import com.botcraft.brain.Status;
import com.botcraft.brain.Task;
import com.botcraft.brain.task.CraftTask;
import com.botcraft.brain.task.DeliverTask;
import com.botcraft.brain.task.FightTask;
import com.botcraft.brain.task.GatherLogsTask;
import com.botcraft.brain.task.GoToTask;
import com.botcraft.brain.task.MineTask;
import com.botcraft.brain.task.SleepTask;
import com.botcraft.chat.ChatRouter;
import com.botcraft.chat.EventWatcher;
import com.botcraft.chat.Intent;
import com.botcraft.chat.Speech;
import com.botcraft.entity.CompanionData;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.knowledge.ItemNames;
import com.botcraft.memory.Memory;
import net.minecraft.util.math.BlockPos;
import com.botcraft.nav.Navigator;
import com.botcraft.perception.Perception;
import com.botcraft.util.AiLog;
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
	private final Life life = new Life();
	private final com.botcraft.brain.Leftovers leftovers = new com.botcraft.brain.Leftovers(this);
	private final com.botcraft.brain.Watchdog watchdog = new com.botcraft.brain.Watchdog();
	private final com.botcraft.brain.Failsafe failsafe = new com.botcraft.brain.Failsafe();
	private final com.botcraft.brain.Lighting lighting = new com.botcraft.brain.Lighting();
	private final com.botcraft.brain.Popbob popbob = new com.botcraft.brain.Popbob();
	/** Recent deaths (where, when): don't keep walking back into the same danger. */
	private final java.util.ArrayDeque<long[]> recentDeaths = new java.util.ArrayDeque<>();
	private boolean lastDeathUnrecoverable;
	/** Couldn't get to the base chests recently: get things another way until then. */
	public long baseUnreachableUntil;
	private final EventWatcher events = new EventWatcher();
	private final Random random = new Random();
	private final com.botcraft.brain.Mood mood = new com.botcraft.brain.Mood();
	private final com.botcraft.brain.Relations relations = new com.botcraft.brain.Relations(this);

	private record Heard(UUID sender, List<Intent> intents, boolean append, long due) {
	}

	private final List<Heard> heard = new ArrayList<>();
	private long now;
	/** Don't try to go to bed again before this tick (after failing to find one). */
	public long sleepRetryAfter;

	/** Times it was dropped right after joining and sent back in (see CompanionManager). */
	int rejoinTries;
	/** The next rejoin is a retry of a failed join (back where it was), not a respawn after dying. */
	boolean rejoinPending;
	/** Server tick it last (re)joined: fresh from a respawn it stays out of fights for a minute. */
	public long spawnedAt = -1_000_000L;
	/** Killed by a player or another faction's companion last time (no going back for the things). */
	private boolean lastKilledInFight;

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

	public Life life() {
		return life;
	}

	/** How it feels about the people it knows. */
	public com.botcraft.brain.Relations relations() {
		return relations;
	}

	/** How it feels right now (fear, joy, frustration...). */
	public com.botcraft.brain.Mood mood() {
		mood.update(now);
		return mood;
	}

	/** What our own work dropped and we still have to pick up. */
	public com.botcraft.brain.Leftovers leftovers() {
		return leftovers;
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
		if (serverTick % 40 == 20 && !player.isUsingItem() && Inventories.equipShield(player)) {
			AiLog.log(name(), "took my shield in my off hand");
		}
		if (serverTick % 100 == 0) {
			headBackIfLost("idle");
		}
		if (serverTick % 40 == 0 && watchdog.check(this, serverTick)) {
			onStalled();
		}
		if (serverTick % 20 == 7) {
			lighting.tick(this, serverTick);
		}
		if (serverTick % 5 == 0) {
			com.botcraft.brain.Blackboard.publish(this);
		}
		if (serverTick % 20 == 3) {
			feel();
		}
		if (serverTick % 20 == 0) {
			Memory.Place home = memory.place("home");
			String why = failsafe.check(this, serverTick, home != null && dimension().equals(home.dim) ? home.pos() : null);
			if (why != null) {
				rescueHome(why);
			}
		}
		if (com.botcraft.brain.Popbob.is(this)) {
			popbob.tick(this, serverTick); // the easter egg: no jobs, no plans, just trouble
		} else {
			jobs.tick();
			life.tick(this, serverTick);
			brain.tick(serverTick);
		}
		nav.tick();
		actions.tick();
		events.tick(this, serverTick);
		speech.tick(serverTick);
	}

	/** Once a second: tiredness from working, rest from sleeping, fear from what's around. */
	private void feel() {
		mood.update(now);
		if (player.isSleeping()) {
			mood.reset(com.botcraft.brain.Mood.Kind.FATIGUE);
			return;
		}
		if (brain.hasJob()) {
			mood.bump(com.botcraft.brain.Mood.Kind.FATIGUE, 0.0004F, now);
		}
		int threats = perception.threats().size();
		if (threats >= 3) {
			mood.bump(com.botcraft.brain.Mood.Kind.FEAR, 0.03F * (1.0F - brain.personality().bravery()), now);
		}
	}

	/** Took damage: fear, and anger at whoever did it (see faction.Standing for what players get). */
	public void onDamaged(float amount, @Nullable net.minecraft.entity.Entity attacker) {
		if (player == null) {
			return;
		}
		float share = amount / Math.max(1.0F, player.getMaxHealth());
		mood.bump(com.botcraft.brain.Mood.Kind.FEAR, share * (1.5F - brain.personality().bravery()), now);
		if (attacker instanceof ServerPlayerEntity) {
			mood.bump(com.botcraft.brain.Mood.Kind.ANGER, 0.2F + share, now);
		}
	}

	private long nextHeadBack;

	/**
	 * Don't stay lost: with nothing to do (a job failed, or simply ran out of things to do) and
	 * nobody to follow, head back home rather than idle wherever we ended up (a cave, a far-off
	 * wood). In follow mode with the owner around, following already brings us back (with a
	 * teleport if we're far away or stuck); "stay" means stay.
	 */
	private void headBackIfLost(String why) {
		if (player == null || now < nextHeadBack || !"follow".equals(data.mode) || !jobs.isIdle()) {
			return;
		}
		ServerPlayerEntity o = perception.owner();
		if ("follow".equals(data.mode) && o != null && o.getWorld() == player.getWorld()) {
			return;
		}
		Memory.Place home = memory.place("home");
		if (home == null || !dimension().equals(home.dim) || player.getBlockPos().getSquaredDistance(home.pos()) < 24 * 24) {
			return;
		}
		nextHeadBack = now + 20 * 30;
		AiLog.log(name(), "nothing to do and nobody to follow ({}): heading home", why);
		jobs.append(List.of("home"));
		speech.event("event.heading_home", Map.of(), 20 * 60, 0.7F);
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
		CompanionManager mgr = CompanionManager.get();
		spawnedAt = mgr == null ? now : mgr.tickCount();
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
			case "new" -> {
				speech.event("event.hello", Map.of(), 0, 1.0F);
				CompanionManager m = CompanionManager.get();
				if (m != null) {
					com.botcraft.chat.Social.onSpawn(this, m.all());
				}
			}
			case "respawned" -> {
				speech.event("event.back", Map.of(), 0, 0.7F);
				planRecovery();
			}
			case "summoned" -> speech.event("event.back", Map.of(), 0, 0.7F);
			default -> {
			}
		}
	}

	/**
	 * After dying: go back for what we dropped, like a player would, if it's still there (items
	 * last 5 minutes) and it's sensible: not burned in lava, not at the bottom of the water we
	 * drowned in, not somewhere that has already killed us twice.
	 */
	private void planRecovery() {
		// A trip back from an earlier death is out of date: it's decided afresh below. (Left in
		// the queue, it carried on to the place that had killed us twice, and killed us again.)
		int stale = jobs.drop(spec -> spec.contains(RECOVERY));
		if (stale > 0) {
			AiLog.log(name(), "dropped {} earlier trip(s) back for my things", stale);
		}
		Memory.Place death = memory.place("last_death");
		if (death == null || player == null || !dimension().equals(death.dim)
			|| player.getWorld().getGameRules().getBoolean(net.minecraft.world.GameRules.KEEP_INVENTORY)) {
			return;
		}
		long age = player.getWorld().getTime() - death.time;
		if (age > 20 * 60 * 4 || lastDeathUnrecoverable || lastKilledInFight) {
			AiLog.log(name(), "not going back for my things ({})", lastDeathUnrecoverable ? "nothing to recover there"
				: lastKilledInFight ? "whoever killed me is still there" : "too long ago");
			return;
		}
		int nearby = 0;
		for (long[] d : recentDeaths) {
			if (BlockPos.fromLong(d[0]).getSquaredDistance(death.pos()) < 16 * 16 && now - d[1] < 20 * 60 * 10) {
				nearby++;
			}
		}
		if (nearby >= 2) {
			AiLog.log(name(), "not going back for my things: that place has killed me {} times", nearby);
			return;
		}
		// Items last 5 minutes from the death; keep looking for them that long.
		leftovers.expect(death.pos(), 10.0, Math.max(20 * 30, 20 * 60 * 5 - age));
		BlockPos at = death.pos();
		jobs.pushFront(List.of("goto " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + RECOVERY));
		AiLog.log(name(), "going back for my things at {}", at.toShortString());
		speech.event("event.recover", Map.of(), 0, 0.8F);
	}

	/** The label of the trip back to where we died (it's in the job spec). */
	private static final String RECOVERY = "back for my things";

	/** The job made no progress for minutes: give up on it like any failure. */
	private void onStalled() {
		String job = brain.jobName();
		String what = brain.job() != null ? brain.job().describe() : job;
		AiLog.log(name(), "watchdog: no progress on {} for {} min, giving up on it", what, com.botcraft.brain.Watchdog.STALL_TICKS / 1200);
		memory.count("stalls", 1);
		com.botcraft.ops.SoakStats.stall(name(), job);
		nav.stop();
		brain.setJob(null);
		jobs.abandonRest();
		life.failed(now);
		speech.event("event.task_failed", Map.of("reason", "I got stuck", "job", what), 0, 1.0F);
		headBackIfLost("stalled");
		if (failsafe.stalled(now) && "free".equals(data.mode)) {
			rescueHome("jobs keep stalling");
		}
	}

	/**
	 * Free mode's failsafe: something went wrong (wedged in blocks, stuck for minutes, jobs
	 * stalling one after another), so put the companion back home. Never to the owner.
	 */
	public void rescueHome(String why) {
		CompanionManager m = CompanionManager.get();
		if (player == null || m == null) {
			return;
		}
		var at = m.homeArrival(this);
		AiLog.log(name(), "failsafe: {} at {}, back home to {}", why, player.getBlockPos().toShortString(),
			BlockPos.ofFloored(at.pos()).toShortString());
		memory.count("rescues", 1);
		com.botcraft.ops.SoakStats.rescue(name(), why);
		nav.stop();
		brain.setJob(null);
		jobs.clear();
		player.teleport(at.world(), at.pos().x, at.pos().y, at.pos().z, player.getYaw(), player.getPitch());
		player.fallDistance = 0;
		player.setAir(player.getMaxAir());
		speech.event("event.rescued", Map.of(), 20 * 60, 0.8F);
	}

	/** How many things a finished task got us (for learning how well each method works). */
	private static int unitsFrom(Task task) {
		if (task instanceof MineTask m) {
			return m.gathered();
		}
		if (task instanceof GatherLogsTask g) {
			return g.gathered();
		}
		if (task instanceof com.botcraft.brain.task.HuntTask h) {
			return h.kills();
		}
		if (task instanceof com.botcraft.brain.task.SmeltTask sm) {
			return sm.made();
		}
		if (task instanceof CraftTask cr) {
			return cr.made();
		}
		return 0;
	}

	/** Drop the current job (and what was queued after it) as failed, like a task that failed itself. */
	private void giveUpJob(String reason) {
		Task job = brain.job();
		String what = job != null ? job.describe() : brain.jobName();
		AiLog.log(name(), "giving up on {}: {}", what, reason);
		if (job != null && job.spec() != null) {
			com.botcraft.ops.SoakStats.job(name(), job.name(), false, reason);
			com.botcraft.brain.plan.MethodStats.record(this, com.botcraft.brain.plan.MethodStats.key(job.spec()), false, 0, job.ticks());
		}
		nav.stop();
		brain.setJob(null);
		jobs.abandonRest();
		life.failed(now);
		mood.bump(com.botcraft.brain.Mood.Kind.FRUSTRATION, 0.25F, now);
		speech.event("event.task_failed", Map.of("reason", reason, "job", what), 0, 1.0F);
	}

	void onDeath() {
		AiLog.log(name(), "died");
		nav.stop();
		memory.count("deaths", 1);
		mood.bump(com.botcraft.brain.Mood.Kind.FRUSTRATION, 0.3F, now);
		mood.reset(com.botcraft.brain.Mood.Kind.FEAR);
		com.botcraft.brain.Personality.drift(data, "bravery", -0.02F); // once bitten, twice shy
		// A goal that got us killed is put aside for a while, like one that failed; and the way we
		// were going about it is remembered as dangerous.
		life.failed(now);
		if (data.job != null) {
			com.botcraft.brain.plan.MethodStats.death(this, com.botcraft.brain.plan.MethodStats.key(data.job));
		}
		var source = player == null ? null : player.getRecentDamageSource();
		String cause = source == null ? "unknown" : source.getName();
		com.botcraft.faction.Incidents.companionKilled(this, source);
		lastKilledInFight = source != null && source.getAttacker() instanceof ServerPlayerEntity;
		lastDeathUnrecoverable = source != null && (source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_FIRE)
			|| source.isOf(net.minecraft.entity.damage.DamageTypes.LAVA) || source.isOf(net.minecraft.entity.damage.DamageTypes.DROWN)
			|| source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD));
		com.botcraft.ops.SoakStats.death(name(), player == null || source == null ? cause : source.getDeathMessage(player).getString());
		if (player != null) {
			BlockPos at = player.getBlockPos();
			recentDeaths.addLast(new long[]{at.asLong(), now});
			while (recentDeaths.size() > 5) {
				recentDeaths.removeFirst();
			}
		}
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			com.botcraft.chat.Social.onDeath(this, m.all());
		}
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
		mood.bump(com.botcraft.brain.Mood.Kind.JOY, 0.12F, now);
		mood.bump(com.botcraft.brain.Mood.Kind.FEAR, -0.2F, now);
		com.botcraft.brain.Personality.drift(data, "aggression", 0.004F);
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			com.botcraft.chat.Social.onKill(this, victim, m.all());
		}
		speech.event("event.kill", Map.of("mob", mobName(victim)), 15 * 20, 0.35F);
	}

	/** The job that keeps sending us up for air, and how many times it has. */
	@org.jetbrains.annotations.Nullable
	private Task breathJob;
	private int breathCount;

	/** Brain callback when an interrupt (fight, flee, eat...) starts. */
	public void onInterruptStarted(Objective objective, Task task) {
		com.botcraft.ops.SoakStats.interrupt(objective.name());
		if (objective == Objective.BREATHE && brain.job() != null && player != null && !player.isInLava()) {
			// Up for air, back down, up for air...: whatever the job is after is underwater.
			if (brain.job() == breathJob) {
				breathCount++;
			} else {
				breathJob = brain.job();
				breathCount = 1;
			}
			if (breathCount >= 3) {
				breathJob = null;
				giveUpJob("there's too much water here");
				if (failsafe.waterTrapped(now) && "free".equals(data.mode)) {
					rescueHome("keeps getting caught in water"); // the next job would lead straight back in
				}
			}
		}
		if (objective == Objective.FIGHT && task instanceof FightTask) {
			net.minecraft.entity.mob.MobEntity t = perception.nearestThreat();
			if (t != null && t.getTarget() instanceof CompanionPlayer friend && friend != player) {
				speech.event("social.help", Map.of("friend", friend.getGameProfile().getName(), "mob", mobName(t)), 20 * 30, 0.7F);
			} else if (t != null) {
				speech.event("event.fight", Map.of("mob", mobName(t)), 40 * 20, 0.3F);
			}
		} else if (objective == Objective.FLEE && perception.nearestCreeper(10) == null) {
			boolean armed = perception.inventory().bestWeapon() >= Drives.ARMED_DAMAGE;
			speech.event(armed ? "event.flee" : "event.flee_unarmed", Map.of(), 40 * 20, 0.4F);
		}
	}

	/** Brain callback when a task ends. */
	public void onTaskFinished(Task task, Status status, boolean interrupt) {
		if (task instanceof SleepTask && status == Status.SUCCESS) {
			mood.reset(com.botcraft.brain.Mood.Kind.FATIGUE);
		}
		if (task instanceof SleepTask && task.spec() == null) {
			if (status == Status.FAILED) {
				sleepRetryAfter = now + 20 * 60;
				speech.event("event.no_bed", Map.of("reason", task.failReason()), 20 * 120, 0.8F);
			}
			return;
		}
		if (task.spec() == null || task.silent()) {
			if (task.spec() != null && status == Status.SUCCESS && jobs.pending().isEmpty()) {
				life.succeeded(now); // a quiet success still finishes a life goal (or board request)
			}
			return;
		}
		if (task.spec() != null && !task.silent()) {
			com.botcraft.ops.SoakStats.job(name(), task.name(), status == Status.SUCCESS, status == Status.FAILED ? task.failReason() : "");
			com.botcraft.brain.plan.MethodStats.record(this, com.botcraft.brain.plan.MethodStats.key(task.spec()), status == Status.SUCCESS,
				unitsFrom(task), task.ticks());
		}
		if (status == Status.FAILED && Jobs.optional(task.spec())) {
			// Tool prep didn't work out: get on with the actual job.
			com.botcraft.brain.task.ToolPrep.failed(this, task.spec());
			jobs.skipOptional();
			return;
		}
		if (status == Status.FAILED && (task.spec().startsWith("deliver ") || task.spec().startsWith("await ")) && !jobs.pending().isEmpty()) {
			// A favour for a friend (or waiting on one) that didn't work out: that's no reason to
			// drop what we were doing ourselves.
			speech.event("event.task_failed", Map.of("reason", task.failReason(), "job", Jobs.describe(task.spec())), 0, 1.0F);
			return;
		}
		if (status == Status.FAILED) {
			life.failed(now);
			mood.bump(com.botcraft.brain.Mood.Kind.FRUSTRATION, 0.25F, now);
			int dropped = jobs.abandonRest();
			speech.event(dropped > 0 ? "event.plan_failed" : "event.task_failed",
				Map.of("reason", task.failReason(), "job", Jobs.describe(task.spec())), 0, 1.0F);
			if (task instanceof GoToTask g && g.label().equals("home")) {
				nextHeadBack = now + 20 * 60; // couldn't get home: don't keep trying straight away
			} else {
				headBackIfLost("failed");
			}
			return;
		}
		if (jobs.pending().isEmpty()) {
			life.succeeded(now);
		}
		mood.bump(com.botcraft.brain.Mood.Kind.JOY, 0.08F, now);
		mood.bump(com.botcraft.brain.Mood.Kind.FRUSTRATION, -0.1F, now);
		String result = resultPhrase(task);
		if (!jobs.pending().isEmpty()) {
			speech.event("event.step_done", Map.of("result", result, "next", Jobs.describe(jobs.pending().get(0))), 0, 0.6F);
		} else if (task instanceof GoToTask g && !g.label().equals("exploring") && !g.label().equals("home")) {
			speech.event("event.arrived", Map.of(), 10 * 20, 0.4F);
		} else if (task instanceof GoToTask) {
			// Our own errands (exploring, heading home): no need to announce arriving.
		} else if (!(task instanceof SleepTask)) {
			speech.event("event.task_done", Map.of("result", result), 0, 1.0F);
		}
	}

	private static String resultPhrase(Task task) {
		if (task instanceof GatherLogsTask g) {
			return "got " + g.gathered() + (g.gathered() == 1 ? " log" : " logs");
		}
		if (task instanceof MineTask m) {
			return "got " + m.gathered() + " " + m.kind().noun();
		}
		if (task instanceof CraftTask cr) {
			String what = ItemNames.display(cr.item());
			return "made " + (cr.made() > 1 ? cr.made() + " " + ItemNames.plural(what, cr.made()) : (what.matches("^[aeiou].*") ? "an " : "a ") + what);
		}
		if (task instanceof com.botcraft.brain.task.SmeltTask sm) {
			return (sm.output() == null ? "cooked " : "smelted ") + sm.made() + " " + (sm.output() == null ? "food" : ItemNames.display(sm.output()));
		}
		if (task instanceof com.botcraft.brain.task.HuntTask h) {
			return "hunted " + h.kills() + (h.kills() == 1 ? " animal" : " animals");
		}
		if (task instanceof DeliverTask d) {
			return "gave " + d.recipientName() + " " + d.given() + " " + d.label();
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

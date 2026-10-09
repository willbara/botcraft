package com.mcfriends;

import com.mcfriends.action.ActionController;
import com.mcfriends.brain.Brain;
import com.mcfriends.brain.Drives;
import com.mcfriends.brain.Interrupts;
import com.mcfriends.brain.Status;
import com.mcfriends.brain.Task;
import com.mcfriends.chat.CommandTasks;
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
		perception.tick(serverTick);
		brain.tick(serverTick);
		nav.tick();
		actions.tick();
	}

	/** Called after the player joined (first spawn, restart restore, or respawn). */
	void onSpawned(boolean firstTime) {
		nav.stop();
		actions.reset();
		if (data.job != null && !brain.hasJob()) {
			CommandTasks.restoreJob(this);
		}
		brain.reconsider();
		AiLog.log(name(), "spawned (first={}), personality {}", firstTime, brain.personality().summary());
	}

	void onDeath() {
		AiLog.log(name(), "died");
		nav.stop();
	}

	void onRemoved() {
		nav.stop();
	}

	/** Brain callback when a task ends. */
	public void onTaskFinished(Task task, Status status, boolean interrupt) {
	}

	public String describeActivity() {
		if (player == null) {
			return "offline";
		}
		return brain.describe();
	}
}

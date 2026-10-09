package com.mcfriends;

import com.mcfriends.entity.CompanionData;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.util.AiLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime state for one companion: its persistent data, the current player entity (replaced
 * after death/rejoin) and the AI subsystems. Subsystems must always go through {@link #player()}
 * rather than caching the entity.
 */
public class Companion {
	public final CompanionData data;
	public final MinecraftServer server;
	@Nullable
	private CompanionPlayer player;

	/** Server tick at which a dead/kicked companion should rejoin; -1 = not scheduled. */
	long respawnAtTick = -1;
	int deadTicks;

	public Companion(MinecraftServer server, CompanionData data) {
		this.server = server;
		this.data = data;
	}

	@Nullable
	public CompanionPlayer player() {
		return player;
	}

	void setPlayer(@Nullable CompanionPlayer player) {
		this.player = player;
		this.deadTicks = 0;
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
	}

	/** Called after the player joined (first spawn, restart restore, or respawn). */
	void onSpawned(boolean firstTime) {
	}

	void onDeath() {
		AiLog.log(name(), "died");
	}

	void onRemoved() {
	}

	/** Called when the base task changes from a command or chat. */
	public void setBaseTask(String spec) {
		data.task = spec;
		AiLog.log(name(), "base task -> {}", spec);
	}

	public String describeActivity() {
		return data.task;
	}
}

package com.mcfriends;

import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.CompanionData;
import com.mcfriends.entity.CompanionPlayer;
import com.mcfriends.entity.CompanionStore;
import com.mcfriends.entity.FakePlayers;
import com.mcfriends.entity.SkinUtil;
import com.mcfriends.util.TickBudget;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/** Owns all companions on the running server. Server thread only. */
public final class CompanionManager {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final int AUTOSAVE_TICKS = 6000;
	private static final int DEATH_LINGER_TICKS = 40;

	@Nullable
	private static CompanionManager instance;

	private final MinecraftServer server;
	private final Map<String, Companion> companions = new LinkedHashMap<>();
	private long tickCount;
	private boolean stopping;

	private CompanionManager(MinecraftServer server) {
		this.server = server;
	}

	@Nullable
	public static CompanionManager get() {
		return instance;
	}

	// ---- lifecycle ------------------------------------------------------------------------

	static void onServerStarted(MinecraftServer server) {
		instance = new CompanionManager(server);
		instance.loadAndSpawnAll();
	}

	static void onServerStopping(MinecraftServer server) {
		if (instance == null) {
			return;
		}
		instance.stopping = true;
		instance.save();
		for (Companion c : instance.companions.values()) {
			CompanionPlayer p = c.player();
			if (p != null) {
				FakePlayers.disconnect(p, "Server closed");
				c.setPlayer(null);
			}
		}
	}

	static void onServerStopped(MinecraftServer server) {
		instance = null;
	}

	private void loadAndSpawnAll() {
		List<CompanionData> list = CompanionStore.load(server);
		for (CompanionData data : list) {
			Companion c = new Companion(server, data);
			companions.put(key(data.name), c);
			try {
				c.setPlayer(FakePlayers.connect(server, data, null, null, 0.0F));
				reviveIfDead(c, false);
			} catch (RuntimeException e) {
				MCFriends.LOG.error("Failed to restore companion {}", data.name, e);
			}
		}
		if (!list.isEmpty()) {
			MCFriends.LOG.info("Restored {} companion(s)", list.size());
		}
	}

	public void save() {
		List<CompanionData> list = new ArrayList<>();
		for (Companion c : companions.values()) {
			list.add(c.data);
		}
		CompanionStore.save(server, list);
	}

	// ---- queries ----------------------------------------------------------------------------

	public Collection<Companion> all() {
		return companions.values();
	}

	@Nullable
	public Companion byName(String name) {
		return companions.get(key(name));
	}

	@Nullable
	public Companion byPlayer(ServerPlayerEntity player) {
		if (!(player instanceof CompanionPlayer)) {
			return null;
		}
		Companion c = companions.get(key(player.getGameProfile().getName()));
		return c != null && c.player() == player ? c : null;
	}

	public List<Companion> ownedBy(ServerPlayerEntity owner) {
		List<Companion> out = new ArrayList<>();
		for (Companion c : companions.values()) {
			if (owner.getUuid().equals(c.data.owner)) {
				out.add(c);
			}
		}
		return out;
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	// ---- spawn / remove ---------------------------------------------------------------------

	/** @return null on success, otherwise an error message for the user. */
	@Nullable
	public String spawn(String name, @Nullable String skin, @Nullable ServerPlayerEntity owner, ServerWorld world, Vec3d pos, float yaw) {
		if (!NAME.matcher(name).matches()) {
			return "Name must be 3-16 characters: letters, digits or _";
		}
		Companion existing = byName(name);
		if (existing != null && existing.isOnline()) {
			return name + " is already here";
		}
		if (existing == null && companions.size() >= CompanionConfig.get().maxCompanions) {
			return "Companion limit reached (" + CompanionConfig.get().maxCompanions + ")";
		}
		ServerPlayerEntity clash = server.getPlayerManager().getPlayer(name);
		if (clash != null && !(clash instanceof CompanionPlayer)) {
			return "A real player named " + name + " is online";
		}

		CompanionData data = existing != null ? existing.data : newData(name, skin);
		if (existing == null && skin != null && data.skinValue == null && SkinUtil.skinIndex(skin) < 0) {
			return "Unknown skin '" + skin + "'. Use a default skin (" + String.join(", ", SkinUtil.NAMES)
				+ ", optionally _slim/_wide) or the name of an online player to copy.";
		}
		if (owner != null) {
			data.owner = owner.getUuid();
			data.ownerName = owner.getGameProfile().getName();
		}
		Companion c = existing != null ? existing : new Companion(server, data);
		try {
			c.setPlayer(FakePlayers.connect(server, data, world, pos, yaw));
			reviveIfDead(c, false);
		} catch (RuntimeException e) {
			MCFriends.LOG.error("Failed to spawn companion {}", name, e);
			return "Failed to spawn " + name + " (see log)";
		}
		c.respawnAtTick = -1;
		companions.put(key(name), c);
		save();
		c.onSpawned(existing == null);
		return null;
	}

	private CompanionData newData(String name, @Nullable String skin) {
		CompanionData data = new CompanionData();
		data.name = name;
		data.skin = skin == null ? "random" : skin;
		int skinIndex = -1;
		if (skin != null) {
			skinIndex = SkinUtil.skinIndex(skin);
			if (skinIndex < 0) {
				// Not a default skin: copy the signed textures of an online player with that name.
				// This is the only way to show a custom skin without contacting Mojang's servers.
				ServerPlayerEntity source = server.getPlayerManager().getPlayer(skin);
				if (source != null) {
					for (Property p : source.getGameProfile().getProperties().get("textures")) {
						if (p.hasSignature()) {
							data.skinValue = p.getValue();
							data.skinSignature = p.getSignature();
						}
					}
				}
			}
		}
		data.uuid = SkinUtil.uuidFor(name, skinIndex);
		// Personality is random but stable per name, so re-creating a companion keeps its character.
		Random r = new Random(data.uuid.getMostSignificantBits());
		data.chattiness = 0.2F + r.nextFloat() * 0.7F;
		data.bravery = 0.2F + r.nextFloat() * 0.7F;
		data.curiosity = 0.2F + r.nextFloat() * 0.7F;
		data.helpfulness = 0.3F + r.nextFloat() * 0.7F;
		return data;
	}

	/** Despawns the companion and forgets it. Its playerdata file is kept (re-spawning the name restores inventory). */
	public boolean remove(String name) {
		Companion c = companions.remove(key(name));
		if (c == null) {
			return false;
		}
		CompanionPlayer p = c.player();
		if (p != null) {
			c.onRemoved();
			FakePlayers.disconnect(p, "Dismissed");
			c.setPlayer(null);
		}
		save();
		return true;
	}

	// ---- ticking ----------------------------------------------------------------------------

	static void onEndTick(MinecraftServer server) {
		if (instance != null && instance.server == server) {
			instance.tick();
		}
	}

	private void tick() {
		if (stopping) {
			return;
		}
		tickCount++;
		TickBudget.begin(CompanionConfig.get().tickBudgetMs);
		for (Companion c : new ArrayList<>(companions.values())) {
			try {
				tickCompanion(c);
			} catch (RuntimeException e) {
				MCFriends.LOG.error("Error ticking companion {}", c.name(), e);
			}
		}
		if (tickCount % AUTOSAVE_TICKS == 0) {
			save();
		}
	}

	private void tickCompanion(Companion c) {
		CompanionPlayer p = c.player();
		if (p == null) {
			if (c.respawnAtTick >= 0 && tickCount >= c.respawnAtTick) {
				respawn(c);
			}
			return;
		}
		if (p.isDisconnected()) {
			// Kicked (/kick) or otherwise disconnected by someone else. Stays registered and
			// comes back on the next server start or /companion spawn.
			MCFriends.LOG.info("Companion {} was disconnected", c.name());
			c.setPlayer(null);
			return;
		}
		if (p.isDead()) {
			if (c.deadTicks++ == 0) {
				c.onDeath();
			}
			if (c.deadTicks > DEATH_LINGER_TICKS) {
				// No mixin-free way to run vanilla respawn for a custom player class (respawnPlayer
				// constructs a plain ServerPlayerEntity), so the companion leaves and rejoins.
				FakePlayers.disconnect(p, "Died");
				c.setPlayer(null);
				c.respawnAtTick = tickCount + CompanionConfig.get().respawnDelayTicks;
			}
			return;
		}
		c.tick(tickCount);
	}

	private void respawn(Companion c) {
		c.respawnAtTick = -1;
		ServerPlayerEntity owner = c.owner();
		ServerWorld world;
		Vec3d pos;
		if (owner != null && !owner.isDead()) {
			world = owner.getServerWorld();
			pos = owner.getPos();
		} else {
			world = server.getOverworld();
			BlockPos spawn = world.getSpawnPos();
			pos = Vec3d.ofBottomCenter(world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawn));
		}
		try {
			c.setPlayer(FakePlayers.connect(server, c.data, world, pos, 0.0F));
			reviveIfDead(c, true);
			c.onSpawned(false);
		} catch (RuntimeException e) {
			MCFriends.LOG.error("Failed to respawn companion {}", c.name(), e);
		}
	}

	/** Saved playerdata of a dead player has Health 0; reset it like vanilla respawn would. */
	private void reviveIfDead(Companion c, boolean force) {
		CompanionPlayer p = c.player();
		if (p == null || (!force && !p.isDead())) {
			return;
		}
		if (!p.isDead()) {
			return;
		}
		p.setHealth(p.getMaxHealth());
		p.deathTime = 0;
		p.fallDistance = 0;
		p.extinguish();
		p.clearStatusEffects();
		p.getHungerManager().setFoodLevel(20);
		p.getHungerManager().setSaturationLevel(5.0F);
		if (!p.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY)) {
			p.setExperienceLevel(0);
			p.setExperiencePoints(0);
		}
		MCFriends.LOG.info("Companion {} respawned", c.name());
	}
}

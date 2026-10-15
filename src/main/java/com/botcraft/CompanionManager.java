package com.botcraft;

import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionData;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.entity.CompanionStore;
import com.botcraft.entity.FakePlayers;
import com.botcraft.entity.SkinUtil;
import com.botcraft.entity.SkinResolver.SkinChoice;
import com.botcraft.util.TickBudget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import com.botcraft.entity.SafeSpot;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.util.WorldSavePath;
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
		LegacyNames.migrateWorld(server);
		com.botcraft.faction.Factions.load(server);
		com.botcraft.chat.LexiconStore.load(server);
		com.botcraft.colony.Colony.load(server);
		com.botcraft.colony.Builds.load(server);
		instance = new CompanionManager(server);
		// Companions rejoin on the first server tick, not here: other mods finish setting up in
		// their own "server started" handlers (Xaero's minimap threw on a join this early, and the
		// companion was disconnected at once).
		instance.restorePending = true;
		com.botcraft.ops.Soak.maybeStart(server);
		com.botcraft.ops.Bench.maybeStart(server);
		com.botcraft.ops.NavBench.maybeStart(server);
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
				if (p.isDead()) {
					healForSave(p);
				}
				FakePlayers.disconnect(p, "Server closed");
				c.setPlayer(null);
			}
		}
	}

	static void onServerStopped(MinecraftServer server) {
		com.botcraft.colony.Colony.unload();
		com.botcraft.faction.Factions.unload();
		com.botcraft.faction.Raids.clear();
		com.botcraft.faction.Fracture.clear();
		com.botcraft.colony.Zones.clear();
		com.botcraft.faction.Diplomacy.clear();
		com.botcraft.faction.Territory.clear();
		com.botcraft.faction.Leadership.clear();
		com.botcraft.faction.Strategy.clear();
		com.botcraft.faction.Ceasefire.clear();
		com.botcraft.faction.Defection.clear();
		com.botcraft.brain.EasterEggs.clear();
		com.botcraft.chat.Lexicon.clear();
		com.botcraft.colony.Builds.save(server);
		com.botcraft.colony.Builds.unload();
		com.botcraft.perception.Sight.clear();
		com.botcraft.brain.Board.clear();
		instance = null;
	}

	/** Companions from the save still to rejoin (done on the first tick). */
	private boolean restorePending;

	private void loadAndSpawnAll() {
		List<CompanionData> list = CompanionStore.load(server);
		for (CompanionData data : list) {
			Companion c = new Companion(server, data);
			companions.put(key(data.name), c);
			try {
				c.setPlayer(FakePlayers.connect(server, data, null, null, 0.0F));
				reviveIfDead(c, false);
				rescueIfStuck(c);
				c.onSpawned("restored");
			} catch (RuntimeException e) {
				Botcraft.LOG.error("Failed to restore companion {}", data.name, e);
			}
		}
		if (!list.isEmpty()) {
			Botcraft.LOG.info("Restored {} companion(s)", list.size());
		}
	}

	/** Server ticks counted by the manager (what companions use as "now"). */
	public long tickCount() {
		return tickCount;
	}

	public void save() {
		for (com.botcraft.colony.Colony colony : com.botcraft.colony.Colony.all()) {
			colony.save();
		}
		com.botcraft.faction.Factions.save();
		com.botcraft.colony.Builds.save(server);
		List<CompanionData> list = new ArrayList<>();
		for (Companion c : companions.values()) {
			list.add(c.data);
			c.memory().saveIfDirty(server, c.name());
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

	/** @return null on success, otherwise an error message. */
	@Nullable
	public String spawn(String name, SkinChoice skin, @Nullable ServerPlayerEntity owner, ServerWorld world, Vec3d pos, float yaw) {
		if (!NAME.matcher(name).matches()) {
			return "Name must be 3-16 characters: letters, digits or _";
		}
		Companion existing = byName(name);
		if (existing != null && existing.isOnline()) {
			return name + " is already here";
		}
		int limit = CompanionConfig.get().maxCompanions;
		if (existing == null && limit > 0 && companions.size() >= limit) {
			return "Companion limit reached (" + CompanionConfig.get().maxCompanions + ")";
		}
		ServerPlayerEntity clash = server.getPlayerManager().getPlayer(name);
		if (clash != null && !(clash instanceof CompanionPlayer)) {
			return "A real player named " + name + " is online";
		}
		CompanionData data = existing != null ? existing.data : newData(name, skin);
		if (owner != null) {
			data.owner = owner.getUuid();
			data.ownerName = owner.getGameProfile().getName();
		}
		Companion c = existing != null ? existing : new Companion(server, data);
		try {
			c.setPlayer(FakePlayers.connect(server, data, world, pos, yaw));
			reviveIfDead(c, false);
		} catch (RuntimeException e) {
			Botcraft.LOG.error("Failed to spawn companion {}", name, e);
			return "Failed to spawn " + name + " (see log)";
		}
		c.respawnAtTick = -1;
		if (existing == null && CompanionConfig.get().startFree) {
			// Off on its own straight away (startFree), as if told "go have fun", with home where it arrived.
			c.data.mode = "free";
			c.memory().setPlace("home", BlockPos.ofFloored(pos), world.getRegistryKey().getValue().toString(), world.getTime());
		}
		companions.put(key(name), c);
		save();
		c.onSpawned(existing == null ? "new" : "summoned");
		return null;
	}

	/** Convenience (tests): spawn with a built-in default skin id. */
	@Nullable
	public String spawn(String name, String defaultSkin, @Nullable ServerPlayerEntity owner, ServerWorld world, Vec3d pos, float yaw) {
		int idx = SkinUtil.skinIndex(defaultSkin);
		return spawn(name, idx >= 0 ? new SkinChoice(defaultSkin, idx, null, null) : SkinChoice.random(), owner, world, pos, yaw);
	}

	private CompanionData newData(String name, SkinChoice skin) {
		CompanionData data = new CompanionData();
		data.name = name;
		applySkin(data, skin);
		data.uuid = SkinUtil.uuidFor(name, skin.hasTextures() ? -1 : skin.defaultIndex());
		// Personality is random but stable per name, so re-creating a companion keeps its character.
		Random r = new Random(SkinUtil.uuidFor(name, -1).getMostSignificantBits());
		data.chattiness = 0.2F + r.nextFloat() * 0.7F;
		data.bravery = 0.2F + r.nextFloat() * 0.7F;
		data.curiosity = 0.2F + r.nextFloat() * 0.7F;
		data.helpfulness = 0.3F + r.nextFloat() * 0.7F;
		return data;
	}

	private static void applySkin(CompanionData data, SkinChoice skin) {
		data.skin = skin.id();
		data.skinValue = skin.hasTextures() ? skin.value() : null;
		data.skinSignature = skin.hasTextures() ? skin.signature() : null;
	}

	/**
	 * Change an existing companion's skin. Clients only read skins when a player joins, so the
	 * companion leaves and rejoins in place. Default skins are picked through the UUID, so for
	 * those the companion's playerdata/stats/advancements files move to the new UUID.
	 */
	@Nullable
	public String changeSkin(Companion c, SkinChoice skin) {
		CompanionPlayer p = c.player();
		ServerWorld world = p != null ? p.getServerWorld() : null;
		Vec3d pos = p != null ? p.getPos() : null;
		float yaw = p != null ? p.getYaw() : 0.0F;
		UUID newUuid = SkinUtil.uuidFor(c.name(), skin.hasTextures() ? -1 : skin.defaultIndex());
		if (p != null) {
			FakePlayers.disconnect(p, "Changing skin");
			c.setPlayer(null);
		}
		if (!newUuid.equals(c.data.uuid)) {
			moveFile(WorldSavePath.PLAYERDATA, c.data.uuid + ".dat", newUuid + ".dat");
			moveFile(WorldSavePath.PLAYERDATA, c.data.uuid + ".dat_old", newUuid + ".dat_old");
			moveFile(WorldSavePath.STATS, c.data.uuid + ".json", newUuid + ".json");
			moveFile(WorldSavePath.ADVANCEMENTS, c.data.uuid + ".json", newUuid + ".json");
			c.data.uuid = newUuid;
		}
		applySkin(c.data, skin);
		save();
		try {
			c.setPlayer(FakePlayers.connect(server, c.data, world, pos, yaw));
			reviveIfDead(c, false);
			c.onSpawned("reskinned");
		} catch (RuntimeException e) {
			Botcraft.LOG.error("Failed to rejoin {} after skin change", c.name(), e);
			return "Failed to rejoin (see log)";
		}
		return null;
	}

	private void moveFile(WorldSavePath dir, String from, String to) {
		Path base = server.getSavePath(dir);
		Path src = base.resolve(from);
		try {
			if (Files.exists(src)) {
				Files.move(src, base.resolve(to), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Botcraft.LOG.warn("Could not move {} to {}", src, to, e);
		}
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

	/** A player joined: their companions say hello. */
	static void onPlayerJoin(ServerPlayerEntity player) {
		if (instance == null || player instanceof CompanionPlayer) {
			return;
		}
		for (Companion c : instance.ownedBy(player)) {
			if (c.isOnline()) {
				c.onOwnerJoined(player);
			}
		}
	}

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
		if (restorePending) {
			restorePending = false;
			loadAndSpawnAll();
			com.botcraft.faction.Factions.syncTeams();
		}
		double budget = budgetMs();
		TickBudget.begin(budget, ceilingMs());
		// Rotate the starting companion so a shared, exhausted budget doesn't always starve the same one.
		List<Companion> order = new ArrayList<>(companions.values());
		if (!order.isEmpty()) {
			java.util.Collections.rotate(order, (int) (tickCount % order.size()));
		}
		long started = System.nanoTime();
		double slice = sliceMs();
		double burst = CompanionConfig.get().companionBurstMs;
		for (Companion c : order) {
			// Its own allowance: this tick's slice plus what it saved on quiet ticks, up to a burst.
			c.budgetCredit = Math.min(burst, c.budgetCredit + slice);
			TickBudget.beginCompanion(c.budgetCredit);
			// Everything a companion does is for its own faction (its colony, its board).
			String prev = com.botcraft.faction.Scope.enter(com.botcraft.faction.Factions.idOf(c));
			try {
				tickCompanion(c);
			} catch (RuntimeException e) {
				Botcraft.LOG.error("Error ticking companion {}", c.name(), e);
			} finally {
				com.botcraft.faction.Scope.exit(prev);
				c.budgetCredit = Math.max(0, c.budgetCredit - TickBudget.endCompanion());
			}
		}
		for (var colony : com.botcraft.colony.Colony.all()) {
			String prev = com.botcraft.faction.Scope.enter(colony.id());
			try {
				com.botcraft.colony.BaseMap.tick(colony);
				if (tickCount % 100 == 50) {
					com.botcraft.colony.Zones.plan(colony, com.botcraft.colony.BaseMap.of(colony.id()));
				}
			} finally {
				com.botcraft.faction.Scope.exit(prev);
			}
		}
		if (tickCount % 200 == 7) {
			com.botcraft.faction.Factions.syncTeams(); // new companions, players who joined
		}
		try {
			com.botcraft.faction.Raids.tick(this, tickCount);
			com.botcraft.faction.Fracture.tick(this, tickCount);
			com.botcraft.faction.Diplomacy.tick(this, tickCount);
			com.botcraft.faction.Ceasefire.tick(this, tickCount);
			com.botcraft.faction.Territory.tick(this, tickCount);
			com.botcraft.faction.Leadership.tick(this, tickCount);
			com.botcraft.faction.Merger.tick(this, tickCount);
		} catch (RuntimeException e) {
			Botcraft.LOG.error("Error updating factions", e);
		}
		long took = System.nanoTime() - started;
		aiMs = aiMs * 0.8 + took / 1e6 * 0.2;
		com.botcraft.ops.SoakStats.tickTime(took, budget + slice * order.size());
		com.botcraft.ops.SoakStats.serverTick(server.getTickTime());
		com.botcraft.ops.Soak.tick(server);
		com.botcraft.ops.Bench.tick(server);
		com.botcraft.ops.NavBench.tick(server);
		com.botcraft.chat.Social.tick(companions.values(), tickCount);
		if (tickCount % 100 == 0) {
			try {
				com.botcraft.brain.Board.refresh(this, tickCount);
			} catch (RuntimeException e) {
				Botcraft.LOG.error("Error updating the request board", e);
			}
		}
		if (tickCount % AUTOSAVE_TICKS == 0) {
			save();
		}
	}

	/** Smoothed time the companions took per tick (ms), like the server's own tick time. */
	private double aiMs;
	/** A whole server tick should stay under this (of 50 ms), leaving room for spikes. */
	private static final double TARGET_TICK_MS = 35.0;

	/**
	 * How long companions may think this tick: the configured floor, plus whatever the rest of
	 * the server leaves spare under {@link #TARGET_TICK_MS}, up to maxTickBudgetMs. A quiet
	 * server gets quick path finding; a busy one is never slowed down by it.
	 */
	/**
	 * Each companion's own time per tick: {@code companionBudgetMs}, cut back while the server is
	 * falling behind (a whole tick over 40 ms), so many companions never add up to a lagging server.
	 */
	private double sliceMs() {
		CompanionConfig cfg = CompanionConfig.get();
		double tick = server.getTickTime();
		double scale = tick > 50 ? 0.25 : tick > 40 ? 0.5 : 1.0;
		return cfg.companionBudgetMs * scale;
	}

	/** All the AI time a tick can take, whatever companions have saved: what the rest of the server leaves under 35 ms. */
	private double ceilingMs() {
		double others = Math.max(0, server.getTickTime() - aiMs);
		return Math.max(CompanionConfig.get().tickBudgetMs, TARGET_TICK_MS - others);
	}

	private double budgetMs() {
		CompanionConfig cfg = CompanionConfig.get();
		if (!com.botcraft.util.Experiments.on("adaptiveBudget")) {
			return cfg.tickBudgetMs;
		}
		double others = Math.max(0, server.getTickTime() - aiMs);
		return Math.max(cfg.tickBudgetMs, Math.min(cfg.maxTickBudgetMs, TARGET_TICK_MS - others));
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
			// comes back on the next server start or /companion spawn. Dropped right after
			// joining (another mod failing on the join) it tries again, twice at most.
			c.setPlayer(null);
			if (p.age < 100 && c.rejoinTries < 2) {
				c.rejoinTries++;
				c.rejoinPending = true;
				c.respawnAtTick = tickCount + 100;
				Botcraft.LOG.warn("Companion {} was disconnected right after joining; trying again in 5 s", c.name());
			} else {
				Botcraft.LOG.info("Companion {} was disconnected", c.name());
			}
			return;
		}
		if (p.isDead()) {
			if (c.deadTicks++ == 0) {
				c.onDeath();
			}
			if (c.deadTicks > DEATH_LINGER_TICKS) {
				// No mixin-free way to run vanilla respawn for a custom player class (respawnPlayer
				// constructs a plain ServerPlayerEntity), so the companion leaves and rejoins.
				// Heal first so the saved playerdata isn't "dead": a player that appears with 0
				// health starts its death animation on clients and never stops it.
				healForSave(p);
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
		if (c.rejoinPending) {
			// Dropped right after joining: back where it was saved, as if the server had just started.
			c.rejoinPending = false;
			try {
				c.setPlayer(FakePlayers.connect(server, c.data, null, null, 0.0F));
				reviveIfDead(c, false);
				c.onSpawned("restored");
			} catch (RuntimeException e) {
				Botcraft.LOG.error("Failed to rejoin companion {}", c.name(), e);
			}
			return;
		}
		Arrival arrival = respawnPoint(c);
		ServerWorld world = arrival.world();
		Vec3d pos = arrival.pos();
		try {
			c.setPlayer(FakePlayers.connect(server, c.data, world, pos, 0.0F));
			reviveIfDead(c, true);
			c.onSpawned("respawned");
		} catch (RuntimeException e) {
			Botcraft.LOG.error("Failed to respawn companion {}", c.name(), e);
		}
	}

	@Nullable
	private ServerWorld worldFor(String dim) {
		for (ServerWorld w : server.getWorlds()) {
			if (w.getRegistryKey().getValue().toString().equals(dim)) {
				return w;
			}
		}
		return null;
	}

	public record Arrival(ServerWorld world, Vec3d pos) {
	}

	/** Bed, else home, else world spawn (never the owner): where a free companion is rescued to. */
	public Arrival homeArrival(Companion c) {
		return respawnPoint(c, false);
	}

	/** Where a companion comes back: bed, home, a safely standing owner, or world spawn. */
	private Arrival respawnPoint(Companion c) {
		// Free companions live on their own: they come back home, not at the owner's side.
		return respawnPoint(c, !"free".equals(c.data.mode));
	}

	private Arrival respawnPoint(Companion c, boolean ownerOk) {
		ServerWorld world = server.getOverworld();
		BlockPos at = null;
		// Like a player: our bed, else home, else with the owner (only if they're somewhere safe:
		// not spectating inside a flooded cave), else world spawn. Never into water, lava or a wall.
		// Never at another faction's base or on enemy land, though: a bed or home remembered from a
		// faction we've left would put us among enemies, to die again and again.
		String ours = com.botcraft.faction.Factions.idOf(c);
		for (String placeName : new String[]{"bed", "home"}) {
			var place = c.memory().place(placeName);
			if (at == null && place != null) {
				ServerWorld w = worldFor(place.dim);
				if (w != null && com.botcraft.faction.Factions.enabled() && (com.botcraft.faction.Factions.foreignBase(ours, w, place.pos()) != null
					|| com.botcraft.faction.Hostility.hostileLand(c, w, place.pos()))) {
					Botcraft.LOG.info("[{}] won't respawn at its {} ({}): another faction's ground", c.name(), placeName, place.pos().toShortString());
					continue;
				}
				BlockPos safe = w == null ? null : SafeSpot.near(w, place.pos(), 6);
				if (safe != null) {
					world = w;
					at = safe;
				}
			}
		}
		// Else our own faction's base.
		var colony = com.botcraft.faction.Factions.enabled() && !com.botcraft.faction.Factions.loners(ours) ? com.botcraft.colony.Colony.of(ours) : null;
		if (at == null && colony != null && colony.hasBase() && colony.world() != null) {
			BlockPos safe = SafeSpot.near(colony.world(), colony.base(), 6);
			if (safe != null) {
				world = colony.world();
				at = safe;
			}
		}
		ServerPlayerEntity owner = c.owner();
		if (ownerOk && at == null && owner != null && !owner.isDead() && !owner.isSpectator() && owner.isOnGround() && !owner.isTouchingWater()) {
			BlockPos safe = SafeSpot.near(owner.getServerWorld(), owner.getBlockPos(), 4);
			if (safe != null) {
				world = owner.getServerWorld();
				at = safe;
			}
		}
		if (at == null) {
			world = server.getOverworld();
			BlockPos spawn = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, world.getSpawnPos());
			BlockPos safe = SafeSpot.near(world, spawn, 8);
			at = safe != null ? safe : spawn;
		}
		return new Arrival(world, Vec3d.ofBottomCenter(at));
	}

	/**
	 * Saved somewhere deadly (under water, in lava, inside a block): a player logging in there
	 * would just die, so move to the nearest safe spot (or a respawn point) as it joins.
	 */
	private void rescueIfStuck(Companion c) {
		CompanionPlayer p = c.player();
		if (p == null) {
			return;
		}
		ServerWorld world = p.getServerWorld();
		BlockPos feet = p.getBlockPos();
		boolean stuck = p.isSubmergedInWater() || p.isInLava() || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
		if (!stuck) {
			return;
		}
		BlockPos safe = SafeSpot.near(world, feet, 12);
		if (safe != null) {
			p.teleport(world, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, p.getYaw(), p.getPitch());
		} else {
			var at = respawnPoint(c);
			p.teleport(at.world(), at.pos().x, at.pos().y, at.pos().z, p.getYaw(), p.getPitch());
		}
		p.setAir(p.getMaxAir());
		Botcraft.LOG.info("Companion {} was saved somewhere unsafe ({}), moved to {}", c.name(), feet.toShortString(), p.getBlockPos().toShortString());
	}

	/** Make a dead companion "alive" just before it leaves, so its saved playerdata is too. */
	private static void healForSave(CompanionPlayer p) {
		p.setHealth(p.getMaxHealth());
		p.deathTime = 0;
		p.hurtTime = 0;
		p.extinguish();
		p.setAir(p.getMaxAir());
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
		Botcraft.LOG.info("Companion {} respawned", c.name());
	}
}

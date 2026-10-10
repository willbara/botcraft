package com.mcfriends.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mcfriends.MCFriends;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON config stored at config/mcfriends.json. Missing fields fall back to the
 * defaults below; the file is rewritten on load so new options show up.
 */
public class CompanionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CompanionConfig instance = new CompanionConfig();

	/** Maximum number of companions on the server at once. 0 = no limit. */
	public int maxCompanions = 0;
	/** Brain (drive scoring / task selection) runs every N ticks. Body control runs every tick. */
	public int aiTickInterval = 5;
	/** Total time (milliseconds) all companions may spend on AI work per server tick. */
	public double tickBudgetMs = 2.0;
	/** A* node expansions allowed per companion per tick (also bounded by tickBudgetMs). */
	public int pathNodesPerTick = 600;
	/** Abort a path search after this many expanded nodes. */
	public int maxPathNodes = 20000;
	/** Companions may chat (replies and unprompted remarks). */
	public boolean chatEnabled = true;
	/**
	 * When false (default) companions never break blocks that could be player-placed:
	 * navigation only breaks natural (non-persistent) leaves, and wood is only taken from
	 * trees that still have natural leaves. Containers / block entities are never broken.
	 */
	public boolean griefing = false;
	/** Navigation may place throwaway blocks (dirt, cobblestone, ...) to bridge 1-block gaps. */
	public boolean allowBridging = true;
	/** Preferred distance from the owner while following. */
	public double followDistance = 3.5;
	/** If the owner is farther than this (or in another dimension) the companion teleports to them. 0 disables. */
	public double teleportToOwnerDistance = 48.0;
	/** Radius (blocks) searched for trees when gathering wood. */
	public int treeSearchRadius = 32;
	/** Ticks before a dead companion rejoins. */
	public int respawnDelayTicks = 100;
	/**
	 * Allow looking up skins of registered Minecraft accounts by name (one request to Mojang per
	 * new name, off the server thread, same as player heads). Results are stored with the companion.
	 */
	public boolean allowSkinLookup = true;
	/** When /companion spawn has no skin argument, try the account with the companion's own name. */
	public boolean autoSkinFromName = false;
	/**
	 * How readily idle companions set their own goals (make tools, gather wood). 0 = never act
	 * without orders, 1 = eager. Personality scales it further.
	 */
	public float initiative = 0.5F;
	/** Companions go to bed when their owner sleeps (so the night can be skipped). */
	public boolean sleepWithOwner = true;
	/** Put companions in survival mode when they join (they gather/fight like survival players). */
	public boolean forceSurvival = true;
	/** Permission level required for /companion. */
	public int commandPermissionLevel = 2;
	/** Log AI decisions at INFO (instead of DEBUG) so they show up in latest.log without log config. */
	/** Put torches down in dark caves and around a dark base. */
	public boolean placeTorches = true;
	public boolean verboseAiLogging = false;

	public static CompanionConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(MCFriends.MOD_ID + ".json");
	}

	public static void load() {
		Path file = path();
		CompanionConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CompanionConfig.class);
			} catch (IOException | JsonParseException e) {
				MCFriends.LOG.error("Could not read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new CompanionConfig();
		instance.sanitize();
		save();
	}

	public static void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			MCFriends.LOG.error("Could not write {}", file, e);
		}
	}

	private void sanitize() {
		maxCompanions = Math.max(0, maxCompanions);
		aiTickInterval = Math.max(1, Math.min(40, aiTickInterval));
		tickBudgetMs = Math.max(0.2, Math.min(20.0, tickBudgetMs));
		pathNodesPerTick = Math.max(10, pathNodesPerTick);
		maxPathNodes = Math.max(200, maxPathNodes);
		followDistance = Math.max(1.5, followDistance);
		treeSearchRadius = Math.max(4, Math.min(64, treeSearchRadius));
		respawnDelayTicks = Math.max(1, respawnDelayTicks);
		commandPermissionLevel = Math.max(0, Math.min(4, commandPermissionLevel));
		initiative = Math.max(0.0F, Math.min(1.0F, initiative));
	}
}

package com.botcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.botcraft.Botcraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON config stored at config/botcraft.json. Missing fields fall back to the
 * defaults below; the file is rewritten on load so new options show up.
 */
public class CompanionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CompanionConfig instance = new CompanionConfig();

	/** Maximum number of companions on the server at once. 0 = no limit. */
	public int maxCompanions = 0;
	/** Brain (drive scoring / task selection) runs every N ticks. Body control runs every tick. */
	public int aiTickInterval = 5;
	/**
	 * Time (milliseconds) each companion has of its own for heavy AI work (path search, block
	 * scans) per server tick, halved while the server is falling behind. Unused time is saved up
	 * to companionBurstMs, so a long search can run without starving the others.
	 */
	public double companionBudgetMs = 0.5;
	/** Most AI time a companion can save up for one tick (ms). */
	public double companionBurstMs = 5.0;
	/**
	 * Time (milliseconds) shared by all companions per server tick on top of their own: what a
	 * companion borrows once its own is spent. More is shared when the server has time to spare.
	 */
	public double tickBudgetMs = 2.0;
	/**
	 * Most time per tick the AI may take when the rest of the server is light: the budget grows
	 * from tickBudgetMs up to this while a whole tick stays under 35 ms (of the 50 ms there are).
	 */
	public double maxTickBudgetMs = 8.0;
	/** A* node expansions allowed per companion per tick (also bounded by tickBudgetMs). */
	public int pathNodesPerTick = 600;
	/**
	 * Once a path search has a partial path worth taking (one that gets more than 5 blocks from
	 * the start), it stops here and hands that over; it only runs on to maxPathNodes while it has
	 * nothing (Baritone's primary and failure timeouts, in nodes rather than milliseconds so a run
	 * doesn't depend on how busy the machine is).
	 */
	public int pathPrimaryNodes = 20000;
	/** Abort a path search after this many expanded nodes. */
	public int maxPathNodes = 80000;
	/** Companions may chat (replies and unprompted remarks). */
	public boolean chatEnabled = true;
	/**
	 * How much companions say: "all" (everything), or "quiet": answers and acknowledgements when
	 * spoken to, saying what they're starting on their own, and saying when something failed;
	 * no small talk, banter or remarks. Set in game with /companion chat.
	 */
	public String chatVerbosity = "all";
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
	/**
	 * Free mode keeps working at night, close to home (upkeep, the farm, chores, mining from
	 * home), sleeping only when that skips the night (a player is in bed, or there's no player to
	 * wait for). Off: to bed at dusk, as before.
	 */
	public boolean nightWork = true;
	/** Companions go to bed when their owner sleeps (so the night can be skipped). */
	public boolean sleepWithOwner = true;
	/** Put companions in survival mode when they join (they gather/fight like survival players). */
	public boolean forceSurvival = true;
	/** Permission level required for /companion. */
	public int commandPermissionLevel = 2;
	/**
	 * New companions start in free mode (living on their own, as after "go have fun") instead of
	 * following whoever spawned them. /companion freemode on|off sets it.
	 */
	public boolean startFree = false;
	/**
	 * Teams and dynamic mode: a companion left in no faction (just spawned, its faction disbanded)
	 * is taken in by one: the smallest team, or in dynamic mode the faction it likes best nearby.
	 */
	public boolean recruit = true;
	/** Free mode tends the base's farm zone (harvest, replant, till). Asking a companion to farm works either way. */
	public boolean farming = true;
	/** Farmers add more farm plots next to the first as the base grows, each watered from a hole in its middle. */
	public boolean expandFarms = true;
	/** Fell the trees standing in the home zone (part of tidying the base). */
	public boolean clearTrees = true;
	/** Put a fence round the home zone (with gates on the paths) once the base has wood to spare. */
	public boolean fenceHome = true;
	/** Companions who find water may make a fishing rod and fish there. */
	public boolean fishing = true;
	/** Put torches down in dark caves and around a dark base. */
	public boolean placeTorches = true;
	/** Easter eggs (a companion named "popbob" is hostile and griefs). */
	public boolean easterEggs = true;
	/** Log AI decisions at INFO (instead of DEBUG) so they show up in latest.log without log config. */
	public boolean verboseAiLogging = false;
	/**
	 * How companions group up: "single" (one colony, as before), "teams" (fixed teams such as red
	 * and blue, set with /companion faction teams), or "dynamic" (factions form and fight on their own).
	 */
	public String factionMode = "single";
	/**
	 * How companions treat hostile players and enemy factions: "off" (never fight them), "defend"
	 * (only when attacked or inside our base), "pvp" (on sight), "raid" (pvp, and raids on enemy
	 * bases; breaking blocks or taking from chests also needs griefing). Dynamic factions always raid.
	 */
	public String warfare = "defend";
	/**
	 * "absolute": a companion always obeys the player who spawned it. "standing": the owner is
	 * judged by how they treat it, like everyone else (starting at ownerStartStanding).
	 */
	public String ownerAuthority = "absolute";
	/** Standing the owner starts with in "standing" mode (-100..100). */
	public int ownerStartStanding = 90;
	/** Standing a player starts with when a faction first meets them (-100..100). */
	public int playerStartStanding = 0;
	/** Standing a player (other than the owner) needs to give orders. */
	public int orderStanding = 60;
	/** Standing a player needs for requests (hand me something, come here). */
	public int requestStanding = 20;
	/** At or below this standing a player is an enemy. */
	public int hostileStanding = -30;
	/**
	 * Factions without a base (in teams and dynamic mode) pick a spot and found one themselves;
	 * the zones are then laid out as usual. Zones set by hand are never moved.
	 */
	public boolean dynamicBases = true;
	/**
	 * New factions (teams just made, a split) start neutral and can't fall out past cool, or go to
	 * war, for this many minutes. After that, how they get on is up to them.
	 */
	public int factionGraceMinutes = 15;
	/** Dynamic mode: most factions there can be (splits stop at this many). */
	public int maxFactions = 6;
	/**
	 * Factions hold land (chunks) round their base and spread out over time; wars, raids and
	 * truces start where their borders meet. Off: factions anywhere can fight (as before 0.13).
	 */
	public boolean territory = true;
	/** Kills of our members put a price on the killer's head; companions go after them first. */
	public boolean bounties = true;
	/** Which defaults this file was written with (missing = before 0.11.0), so old defaults can be upgraded. */
	public int configVersion = 0;
	private static final int CONFIG_VERSION = 2;

	public static CompanionConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Botcraft.MOD_ID + ".json");
	}

	public static void load() {
		Path file = path();
		CompanionConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CompanionConfig.class);
			} catch (IOException | JsonParseException e) {
				Botcraft.LOG.error("Could not read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new CompanionConfig();
		if (loaded == null) {
			instance.configVersion = CONFIG_VERSION;
		}
		instance.upgrade();
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
			Botcraft.LOG.error("Could not write {}", file, e);
		}
	}

	/** Old defaults written into a file before they changed are moved to the new ones; values someone set stay. */
	private void upgrade() {
		if (configVersion < 2) {
			// 0.11.0: Baritone-sized path searches (20000 nodes was the old cap).
			if (maxPathNodes == 20000) {
				maxPathNodes = 80000;
			}
			if (pathPrimaryNodes == 5000) {
				pathPrimaryNodes = 20000; // (only ever written by 0.11.0 development builds)
			}
		}
		configVersion = CONFIG_VERSION;
	}

	private void sanitize() {
		maxCompanions = Math.max(0, maxCompanions);
		aiTickInterval = Math.max(1, Math.min(40, aiTickInterval));
		tickBudgetMs = Math.max(0.2, Math.min(20.0, tickBudgetMs));
		companionBudgetMs = Math.max(0.0, Math.min(10.0, companionBudgetMs));
		companionBurstMs = Math.max(companionBudgetMs, Math.min(50.0, companionBurstMs));
		maxTickBudgetMs = Math.max(tickBudgetMs, Math.min(30.0, maxTickBudgetMs));
		pathNodesPerTick = Math.max(10, pathNodesPerTick);
		maxPathNodes = Math.max(200, maxPathNodes);
		pathPrimaryNodes = Math.max(200, Math.min(maxPathNodes, pathPrimaryNodes));
		followDistance = Math.max(1.5, followDistance);
		treeSearchRadius = Math.max(4, Math.min(64, treeSearchRadius));
		respawnDelayTicks = Math.max(1, respawnDelayTicks);
		commandPermissionLevel = Math.max(0, Math.min(4, commandPermissionLevel));
		initiative = Math.max(0.0F, Math.min(1.0F, initiative));
		if (!"quiet".equals(chatVerbosity)) {
			chatVerbosity = "all";
		}
		if (!java.util.List.of("single", "teams", "dynamic").contains(factionMode)) {
			factionMode = "single";
		}
		if (!java.util.List.of("off", "defend", "pvp", "raid").contains(warfare)) {
			warfare = "defend";
		}
		if (!"standing".equals(ownerAuthority)) {
			ownerAuthority = "absolute";
		}
		ownerStartStanding = Math.max(-100, Math.min(100, ownerStartStanding));
		maxFactions = Math.max(1, Math.min(32, maxFactions));
		playerStartStanding = Math.max(-100, Math.min(100, playerStartStanding));
		orderStanding = Math.max(-100, Math.min(100, orderStanding));
		requestStanding = Math.max(-100, Math.min(orderStanding, requestStanding));
		hostileStanding = Math.max(-100, Math.min(requestStanding - 1, hostileStanding));

	}
}

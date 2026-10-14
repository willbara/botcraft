package com.botcraft.faction;

import com.botcraft.Botcraft;
import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.CompanionPlayer;
import com.botcraft.util.AiLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * All factions, and what each thinks of players and of each other.
 *
 * <ul>
 *   <li><b>Modes</b> ({@code factionMode} in the config): "single" is one colony, exactly as
 *       before factions; "teams" is fixed teams (red vs blue...) set with
 *       {@code /companion faction teams}; "dynamic" lets factions form and fight on their own.</li>
 *   <li><b>Membership:</b> a companion's faction is saved with it ({@code CompanionData.faction}).
 *       A player is a member if added to one, and counts as one of a faction if they own a
 *       companion in it.</li>
 *   <li><b>Standing:</b> every faction keeps a {@link Standing} for each player it has dealt with;
 *       it decides whether a player may give orders or requests, and whether they're an enemy.</li>
 *   <li><b>Relations</b> between factions are -100..100; at or below {@code hostileStanding}
 *       they're enemies.</li>
 *   <li>Each faction is a vanilla scoreboard team (outside single mode), so names show in its
 *       colour and members can't hurt each other.</li>
 * </ul>
 */
public final class Factions {
	public static final String DEFAULT = "colony";
	/** Scoreboard team names are this prefix plus the faction id. */
	private static final String TEAM_PREFIX = "bc_";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final List<String> COLORS = List.of("red", "blue", "green", "yellow", "aqua", "light_purple", "gold", "white", "gray",
		"dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple");

	private static final class Data {
		List<Faction> factions = new ArrayList<>();
	}

	private static final Map<String, Faction> FACTIONS = new LinkedHashMap<>();
	@Nullable
	private static MinecraftServer server;
	private static boolean dirty;

	private Factions() {
	}

	// ---- lifecycle ------------------------------------------------------------------------------

	public static void load(MinecraftServer s) {
		server = s;
		FACTIONS.clear();
		Path f = file(s);
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Data d = GSON.fromJson(r, Data.class);
				if (d != null && d.factions != null) {
					for (Faction fac : d.factions) {
						if (fac != null && fac.id != null) {
							fac.repair();
							FACTIONS.put(fac.id, fac);
						}
					}
				}
			} catch (IOException | JsonParseException e) {
				Botcraft.LOG.error("Could not read {}", f, e);
			}
		}
		ensureDefault();
		Scope.reset();
	}

	private static void ensureDefault() {
		if (!FACTIONS.containsKey(DEFAULT)) {
			FACTIONS.put(DEFAULT, new Faction(DEFAULT, "Colony", "white", 0));
			dirty = true;
		}
	}

	public static void save() {
		if (server == null || !dirty) {
			return;
		}
		Path f = file(server);
		Data d = new Data();
		d.factions.addAll(FACTIONS.values());
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("factions.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(d, w);
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			Botcraft.LOG.error("Could not write {}", f, e);
		}
	}

	public static void unload() {
		save();
		FACTIONS.clear();
		server = null;
		Scope.reset();
	}

	private static Path file(MinecraftServer s) {
		return s.getSavePath(WorldSavePath.ROOT).resolve("botcraft").resolve("factions.json");
	}

	public static void markDirty() {
		dirty = true;
	}

	// ---- modes ----------------------------------------------------------------------------------

	public static String mode() {
		return CompanionConfig.get().factionMode;
	}

	/** More than one faction can exist (teams or dynamic mode). */
	public static boolean enabled() {
		return !"single".equals(mode());
	}

	public static boolean dynamic() {
		return "dynamic".equals(mode());
	}

	/** How this faction fights players and other factions: off, defend, pvp or raid. */
	public static String warfare(@Nullable Faction f) {
		if (dynamic()) {
			return "raid"; // dynamic factions fight and raid on their own
		}
		if (f != null && f.warfare != null) {
			return f.warfare;
		}
		return CompanionConfig.get().warfare;
	}

	// ---- factions -------------------------------------------------------------------------------

	@Nullable
	public static Faction get(String id) {
		return id == null ? null : FACTIONS.get(id.toLowerCase(Locale.ROOT));
	}

	public static Faction defaultFaction() {
		ensureDefault();
		return FACTIONS.get(DEFAULT);
	}

	public static Collection<Faction> all() {
		return FACTIONS.values();
	}

	/** The ids of all factions (the default first). */
	public static List<String> ids() {
		return new ArrayList<>(FACTIONS.keySet());
	}

	/** A faction by id or display name, or null. */
	@Nullable
	public static Faction find(String name) {
		if (name == null) {
			return null;
		}
		String n = name.toLowerCase(Locale.ROOT);
		Faction f = FACTIONS.get(n);
		if (f != null) {
			return f;
		}
		for (Faction o : FACTIONS.values()) {
			if (o.name.equalsIgnoreCase(name)) {
				return o;
			}
		}
		return null;
	}

	/** A new faction. @return null if the id is taken or not a valid word */
	@Nullable
	public static Faction create(String id, String name, String color, long now) {
		String key = id.toLowerCase(Locale.ROOT);
		if (!key.matches("[a-z0-9_]{1,12}") || FACTIONS.containsKey(key)) {
			return null;
		}
		Faction f = new Faction(key, name, COLORS.contains(color) ? color : COLORS.get(FACTIONS.size() % COLORS.size()), now);
		FACTIONS.put(key, f);
		dirty = true;
		return f;
	}

	/** Remove a faction; its companions go back to the default colony. */
	public static boolean remove(String id) {
		String key = id.toLowerCase(Locale.ROOT);
		if (key.equals(DEFAULT) || FACTIONS.remove(key) == null) {
			return false;
		}
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				if (key.equals(c.data.faction)) {
					c.data.faction = null;
				}
			}
		}
		for (Faction f : FACTIONS.values()) {
			f.relations.remove(key);
		}
		if (server != null) {
			Team t = server.getScoreboard().getTeam(teamName(key));
			if (t != null) {
				server.getScoreboard().removeTeam(t);
			}
		}
		dirty = true;
		return true;
	}

	/** The faction a companion is in (the default colony in single mode). */
	public static String idOf(Companion c) {
		if (!enabled()) {
			return DEFAULT;
		}
		String f = c.data.faction;
		return f != null && FACTIONS.containsKey(f) ? f : DEFAULT;
	}

	public static Faction of(Companion c) {
		Faction f = FACTIONS.get(idOf(c));
		return f != null ? f : defaultFaction();
	}

	/** Companions in a faction. */
	public static List<Companion> members(String id) {
		List<Companion> out = new ArrayList<>();
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				if (idOf(c).equals(id)) {
					out.add(c);
				}
			}
		}
		return out;
	}

	/** Put a companion in a faction. */
	public static void join(Companion c, String id) {
		c.data.faction = DEFAULT.equals(id) ? null : id;
		c.brain().reconsider();
		syncTeams();
	}

	// ---- between factions -----------------------------------------------------------------------

	/** Enemies: different factions whose standing with each other is hostile. */
	public static boolean hostile(String a, String b) {
		if (!enabled() || a.equals(b)) {
			return false;
		}
		Faction fa = FACTIONS.get(a);
		Faction fb = FACTIONS.get(b);
		if (fa == null || fb == null) {
			return false;
		}
		int hostile = CompanionConfig.get().hostileStanding;
		return fa.relation(b) <= hostile || fb.relation(a) <= hostile;
	}

	/** Same faction, or allied (standing 60 or more both ways). */
	public static boolean friendly(String a, String b) {
		if (a.equals(b)) {
			return true;
		}
		Faction fa = FACTIONS.get(a);
		Faction fb = FACTIONS.get(b);
		return fa != null && fb != null && fa.relation(b) >= 60 && fb.relation(a) >= 60;
	}

	/** Same side: the same faction, or allies. */
	public static boolean sameSide(Companion a, Companion b) {
		return friendly(idOf(a), idOf(b));
	}

	/** Set both factions' standing with each other. */
	public static void setRelation(String a, String b, int value) {
		Faction fa = FACTIONS.get(a);
		Faction fb = FACTIONS.get(b);
		if (fa != null && fb != null && !a.equals(b)) {
			fa.setRelation(b, value);
			fb.setRelation(a, value);
			dirty = true;
		}
	}

	/** One faction's member hurt another's: both sides think less of each other. */
	public static void clash(String a, String b, int by) {
		Faction fa = FACTIONS.get(a);
		Faction fb = FACTIONS.get(b);
		if (fa != null && fb != null && !a.equals(b)) {
			fa.setRelation(b, fa.relation(b) - by);
			fb.setRelation(a, fb.relation(a) - by);
			dirty = true;
		}
	}

	// ---- players --------------------------------------------------------------------------------

	public static Standing.Thresholds thresholds() {
		CompanionConfig cfg = CompanionConfig.get();
		return new Standing.Thresholds(cfg.orderStanding, cfg.requestStanding, cfg.hostileStanding);
	}

	/** A player counts as one of a faction if they're a member or own a companion in it. */
	public static boolean isMember(Faction f, UUID player) {
		if (f.rank(player.toString()) != null) {
			return true;
		}
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				if (player.equals(c.data.owner) && idOf(c).equals(f.id)) {
					return true;
				}
			}
		}
		return false;
	}

	/** What a faction thinks of a player (created at the starting value if they've never met). */
	public static Standing standing(Faction f, ServerPlayerEntity player) {
		String key = player.getUuidAsString();
		Standing s = f.players.get(key);
		long now = player.getServer().getOverworld().getTime();
		if (s == null) {
			CompanionConfig cfg = CompanionConfig.get();
			float start = cfg.playerStartStanding;
			if (isMember(f, player.getUuid())) {
				start = Math.max(start, cfg.ownerStartStanding); // our own people start out trusted
			}
			s = new Standing(player.getGameProfile().getName(), start, thresholds());
			s.decayedAt = now;
			f.players.put(key, s);
			dirty = true;
		}
		s.name = player.getGameProfile().getName();
		s.decay(now, thresholds());
		return s;
	}

	/** What a faction thinks of a player, without creating anything (null if never met). */
	@Nullable
	public static Standing peek(Faction f, UUID player) {
		return f.players.get(player.toString());
	}

	/**
	 * A player did something to a faction. Returns the band they were in before, if it changed
	 * (so the caller can say something about it), else null.
	 */
	@Nullable
	public static Standing.Band incident(Faction f, ServerPlayerEntity player, Standing.Incident incident, int times) {
		Standing s = standing(f, player);
		Standing.Band before = s.apply(incident, times, player.getServer().getOverworld().getTime(), thresholds());
		dirty = true;
		AiLog.log("faction", "{}: {} {} x{} -> {}", f.id, player.getGameProfile().getName(), incident, times, s.summary());
		return before;
	}

	// ---- scoreboard teams -----------------------------------------------------------------------

	public static String teamName(String id) {
		String n = TEAM_PREFIX + id;
		return n.length() > 16 ? n.substring(0, 16) : n;
	}

	/**
	 * Keep the scoreboard teams in line with the factions: one coloured team per faction (outside
	 * single mode) holding its companions and member players; none in single mode.
	 */
	public static void syncTeams() {
		if (server == null) {
			return;
		}
		Scoreboard board = server.getScoreboard();
		CompanionManager m = CompanionManager.get();
		if (!enabled()) {
			for (String id : ids()) {
				Team t = board.getTeam(teamName(id));
				if (t != null) {
					board.removeTeam(t);
				}
			}
			return;
		}
		for (Faction f : FACTIONS.values()) {
			String name = teamName(f.id);
			Team t = board.getTeam(name);
			if (t == null) {
				t = board.addTeam(name);
			}
			Formatting color = Formatting.byName(f.color);
			t.setColor(color != null && color.isColor() ? color : Formatting.WHITE);
			t.setDisplayName(Text.literal(f.name));
			t.setFriendlyFireAllowed(false);
			t.setShowFriendlyInvisibles(true);
			for (String uuid : f.members.keySet()) {
				ServerPlayerEntity pl = server.getPlayerManager().getPlayer(UUID.fromString(uuid));
				if (pl != null && !(pl instanceof CompanionPlayer)) {
					board.addPlayerToTeam(pl.getGameProfile().getName(), t);
				}
			}
		}
		if (m != null) {
			for (Companion c : m.all()) {
				Team t = board.getTeam(teamName(idOf(c)));
				if (t != null) {
					board.addPlayerToTeam(c.name(), t);
				}
			}
		}
	}

	/**
	 * Split companions into teams: "even" deals them out in turn, "owner" keeps each owner's
	 * companions together. Creates the teams (enemies of each other) if they don't exist.
	 */
	public static List<String> makeTeams(List<String> names, String how, long now) {
		List<String> ids = new ArrayList<>();
		for (String n : names) {
			String id = n.toLowerCase(Locale.ROOT);
			Faction f = get(id);
			if (f == null) {
				f = create(id, Character.toUpperCase(n.charAt(0)) + n.substring(1).toLowerCase(Locale.ROOT), COLORS.contains(id) ? id
					: COLORS.get(ids.size() % COLORS.size()), now);
			}
			if (f != null) {
				ids.add(f.id);
			}
		}
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				setRelation(ids.get(i), ids.get(j), -50);
			}
		}
		CompanionManager m = CompanionManager.get();
		if (m == null || ids.isEmpty()) {
			return ids;
		}
		List<Companion> all = new ArrayList<>(m.all());
		all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		if ("owner".equals(how)) {
			Map<UUID, String> byOwner = new LinkedHashMap<>();
			for (Companion c : all) {
				String team = byOwner.computeIfAbsent(c.data.owner == null ? new UUID(0, 0) : c.data.owner,
					o -> ids.get(byOwner.size() % ids.size()));
				c.data.faction = team;
			}
		} else {
			for (int i = 0; i < all.size(); i++) {
				all.get(i).data.faction = ids.get(i % ids.size());
			}
		}
		for (Companion c : all) {
			c.brain().reconsider();
		}
		dirty = true;
		syncTeams();
		return ids;
	}
}

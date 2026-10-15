package com.botcraft.command;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.brain.Personality;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.faction.Faction;
import com.botcraft.faction.Factions;
import com.botcraft.faction.Standing;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /companion faction ... (groups, teams, relations, warfare), /companion standing (what a faction
 * thinks of a player), /companion archetype and /companion trait (personality).
 */
public final class FactionCommand {
	private FactionCommand() {
	}

	private static final SuggestionProvider<ServerCommandSource> FACTIONS = (ctx, b) ->
		CommandSource.suggestMatching(Factions.ids(), b);
	private static final SuggestionProvider<ServerCommandSource> COMPANIONS = (ctx, b) -> {
		CompanionManager m = CompanionManager.get();
		List<String> names = new ArrayList<>();
		if (m != null) {
			for (Companion c : m.all()) {
				names.add(c.name());
			}
		}
		return CommandSource.suggestMatching(names, b);
	};
	private static final SuggestionProvider<ServerCommandSource> PLAYERS = (ctx, b) ->
		CommandSource.suggestMatching(ctx.getSource().getServer().getPlayerNames(), b);

	public static LiteralArgumentBuilder<ServerCommandSource> faction() {
		return CommandManager.literal("faction")
			.executes(FactionCommand::list)
			.then(CommandManager.literal("mode")
				.then(CommandManager.argument("mode", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("single", "teams", "dynamic"), b))
					.executes(FactionCommand::mode)))
			.then(CommandManager.literal("warfare")
				.then(CommandManager.argument("level", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("off", "defend", "pvp", "raid"), b))
					.executes(ctx -> warfare(ctx, null))
					.then(CommandManager.argument("faction", StringArgumentType.word())
						.suggests(FACTIONS)
						.executes(ctx -> warfare(ctx, StringArgumentType.getString(ctx, "faction"))))))
			.then(CommandManager.literal("teams")
				.then(CommandManager.argument("teams", StringArgumentType.greedyString())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("red blue", "red blue owner", "red blue green yellow"), b))
					.executes(FactionCommand::teams)))
			.then(CommandManager.literal("create")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.executes(ctx -> create(ctx, ""))
					.then(CommandManager.argument("color", StringArgumentType.word())
						.suggests((ctx, b) -> CommandSource.suggestMatching(Factions.COLORS, b))
						.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "color"))))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests(FACTIONS)
					.executes(FactionCommand::remove)))
			.then(CommandManager.literal("join")
				.then(CommandManager.argument("companion", StringArgumentType.word())
					.suggests(COMPANIONS)
					.then(CommandManager.argument("faction", StringArgumentType.word())
						.suggests(FACTIONS)
						.executes(FactionCommand::join))))
			.then(CommandManager.literal("member")
				.then(CommandManager.argument("faction", StringArgumentType.word())
					.suggests(FACTIONS)
					.then(CommandManager.argument("player", StringArgumentType.word())
						.suggests(PLAYERS)
						.then(CommandManager.argument("rank", StringArgumentType.word())
							.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("member", "officer", "leader", "remove"), b))
							.executes(FactionCommand::member)))))
			.then(CommandManager.literal("relation")
				.then(CommandManager.argument("a", StringArgumentType.word())
					.suggests(FACTIONS)
					.then(CommandManager.argument("b", StringArgumentType.word())
						.suggests(FACTIONS)
						.then(CommandManager.argument("value", StringArgumentType.word())
							.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("allied", "neutral", "hostile", "-50", "0", "75"), b))
							.executes(FactionCommand::relation)))))
			.then(CommandManager.literal("raid")
				.then(CommandManager.argument("faction", StringArgumentType.word())
					.suggests(FACTIONS)
					.executes(FactionCommand::raid)))
			.then(CommandManager.literal("ceasefire")
				.executes(FactionCommand::ceasefire))
			.then(CommandManager.literal("merge")
				.then(CommandManager.argument("into", StringArgumentType.word())
					.suggests(FACTIONS)
					.then(CommandManager.argument("from", StringArgumentType.word())
						.suggests(FACTIONS)
						.executes(FactionCommand::merge))))
			.then(CommandManager.literal("authority")
				.executes(ctx -> authority(ctx, null))
				.then(CommandManager.argument("mode", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("absolute", "standing"), b))
					.executes(ctx -> authority(ctx, StringArgumentType.getString(ctx, "mode")))))
			.then(CommandManager.literal("info")
				.then(CommandManager.argument("faction", StringArgumentType.word())
					.suggests(FACTIONS)
					.executes(FactionCommand::info)));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> standing() {
		return CommandManager.literal("standing")
			.then(CommandManager.argument("faction", StringArgumentType.word())
				.suggests((ctx, b) -> {
					List<String> all = new ArrayList<>(Factions.ids());
					all.addAll(List.of(ctx.getSource().getServer().getPlayerNames()));
					return CommandSource.suggestMatching(all, b);
				})
				.executes(ctx -> standing(ctx, null, null))
				.then(CommandManager.argument("player", StringArgumentType.word())
					.suggests(PLAYERS)
					.executes(ctx -> standing(ctx, StringArgumentType.getString(ctx, "player"), null))
					.then(CommandManager.argument("value", IntegerArgumentType.integer(-100, 100))
						.executes(ctx -> standing(ctx, StringArgumentType.getString(ctx, "player"), IntegerArgumentType.getInteger(ctx, "value"))))));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> archetype() {
		return CommandManager.literal("archetype")
			.then(CommandManager.argument("name", StringArgumentType.word())
				.suggests(COMPANIONS)
				.then(CommandManager.argument("archetype", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(Personality.ARCHETYPES.keySet(), b))
					.executes(FactionCommand::archetypeSet)));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> trait() {
		return CommandManager.literal("trait")
			.then(CommandManager.argument("name", StringArgumentType.word())
				.suggests(COMPANIONS)
				.then(CommandManager.argument("trait", StringArgumentType.word())
					.suggests((ctx, b) -> {
						List<String> all = new ArrayList<>(List.of("chattiness", "bravery", "curiosity", "helpfulness"));
						all.addAll(Personality.EXTRA);
						return CommandSource.suggestMatching(all, b);
					})
					.then(CommandManager.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
						.executes(FactionCommand::traitSet))));
	}

	// ---- handlers ---------------------------------------------------------------------------------

	private static void say(CommandContext<ServerCommandSource> ctx, String line, Formatting f) {
		ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(f), false);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		say(ctx, "Factions (mode " + Factions.mode() + ", warfare " + CompanionConfig.get().warfare + ", owner authority "
			+ CompanionConfig.get().ownerAuthority + "):", Formatting.AQUA);
		for (Faction f : Factions.all()) {
			int n = Factions.members(f.id).size();
			if (Factions.loners(f.id)) {
				say(ctx, "  no faction: " + n + " companion(s), each for itself (no relations, wars or land)", Formatting.DARK_GRAY);
				continue;
			}
			say(ctx, "  " + f.id + " \"" + f.name + "\" (" + f.color + "): " + n + " companion(s), " + f.members.size() + " player member(s), warfare "
				+ Factions.warfare(f), Formatting.GRAY);
		}
		if (!Factions.enabled()) {
			say(ctx, "  Single mode: everyone is in one colony. /companion faction teams red blue splits them up.", Formatting.DARK_GRAY);
		}
		return 1;
	}

	private static int mode(CommandContext<ServerCommandSource> ctx) {
		String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
		if (!List.of("single", "teams", "dynamic").contains(mode)) {
			ctx.getSource().sendError(Text.literal("Modes are single, teams and dynamic"));
			return 0;
		}
		CompanionConfig.get().factionMode = mode;
		CompanionConfig.save();
		Factions.syncTeams();
		reconsiderAll();
		say(ctx, "Faction mode: " + mode + (mode.equals("dynamic") ? " (factions fight and raid on their own)" : ""), Formatting.WHITE);
		return 1;
	}

	private static int warfare(CommandContext<ServerCommandSource> ctx, String factionId) {
		String level = StringArgumentType.getString(ctx, "level").toLowerCase(Locale.ROOT);
		if (!List.of("off", "defend", "pvp", "raid").contains(level)) {
			ctx.getSource().sendError(Text.literal("Warfare is off, defend, pvp or raid"));
			return 0;
		}
		if (factionId == null) {
			CompanionConfig.get().warfare = level;
			CompanionConfig.save();
			say(ctx, "Warfare for everyone: " + level + (Factions.dynamic() ? " (dynamic factions still raid)" : ""), Formatting.WHITE);
		} else {
			Faction f = Factions.find(factionId);
			if (f == null) {
				ctx.getSource().sendError(Text.literal("No faction " + factionId));
				return 0;
			}
			f.warfare = level;
			Factions.markDirty();
			say(ctx, "Warfare for " + f.name + ": " + level, Formatting.WHITE);
		}
		return 1;
	}

	private static int teams(CommandContext<ServerCommandSource> ctx) {
		List<String> words = new ArrayList<>(Arrays.asList(StringArgumentType.getString(ctx, "teams").trim().toLowerCase(Locale.ROOT).split("\\s+")));
		String how = "even";
		if (!words.isEmpty() && (words.get(words.size() - 1).equals("owner") || words.get(words.size() - 1).equals("even"))) {
			how = words.remove(words.size() - 1);
		}
		words.removeIf(w -> !w.matches("[a-z0-9_]{1,12}") || w.equals(Factions.DEFAULT));
		if (words.size() < 2) {
			ctx.getSource().sendError(Text.literal("Name at least two teams, e.g. /companion faction teams red blue"));
			return 0;
		}
		if (!Factions.enabled()) {
			CompanionConfig.get().factionMode = "teams";
			CompanionConfig.save();
		}
		List<String> ids = Factions.makeTeams(words, how, ctx.getSource().getServer().getOverworld().getTime());
		reconsiderAll();
		for (String id : ids) {
			List<String> names = Factions.members(id).stream().map(Companion::name).toList();
			say(ctx, "Team " + id + ": " + (names.isEmpty() ? "nobody yet" : String.join(", ", names)), Formatting.WHITE);
		}
		say(ctx, "Teams start as strangers: neutral, with " + CompanionConfig.get().factionGraceMinutes + " minutes' grace before anything can turn to war "
			+ "(set it with /companion faction relation). Each needs a base: tell a member \"this is home\", or let them find one.",
			Formatting.GRAY);
		return ids.size();
	}

	private static int create(CommandContext<ServerCommandSource> ctx, String color) {
		String id = StringArgumentType.getString(ctx, "id");
		Faction f = Factions.create(id, Character.toUpperCase(id.charAt(0)) + id.substring(1), color.isEmpty() ? "" : color,
			ctx.getSource().getServer().getOverworld().getTime());
		if (f == null) {
			ctx.getSource().sendError(Text.literal("Faction ids are 1-12 letters, digits or _, and must be new"));
			return 0;
		}
		if (!Factions.enabled()) {
			say(ctx, "Note: faction mode is single, so it has no effect until /companion faction mode teams (or dynamic).", Formatting.YELLOW);
		}
		Factions.syncTeams();
		say(ctx, "Created faction " + f.id + " (" + f.color + ")", Formatting.WHITE);
		return 1;
	}

	private static int remove(CommandContext<ServerCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!Factions.remove(id)) {
			ctx.getSource().sendError(Text.literal("No faction " + id + " (the default colony can't be removed)"));
			return 0;
		}
		Colony.drop(id.toLowerCase(Locale.ROOT));
		reconsiderAll();
		say(ctx, "Removed faction " + id + "; its companions are back in the colony", Formatting.WHITE);
		return 1;
	}

	private static int join(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "companion");
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null || f == null) {
			ctx.getSource().sendError(Text.literal(c == null ? "No companion named " + name : "No such faction"));
			return 0;
		}
		if (com.botcraft.brain.EasterEggs.factionless(c)) {
			ctx.getSource().sendError(Text.literal(c.name() + " belongs to no faction"));
			return 0;
		}
		Factions.join(c, f.id);
		Factions.markDirty();
		say(ctx, c.name() + " is now in " + f.name + (Factions.enabled() ? "" : " (takes effect outside single mode)"), Formatting.WHITE);
		return 1;
	}

	private static int member(CommandContext<ServerCommandSource> ctx) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		String playerName = StringArgumentType.getString(ctx, "player");
		String rank = StringArgumentType.getString(ctx, "rank").toLowerCase(Locale.ROOT);
		CompanionManager m = CompanionManager.get();
		Companion companion = m == null ? null : m.byName(playerName);
		if (f != null && companion != null) {
			return companionMember(ctx, f, companion, rank);
		}
		ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		if (f == null || player == null) {
			ctx.getSource().sendError(Text.literal(f == null ? "No such faction" : "No player " + playerName + " online"));
			return 0;
		}
		String uuid = player.getUuidAsString();
		if (rank.equals("remove")) {
			f.members.remove(uuid);
			f.manual.remove(uuid);
		} else {
			Faction.Rank r;
			try {
				r = Faction.Rank.valueOf(rank.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				ctx.getSource().sendError(Text.literal("Ranks are member, officer and leader (or remove)"));
				return 0;
			}
			// One faction at a time.
			for (Faction o : Factions.all()) {
				o.members.remove(uuid);
				o.manual.remove(uuid);
			}
			if (r == Faction.Rank.LEADER) {
				f.members.entrySet().removeIf(e -> e.getValue() == Faction.Rank.LEADER);
			}
			f.members.put(uuid, r);
			f.manual.add(uuid);
			Factions.standing(f, player).set(Math.max(Factions.standing(f, player).value, CompanionConfig.get().orderStanding), Factions.thresholds());
		}
		Factions.markDirty();
		Factions.syncTeams();
		say(ctx, playerName + (rank.equals("remove") ? " left " : " is now " + rank + " of ") + f.name, Formatting.WHITE);
		return 1;
	}

	/** A companion given a rank: it joins the faction (a leader founds and speaks for it). */
	private static int companionMember(CommandContext<ServerCommandSource> ctx, Faction f, Companion c, String rank) {
		if (com.botcraft.brain.EasterEggs.factionless(c)) {
			ctx.getSource().sendError(Text.literal(c.name() + " belongs to no faction"));
			return 0;
		}
		String uuid = c.data.uuid.toString();
		if (rank.equals("remove")) {
			f.members.remove(uuid);
			f.manual.remove(uuid);
			if (Factions.idOf(c).equals(f.id)) {
				Factions.join(c, Factions.DEFAULT);
			}
			Factions.markDirty();
			say(ctx, c.name() + " left " + f.name + " (back in the colony)", Formatting.WHITE);
			return 1;
		}
		Faction.Rank r;
		try {
			r = Faction.Rank.valueOf(rank.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Text.literal("Ranks are member, officer and leader (or remove)"));
			return 0;
		}
		Factions.join(c, f.id);
		f.members.put(uuid, r);
		if (!f.manual.contains(uuid)) {
			f.manual.add(uuid);
		}
		if (r == Faction.Rank.LEADER) {
			// One leader at a time.
			f.members.entrySet().removeIf(e -> e.getValue() == Faction.Rank.LEADER && !e.getKey().equals(uuid));
			f.members.put(uuid, r);
			c.speech().event("faction.joined_rank", Map.of("faction", f.name), 0, 1.0F);
		}
		Factions.markDirty();
		say(ctx, c.name() + " is now " + rank + " of " + f.name + (Factions.enabled() ? "" : " (takes effect outside single mode)")
			+ ("free".equals(c.data.mode) ? "" : "; it's still following its owner (\"do your own thing\" lets it live with its faction)"), Formatting.WHITE);
		return 1;
	}

	private static int relation(CommandContext<ServerCommandSource> ctx) {
		Faction a = Factions.find(StringArgumentType.getString(ctx, "a"));
		Faction b = Factions.find(StringArgumentType.getString(ctx, "b"));
		String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
		if (a == null || b == null || a == b) {
			ctx.getSource().sendError(Text.literal("Name two different factions"));
			return 0;
		}
		Integer value = switch (v) {
			case "allied", "ally", "allies" -> 75;
			case "neutral", "peace" -> 0;
			case "hostile", "enemy", "enemies", "war" -> -60;
			default -> v.matches("-?\\d{1,3}") ? Integer.valueOf(v) : null;
		};
		if (value == null) {
			ctx.getSource().sendError(Text.literal("Use allied, neutral, hostile or a number from -100 to 100"));
			return 0;
		}
		Factions.setRelation(a.id, b.id, value);
		say(ctx, a.name + " and " + b.name + ": " + value + (Factions.hostile(a.id, b.id) ? " (enemies)" : Factions.friendly(a.id, b.id) ? " (allies)" : ""),
			Formatting.WHITE);
		return 1;
	}

	/** /companion faction raid <faction>: send that faction's fighters against its nearest enemy now. */
	private static int raid(CommandContext<ServerCommandSource> ctx) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction"));
			return 0;
		}
		String result = com.botcraft.faction.Raids.consider(f, ctx.getSource().getServer().getOverworld().getTime(), true);
		say(ctx, f.name + ": " + result + (CompanionConfig.get().griefing ? "" : " (griefing is off: a skirmish, nothing broken or taken)"), Formatting.WHITE);
		return 1;
	}

	private static int ceasefire(CommandContext<ServerCommandSource> ctx) {
		List<String> ids = Factions.ids();
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				if (Factions.hostile(ids.get(i), ids.get(j))) {
					Factions.setRelation(ids.get(i), ids.get(j), 0);
				}
			}
		}
		for (Faction f : Factions.all()) {
			for (Standing s : f.players.values()) {
				if (s.band == Standing.Band.HOSTILE) {
					s.set(0, Factions.thresholds());
				}
			}
		}
		Factions.markDirty();
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				c.brain().clearAll();
			}
		}
		say(ctx, "Ceasefire: every faction and player is back to neutral", Formatting.WHITE);
		return 1;
	}

	/**
	 * Owner authority (saved in the config): "absolute", owners are always obeyed and never fought;
	 * "standing", owners are judged by how they treat their companions, like anyone else.
	 */
	private static int authority(CommandContext<ServerCommandSource> ctx, String mode) {
		CompanionConfig cfg = CompanionConfig.get();
		if (mode == null) {
			say(ctx, "Owner authority: " + cfg.ownerAuthority + ("standing".equals(cfg.ownerAuthority)
				? " (owners are judged by how they treat their companions, like anyone else)"
				: " (owners are always obeyed and never fought)"), Formatting.AQUA);
			return 1;
		}
		if (!mode.equals("absolute") && !mode.equals("standing")) {
			ctx.getSource().sendError(Text.literal("Owner authority is absolute or standing"));
			return 0;
		}
		cfg.ownerAuthority = mode;
		CompanionConfig.save();
		if (mode.equals("standing")) {
			say(ctx, "Owner authority: standing. Owners now earn their companions' obedience like anyone else: trusted (60+) may give orders, "
				+ "friendly (20+) ask favours; hurt them and they stop listening, fight back and may put a price on your head. "
				+ "See /companion standing <faction> <you> (and set it to start afresh).", Formatting.WHITE);
		} else {
			say(ctx, "Owner authority: absolute. Your companions always obey you and never fight you (they still remember how you treat them).",
				Formatting.WHITE);
		}
		return 1;
	}

	/** /companion faction merge <into> <from>: {@code from} joins {@code into} now. */
	private static int merge(CommandContext<ServerCommandSource> ctx) {
		Faction into = Factions.find(StringArgumentType.getString(ctx, "into"));
		Faction from = Factions.find(StringArgumentType.getString(ctx, "from"));
		if (into == null || from == null || into == from || Factions.loners(into.id) || Factions.loners(from.id)) {
			ctx.getSource().sendError(Text.literal("Name two different factions (not the factionless)"));
			return 0;
		}
		String fromName = from.name;
		com.botcraft.faction.Merger.merge(into, from, ctx.getSource().getServer().getOverworld().getTime(), "by command");
		say(ctx, fromName + " merged into " + into.name, Formatting.WHITE);
		return 1;
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction"));
			return 0;
		}
		say(ctx, f.name + " (" + f.id + ", " + f.color + "), warfare " + Factions.warfare(f), Formatting.AQUA);
		List<String> names = new ArrayList<>();
		for (Companion c : Factions.members(f.id)) {
			Faction.Rank r = f.rank(c.data.uuid.toString());
			names.add(c.name() + (r == null || r == Faction.Rank.MEMBER ? "" : " (" + r.word() + ")"));
		}
		say(ctx, "  companions: " + (names.isEmpty() ? "none" : String.join(", ", names)), Formatting.GRAY);
		List<String> ps = new ArrayList<>();
		for (Map.Entry<String, Faction.Rank> e : f.members.entrySet()) {
			Standing s = f.players.get(e.getKey());
			if (s != null) {
				ps.add(s.name + " (" + e.getValue().word() + ")");
			}
		}
		if (!ps.isEmpty()) {
			say(ctx, "  players: " + String.join(", ", ps), Formatting.GRAY);
		}
		Colony colony = Colony.of(f.id);
		say(ctx, "  base: " + (colony == null || !colony.hasBase() ? "none yet" : colony.base().toShortString() + ", " + colony.chests().size() + " chest(s)"),
			Formatting.GRAY);
		for (Faction o : Factions.all()) {
			if (o != f && (f.relation(o.id) != 0 || o.relation(f.id) != 0)) {
				say(ctx, "  " + o.name + ": " + f.relation(o.id) + (Factions.hostile(f.id, o.id) ? " (enemies)" : Factions.friendly(f.id, o.id) ? " (allies)" : ""),
					Formatting.GRAY);
			}
		}
		if (com.botcraft.faction.Territory.on()) {
			say(ctx, "  land: " + com.botcraft.faction.Territory.size(f) + " chunk(s)" + (f.met.isEmpty() ? "" : ", borders " + String.join(", ", f.met)),
				Formatting.GRAY);
		}
		long now = ctx.getSource().getServer().getOverworld().getTime();
		List<Faction.Bounty> bounties = com.botcraft.faction.Bounties.list(f, now);
		if (!bounties.isEmpty()) {
			List<String> bs = new ArrayList<>();
			for (Faction.Bounty b : bounties) {
				bs.add(b.name + " " + String.format(java.util.Locale.ROOT, "%.1f", b.value) + " (" + b.kills + " kill" + (b.kills == 1 ? "" : "s") + ")"
					+ (b.value >= com.botcraft.faction.Bounties.WANTED ? " WANTED" : ""));
			}
			say(ctx, "  bounties: " + String.join(", ", bs), Formatting.GOLD);
		}
		if (!f.players.isEmpty()) {
			say(ctx, "  standing:", Formatting.GRAY);
			for (Standing s : f.players.values()) {
				say(ctx, "    " + s.summary(), s.band == Standing.Band.HOSTILE ? Formatting.RED : Formatting.DARK_GRAY);
			}
		}
		return 1;
	}

	private static int standing(CommandContext<ServerCommandSource> ctx, String playerName, Integer value) {
		String first = StringArgumentType.getString(ctx, "faction");
		Faction f = Factions.find(first);
		if (f == null && playerName == null) {
			return standingEverywhere(ctx, first); // "/companion standing Steve": Steve with every faction
		}
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction"));
			return 0;
		}
		if (playerName == null) {
			if (f.players.isEmpty()) {
				say(ctx, f.name + " hasn't met anyone yet", Formatting.GRAY);
			}
			for (Standing s : f.players.values()) {
				say(ctx, s.summary(), s.band == Standing.Band.HOSTILE ? Formatting.RED : Formatting.GRAY);
			}
			return f.players.size();
		}
		ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		if (player == null) {
			for (Standing s : f.players.values()) {
				if (s.name.equalsIgnoreCase(playerName)) {
					if (value != null) {
						s.set(value, Factions.thresholds());
						Factions.markDirty();
					}
					say(ctx, f.name + ": " + s.summary(), Formatting.WHITE);
					return 1;
				}
			}
			ctx.getSource().sendError(Text.literal(f.name + " doesn't know " + playerName));
			return 0;
		}
		Standing s = Factions.standing(f, player);
		if (value != null) {
			s.set(value, Factions.thresholds());
			Factions.markDirty();
		}
		say(ctx, f.name + ": " + s.summary(), Formatting.WHITE);
		return 1;
	}

	/** What every faction thinks of one player (online or remembered by name). */
	private static int standingEverywhere(CommandContext<ServerCommandSource> ctx, String playerName) {
		ServerPlayerEntity online = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		String name = online != null ? online.getGameProfile().getName() : playerName;
		boolean absolute = !"standing".equals(CompanionConfig.get().ownerAuthority);
		int owned = 0;
		CompanionManager m = CompanionManager.get();
		if (online != null && m != null) {
			owned = (int) m.all().stream().filter(c -> online.getUuid().equals(c.data.owner)).count();
		}
		say(ctx, name + "'s standing" + (owned > 0 ? " (owns " + owned + " companion(s); owner authority " + CompanionConfig.get().ownerAuthority
			+ (absolute ? ": always obeyed by them" : ": judged like anyone") + ")" : "") + ":", Formatting.AQUA);
		int known = 0;
		for (Faction f : Factions.all()) {
			if (Factions.enabled() && f.id.equals(Factions.DEFAULT) && Factions.members(f.id).isEmpty() && f.players.isEmpty()) {
				continue; // the empty default colony, outside single mode
			}
			Standing s = null;
			if (online != null) {
				s = f.players.get(online.getUuidAsString());
			}
			if (s == null) {
				for (Standing o : f.players.values()) {
					if (o.name != null && o.name.equalsIgnoreCase(name)) {
						s = o;
						break;
					}
				}
			}
			Faction.Rank rank = online == null ? null : f.rank(online.getUuidAsString());
			String suffix = rank == null ? "" : ", " + rank.word();
			if (s == null) {
				say(ctx, "  " + f.name + ": never met" + suffix, Formatting.DARK_GRAY);
				continue;
			}
			known++;
			Formatting color = switch (s.band) {
				case HOSTILE -> Formatting.RED;
				case TRUSTED -> Formatting.GREEN;
				case FRIENDLY -> Formatting.WHITE;
				case NEUTRAL -> Formatting.GRAY;
			};
			say(ctx, "  " + f.name + ": " + String.format(Locale.ROOT, "%+.0f (%s)", s.value, s.band.word()) + suffix, color);
		}
		if (known == 0 && online == null) {
			ctx.getSource().sendError(Text.literal("No faction knows " + playerName + " (and it isn't a faction either)"));
			return 0;
		}
		return Math.max(1, known);
	}

	private static int archetypeSet(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		String type = StringArgumentType.getString(ctx, "archetype");
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		if (!Personality.applyArchetype(c.data, type)) {
			ctx.getSource().sendError(Text.literal("Archetypes: " + String.join(", ", Personality.ARCHETYPES.keySet())));
			return 0;
		}
		c.brain().reconsider();
		say(ctx, c.name() + " is now a " + type + ": " + c.brain().personality().summary(), Formatting.WHITE);
		return 1;
	}

	private static int traitSet(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		String trait = StringArgumentType.getString(ctx, "trait").toLowerCase(Locale.ROOT);
		float value = FloatArgumentType.getFloat(ctx, "value");
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		if (!Personality.set(c.data, trait, value)) {
			ctx.getSource().sendError(Text.literal("No trait " + trait));
			return 0;
		}
		say(ctx, c.name() + ": " + c.brain().personality().summary(), Formatting.WHITE);
		return 1;
	}

	private static void reconsiderAll() {
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				c.brain().reconsider();
			}
		}
	}
}
